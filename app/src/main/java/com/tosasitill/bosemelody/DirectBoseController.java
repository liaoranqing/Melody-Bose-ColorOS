package com.tosasitill.bosemelody;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Short-lived Bose BMAP RFCOMM controller owned by the Melody process. */
final class DirectBoseController {
    private static final long CONNECT_TIMEOUT_MS = 4_000L;
    private static final long RESPONSE_TIMEOUT_MS = 3_000L;
    private static final long PRESENCE_TTL_MS = 10_000L;
    /**
     * An ACL_DISCONNECTED broadcast fired while Bose Music tears down can be
     * spurious: the classic link often stays up (or silently re-establishes
     * without a new ACL_CONNECTED broadcast). A negative presence hint is
     * therefore only honoured for this TTL; afterwards reachability falls back
     * to the bonded-device probe instead of dropping every click forever
     * (the "must force-stop Melody to regain control" symptom).
     */
    private static final long ACL_NEGATIVE_TTL_MS = 5_000L;
    private static final long[] RETRY_BACKOFF_MS = new long[]{300L, 800L, 1500L};
    private static final int MAX_FAILED_OPENS = 4;
    private static final long MIN_REQUEST_GAP_MS = 700L;
    private static final long SETTLE_DELAY_MS = 150L;
    private static final long POST_WRITE_DELAY_MS = 200L;
    /** A healthy worker never blocks longer than ~9s; past this it is wedged. */
    private static final long WORKER_STUCK_MS = 12_000L;
    /**
     * Hard bound on a single socket write. Android's RFCOMM write blocks in
     * native send() forever on a half-dead link and a close() from another
     * thread does not reliably interrupt it, so the write is delegated to a
     * disposable daemon thread that can simply be abandoned.
     */
    private static final long WRITE_TIMEOUT_MS = 2_500L;
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, int[]> BATTERY = new ConcurrentHashMap<>();
    private static volatile int aclPresent;
    /** When {@link #aclPresent} was last set; a negative value expires after a TTL. */
    private static volatile long aclPresentAt;

    private DirectBoseController() {
    }

    static void requestMode(Context context, String address, int mode) {
        Session session = session(context, address);
        if (session != null) session.requestMode(mode);
    }

    static void syncOnce(Context context, String address) {
        Session session = session(context, address);
        if (session != null) session.syncOnce();
    }

    /** Force a fresh BMAP battery/state read and discard any stale battery cache. */
    static void refreshState(Context context, String address) {
        Session session = session(context, address);
        if (session != null) session.refreshState();
    }

    static boolean reachable(Context context, String address) {
        Session session = session(context, address);
        return session != null && session.reachable();
    }

    static int[] cachedBattery(String address) {
        int[] values = BATTERY.get(BoseDeviceConfig.normalize(address));
        return values == null ? null : values.clone();
    }

    static int cachedMode() {
        return MelodyProviderHook.boseModeCache();
    }

    static void setCnc(Context context, int level) {
        Session session = session(context, BoseDeviceConfig.MAC);
        if (session != null) session.enqueue(new Operation("cnc", new int[]{level}));
    }

    static void setPresent(boolean present) {
        aclPresent = present ? 1 : -1;
        aclPresentAt = SystemClock.elapsedRealtime();
        Logs.trace("bose acl present=" + present);
        for (Session session : SESSIONS.values()) session.onAcl(aclPresent);
    }

    static void disconnect(Context context, String address, String reason) {
        Session session = session(context, address);
        if (session != null) session.release(reason);
    }

    private static Session session(Context context, String address) {
        if (!BoseDeviceConfig.isMac(address)) return null;
        String key = BoseDeviceConfig.normalize(address);
        Session old = SESSIONS.get(key);
        if (old != null) return old;
        Context app = context == null ? null : context.getApplicationContext();
        Session created = new Session(app != null ? app : context, address);
        Session raced = SESSIONS.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }

    private static final class Operation {
        final String name;
        final int[] values;

        Operation(String name, int[] values) {
            this.name = name;
            this.values = values;
        }
    }

    private static final class Session implements BoseBmap.Sink {
        private final String address;
        private final String key;
        private volatile Context context;
        private final Object lock = new Object();
        private final Object replyLock = new Object();
        private int wantedMode = -1;
        private boolean wantedSync;
        private Operation operation;
        private boolean workerStarted;
        private volatile long lastRequestAt;
        private int failedOpens;
        private volatile boolean synced;
        private boolean syncInFlight;
        private volatile BluetoothSocket socket;
        private volatile boolean linkDead;
        private volatile int replyBlock = -1;
        private volatile int replyFunction = -1;
        private volatile BoseBmap.Frame reply;
        private volatile boolean connectorRunning;
        private volatile long probeAt;
        private volatile int probeResult;
        private final Object ioLock = new Object();
        private final AtomicInteger connectGen = new AtomicInteger();
        private volatile int activeConnectGen = -1;
        private final AtomicInteger workerGen = new AtomicInteger();
        private volatile int activeWorkerGen = -1;
        /** Last time the worker made forward progress; the wedge watchdog reads it. */
        private volatile long workerProgressAt;

        Session(Context context, String address) {
            this.context = context;
            this.address = address;
            this.key = BoseDeviceConfig.normalize(address);
        }

        boolean reachable() {
            if (aclPresent > 0) return true;
            // A negative hint only vetoes for a short TTL; afterwards fall
            // through to the bonded probe, because Bose Music teardown can fire
            // a spurious ACL_DISCONNECTED while the link is still usable.
            if (aclPresent < 0 && !aclNegativeExpired()) return false;
            long now = SystemClock.elapsedRealtime();
            if (now - probeAt < PRESENCE_TTL_MS) return probeResult != 0;
            probeAt = now;
            probeResult = probeConnected();
            return probeResult != 0;
        }

        private static boolean aclNegativeExpired() {
            return SystemClock.elapsedRealtime() - aclPresentAt > ACL_NEGATIVE_TTL_MS;
        }

        void onAcl(int state) {
            if (state < 0) {
                synchronized (lock) {
                    synced = false;
                    syncInFlight = false;
                }
            }
        }

        void requestMode(int mode) {
            synchronized (lock) {
                // ColorOS can deliver the same tile click several times in one
                // gesture. One Cambridge radio only tolerates one session, so
                // merge repeats that arrive inside the current request window.
                long now = SystemClock.elapsedRealtime();
                if (wantedMode == mode && now - lastRequestAt < MIN_REQUEST_GAP_MS) {
                    Logs.trace("bose duplicate click ignored mode=" + mode);
                    return;
                }
                lastRequestAt = now;
            }
            Logs.trace("bose request mode=" + mode);
            synchronized (lock) {
                wantedMode = mode;
                startWorker();
                lock.notifyAll();
            }
        }

        void enqueue(Operation value) {
            Logs.trace("bose enqueue " + value.name);
            synchronized (lock) {
                operation = value;
                startWorker();
                lock.notifyAll();
            }
        }

        void syncOnce() {
            synchronized (lock) {
                if (synced || syncInFlight) return;
            }
            if (!reachable()) {
                Logs.trace("bose sync skipped: device not reachable");
                return;
            }
            synchronized (lock) {
                if (synced || syncInFlight) return;
                syncInFlight = true;
                wantedSync = true;
                startWorker();
                lock.notifyAll();
            }
        }

        void refreshState() {
            Logs.trace("bose explicit state refresh requested");
            synchronized (lock) {
                if (syncInFlight) return;
                synced = false;
                syncInFlight = true;
                wantedSync = true;
                startWorker();
                lock.notifyAll();
            }
        }

        void release(String reason) {
            Logs.trace("bose release " + key + " reason=" + reason);
            synchronized (lock) {
                wantedMode = -1;
                wantedSync = false;
            }
            closeSocket();
        }

        private void requeue(int mode, Operation currentOperation, boolean sync) {
            synchronized (lock) {
                if (aclPresent < 0 || probeResult == 0) {
                    syncInFlight = false;
                    return;
                }
                if (mode >= 0 && wantedMode < 0) wantedMode = mode;
                if (currentOperation != null && operation == null) operation = currentOperation;
                if (sync) {
                    syncInFlight = false;
                    synced = false;
                    wantedSync = true;
                }
            }
        }

        private static void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        private void startWorker() {
            // caller holds `lock`
            long now = SystemClock.elapsedRealtime();
            if (workerStarted) {
                // A worker is nominally running. If it has made no progress for a
                // long time it is wedged inside a blocking socket write on a dead
                // RFCOMM link (write() has no timeout), which would leave
                // workerStarted true forever so every later click is silently
                // dropped until Melody is force-stopped. Abandon it: close the
                // socket to unblock the write, bump the generation so the stale
                // worker exits its loop, and start a fresh one.
                if (now - workerProgressAt > WORKER_STUCK_MS) {
                    Logs.trace("bose worker wedged; forcing recovery");
                    closeSocket();
                    workerStarted = false;
                    // fall through to start a new generation
                } else {
                    return;
                }
            }
            workerStarted = true;
            int gen = workerGen.incrementAndGet();
            activeWorkerGen = gen;
            workerProgressAt = now;
            Thread thread = new Thread(new Worker(gen), "bose-bmap-worker");
            thread.setDaemon(true);
            try {
                thread.start();
            } catch (Throwable error) {
                // Thread creation failed (e.g. OOM). Do not leave workerStarted
                // stuck true, or no future click can ever start a worker.
                Logs.trace("bose worker start failed: " + error);
                workerStarted = false;
            }
        }

        /** True while this worker is still the active generation. */
        private boolean isCurrentWorker(int gen) {
            return activeWorkerGen == gen;
        }

        private void markProgress() {
            workerProgressAt = SystemClock.elapsedRealtime();
        }

        /** Runs the worker loop on its own thread, tagged with a generation. */
        private final class Worker implements Runnable {
            private final int gen;

            Worker(int gen) {
                this.gen = gen;
            }

            @Override public void run() {
                workerLoop(gen);
            }
        }

        private void workerLoop(int gen) {
            try {
                while (true) {
                    // A newer generation superseded this worker (wedge recovery);
                    // exit so only one worker drives the socket at a time.
                    if (!isCurrentWorker(gen)) return;
                    int mode;
                    boolean sync;
                    Operation currentOperation;
                    synchronized (lock) {
                        if (wantedMode < 0 && !wantedSync && operation == null) return;
                        mode = wantedMode;
                        wantedMode = -1;
                        sync = wantedSync;
                        wantedSync = false;
                        currentOperation = operation;
                        operation = null;
                    }
                    markProgress();
                    // NOTE: do not gate on raw `aclPresent < 0` here. Bose Music
                    // teardown can fire a spurious ACL_DISCONNECTED while the
                    // classic link stays usable; a permanent negative latch used
                    // to silently swallow every click until the process died.
                    // reachable() applies a short TTL to the negative hint and
                    // falls back to the bonded probe, and it logs the skip.
                    if ((mode >= 0 || currentOperation != null || sync) && !reachable()) {
                        // Log and drop. Do NOT requeue: an unreachable device would
                        // make the worker spin hot on requeued requests. The next
                        // user click retries with a fresh probe.
                        Logs.trace("bose operation skipped: device not reachable");
                        if (sync) {
                            synchronized (lock) {
                                syncInFlight = false;
                            }
                        }
                        continue;
                    }
                    if (!open()) {
                        // The radio refuses a busy RFCOMM link while the other
                        // client holds the channel. Back off instead of looping
                        // back-to-back, which is what kept the link unusable.
                        failedOpens++;
                        if (failedOpens > MAX_FAILED_OPENS) {
                            Logs.trace("bose giving up after " + failedOpens + " failed opens");
                            // Reset so the next user press gets a fresh attempt
                            // instead of being permanently suppressed until a
                            // successful open happens to clear the counter.
                            failedOpens = 0;
                            if (sync) {
                                synchronized (lock) {
                                    syncInFlight = false;
                                }
                            }
                            break;
                        }
                        requeue(mode, currentOperation, sync);
                        sleepQuietly(RETRY_BACKOFF_MS[Math.min(failedOpens - 1,
                                RETRY_BACKOFF_MS.length - 1)]);
                        break;
                    }
                    failedOpens = 0;
                    markProgress();
                    try {
                        if (!isCurrentWorker(gen)) return;
                        if (mode >= 0) setMode(mode);
                        if (!isCurrentWorker(gen)) return;
                        if (currentOperation != null) apply(currentOperation);
                        if (!isCurrentWorker(gen)) return;
                        if (sync) queryState();
                        // Let the last reply settle before the socket is torn
                        // down; closing mid-flight truncated responseBody.
                        sleepQuietly(SETTLE_DELAY_MS);
                    } finally {
                        if (isCurrentWorker(gen)) {
                            synchronized (lock) {
                                syncInFlight = false;
                            }
                            closeSocket();
                        }
                    }
                    markProgress();
                }
            } catch (Throwable error) {
                Logs.e("bose worker crashed", error);
            } finally {
                synchronized (lock) {
                    // Only the active generation owns workerStarted. A stale worker
                    // abandoned by wedge recovery must not clear the flag that now
                    // belongs to its replacement.
                    if (isCurrentWorker(gen)) {
                        workerStarted = false;
                        if (wantedMode >= 0 || wantedSync || operation != null) startWorker();
                    }
                }
            }
        }

        private boolean open() {
            BluetoothSocket existing = socket;
            if (existing != null && existing.isConnected() && !linkDead) return true;
            if (linkDead || existing != null) {
                // Bose Music (or any other client on channel 2) can silently kill
                // our link. The dead BluetoothSocket MUST be fully closed before a
                // fresh connect, otherwise the OS keeps the RFCOMM channel reserved
                // and every later connect() is refused until the process is killed
                // (which is exactly why control only returned after closing Melody).
                Logs.trace("bose cached link stale; forcing reconnect");
                closeSocket();
            }
            // Up to two connect attempts so a single press recovers even if the
            // first connect races with the just-released Bose Music session.
            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0) {
                    Logs.trace("bose reconnect attempt " + (attempt + 1));
                    sleepQuietly(400L);
                }
                if (connectOnce()) return true;
            }
            Logs.trace("bose connect failed after retries");
            return false;
        }

        private boolean connectOnce() {
            int myGen = connectGen.incrementAndGet();
            activeConnectGen = myGen;
            connectorRunning = true;
            Thread connector = new Thread(new Runnable() {
                @Override public void run() {
                    connectSocket(myGen);
                }
            }, "bose-bmap-connect");
            connector.setDaemon(true);
            connector.start();
            long deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS;
            while ((socket == null || !socket.isConnected())
                    && activeConnectGen == myGen && SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(80L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            BluetoothSocket current = socket;
            if (current != null && current.isConnected()) {
                startReader(current);
                Logs.trace("bose BMAP link ready " + key + " channel=" + BoseDeviceConfig.RFCOMM_CHANNEL);
                return true;
            }
            Logs.trace("bose connect watchdog fired");
            // A blocking connect() inside the connector thread can hang far longer
            // than CONNECT_TIMEOUT_MS (e.g. when Bose Music holds the channel at
            // boot). Abandon only our own generation so a stale connector that
            // finally returns cannot clear the flag for a newer attempt; clearing
            // it lets the next press start a brand-new connector instead of waiting
            // on a dead one (the "must close Melody to recover" symptom).
            if (activeConnectGen == myGen) {
                activeConnectGen = -1;
                connectorRunning = false;
            }
            closeSocket();
            return false;
        }

        private void connectSocket(int myGen) {
            Logs.trace("bose connect start channel=" + BoseDeviceConfig.RFCOMM_CHANNEL);
            BluetoothSocket opened = null;
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) throw new IOException("bluetooth disabled");
                adapter.cancelDiscovery();
                BluetoothDevice device = adapter.getRemoteDevice(address);
                opened = (BluetoothSocket) device.getClass()
                        .getMethod("createInsecureRfcommSocket", int.class)
                        .invoke(device, BoseDeviceConfig.RFCOMM_CHANNEL);
                socket = opened;
                linkDead = false;
                opened.connect();
                Thread.sleep(POST_WRITE_DELAY_MS + 100L);
                drainStartup(opened);
                if (myGen == activeConnectGen) connectorRunning = false;
            } catch (Throwable error) {
                Logs.trace("bose BMAP channel " + BoseDeviceConfig.RFCOMM_CHANNEL
                        + " link failed: " + error);
                if (opened != null) {
                    try {
                        opened.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (socket == opened) socket = null;
                if (myGen == activeConnectGen) connectorRunning = false;
            }
        }

        private void drainStartup(BluetoothSocket current) throws IOException {
                InputStream input = current.getInputStream();
                int total = 0;
                while (total < 4096) {
                    int available = input.available();
                    if (available <= 0) break;
                    byte[] stale = new byte[Math.min(available, 4096 - total)];
                    int count = input.read(stale);
                    if (count <= 0) break;
                    total += count;
                    Logs.trace("bose startup bytes discarded=" + count
                            + " data=" + BoseBmap.hex(Arrays.copyOf(stale, count)));
                }
        }

        private void startReader(final BluetoothSocket current) {
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    BoseBmap.Parser parser = new BoseBmap.Parser();
                    byte[] chunk = new byte[1024];
                    try {
                        InputStream input = current.getInputStream();
                        while (current == socket) {
                            int count = input.read(chunk);
                            if (count <= 0) break;
                            parser.feed(chunk, 0, count, Session.this);
                        }
                    } catch (Throwable error) {
                        Logs.trace("bose reader stopped: " + error);
                    } finally {
                        linkDead = true;
                        if (current == socket) socket = null;
                        // The remote side (or a competing client such as Bose Music)
                        // dropped the link. BluetoothSocket does NOT free the OS-side
                        // RFCOMM channel until close() is called, so an unclosed
                        // reference keeps channel 2 reserved and every later connect()
                        // is refused until the process is killed. Release it here so a
                        // button press can re-establish a fresh link without the user
                        // having to close Melody.
                        try {
                            current.close();
                        } catch (Throwable ignored) {
                        }
                        synchronized (replyLock) {
                            replyLock.notifyAll();
                        }
                    }
                }
            }, "bose-bmap-reader");
            reader.setDaemon(true);
            reader.start();
        }

        @Override public void onFrame(BoseBmap.Frame frame) {
            Logs.trace("bose rx " + frame.block + "." + frame.function
                    + " op=" + frame.operator + " " + BoseBmap.hex(frame.payload));
            synchronized (replyLock) {
                if (reply == null && (replyBlock < 0 || frame.matches(replyBlock, replyFunction))) {
                    reply = frame;
                    replyLock.notifyAll();
                }
            }
        }

        private BoseBmap.Frame command(int block, int function, int operator, byte[] payload) {
            synchronized (ioLock) {
                synchronized (replyLock) {
                    reply = null;
                    replyBlock = block;
                    replyFunction = function;
                }
                try {
                    byte[] packet = BoseBmap.packet(block, function, operator, payload);
                    BluetoothSocket current = socket;
                    if (current == null) return null;
                    // The write runs on a disposable thread with a hard timeout:
                    // a native RFCOMM write on a dead link blocks forever and
                    // cannot always be interrupted by close(), so if it exceeds
                    // WRITE_TIMEOUT_MS we abandon that thread (daemon, leaked but
                    // harmless) and release ioLock instead of wedging every
                    // future command until the whole process is killed.
                    final BluetoothSocket writeSocket = current;
                    final byte[] writePacket = packet;
                    final boolean[] writeOk = {false};
                    Thread writer = new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                OutputStream output = writeSocket.getOutputStream();
                                output.write(writePacket);
                                output.flush();
                                writeOk[0] = true;
                            } catch (Throwable error) {
                                Logs.trace("bose write failed: " + error);
                            }
                        }
                    }, "bose-bmap-write");
                    writer.setDaemon(true);
                    writer.start();
                    try {
                        writer.join(WRITE_TIMEOUT_MS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    if (!writeOk[0]) {
                        Logs.trace("bose write timeout; abandoning writer thread");
                        linkDead = true;
                        closeSocket();
                        return null;
                    }
                    Logs.trace("bose tx " + BoseBmap.hex(packet));
                    try {
                        Thread.sleep(POST_WRITE_DELAY_MS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    long deadline = SystemClock.elapsedRealtime() + RESPONSE_TIMEOUT_MS;
                    synchronized (replyLock) {
                        while (reply == null && !linkDead
                                && SystemClock.elapsedRealtime() < deadline) {
                            try {
                                replyLock.wait(150L);
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                return null;
                            }
                        }
                        return reply;
                    }
                } finally {
                    synchronized (replyLock) {
                        reply = null;
                        replyBlock = -1;
                        replyFunction = -1;
                    }
                }
            }
        }

        private void setMode(int mode) {
            if (mode == BoseDeviceConfig.MODE_OFF) {
                // OFF is not a Bose AudioModes preset. It is Quiet with the live
                // ANC bit disabled. Switch to Quiet first but do NOT publish the
                // intermediate "ANC" tile state: the off state is only final once
                // ANC is cleared, so the tile is painted exactly once below.
                if (!switchAudioModeQuiet(BoseDeviceConfig.MODE_QUIET)) return;
                setNoiseCancellation(false);
                queryBattery();
                return;
            }
            if (mode < BoseDeviceConfig.MODE_QUIET || mode > BoseDeviceConfig.MODE_CINEMA) return;
            if (!switchAudioMode(mode)) return;
            // Leaving the off state wrote ANC=0 into the live settings; it has to
            // be switched back on or Quiet would stay silent rather than cancel.
            if (mode == BoseDeviceConfig.MODE_QUIET && !MelodyProviderHook.ancConfirmed()) {
                setNoiseCancellation(true);
            }
            // Ride along on every confirmed mode change so the battery cache is
            // kept fresh without opening extra sessions on panel refreshes.
            queryBattery();
        }

        private void queryBattery() {
            BoseBmap.Frame battery = command(BoseBmap.BLOCK_BATTERY,
                    BoseBmap.FUNC_BATTERY, BoseBmap.OP_GET, null);
            if (battery != null && battery.operator != BoseBmap.OP_ERROR) {
                Logs.trace("bose battery RX payload=" + BoseBmap.hex(battery.payload));
                parseBattery(battery.payload);
            } else {
                Logs.trace("bose battery GET failed: " + describe(battery));
            }
        }

        private boolean switchAudioModeQuiet(int mode) {
            BoseBmap.Frame answer = command(BoseBmap.BLOCK_AUDIO_MODES,
                    BoseBmap.FUNC_CURRENT_MODE, BoseBmap.OP_START,
                    new byte[]{(byte) mode, 0});
            if (answer == null || answer.operator == BoseBmap.OP_ERROR
                    || (answer.operator != BoseBmap.OP_RESULT
                    && answer.operator != BoseBmap.OP_PROCESSING)) {
                Logs.trace("bose mode rejected mode=" + mode + " response="
                        + (answer == null ? "timeout" : answer.operator));
                return false;
            }
            BoseBmap.Frame confirmed = command(BoseBmap.BLOCK_AUDIO_MODES,
                    BoseBmap.FUNC_CURRENT_MODE, BoseBmap.OP_GET, null);
            if (confirmed != null && confirmed.payload.length > 0) {
                int actualMode = confirmed.u8(0);
                Logs.trace("bose mode readback requested=" + mode + " actual=" + actualMode);
                if (actualMode != mode) {
                    Logs.trace("bose mode mismatch requested=" + mode + " actual=" + actualMode);
                    return false;
                }
                // Record the mode but do not repaint the tile yet; the OFF
                // transition finishes with the ANC read-back in setNoiseCancellation.
                MelodyProviderHook.setBoseModeQuiet(actualMode);
                return true;
            }
            Logs.trace("bose mode not confirmed; keeping last confirmed UI state");
            return false;
        }

        private boolean switchAudioMode(int mode) {
            BoseBmap.Frame answer = command(BoseBmap.BLOCK_AUDIO_MODES,
                    BoseBmap.FUNC_CURRENT_MODE, BoseBmap.OP_START,
                    new byte[]{(byte) mode, 0});
            if (answer == null || answer.operator == BoseBmap.OP_ERROR
                    || (answer.operator != BoseBmap.OP_RESULT
                    && answer.operator != BoseBmap.OP_PROCESSING)) {
                Logs.trace("bose mode rejected mode=" + mode + " response="
                        + (answer == null ? "timeout" : answer.operator));
                return false;
            }
            BoseBmap.Frame confirmed = command(BoseBmap.BLOCK_AUDIO_MODES,
                    BoseBmap.FUNC_CURRENT_MODE, BoseBmap.OP_GET, null);
            if (confirmed != null && confirmed.payload.length > 0) {
                int actualMode = confirmed.u8(0);
                Logs.trace("bose mode readback requested=" + mode + " actual=" + actualMode);
                if (actualMode != mode) {
                    Logs.trace("bose mode mismatch requested=" + mode + " actual=" + actualMode);
                    return false;
                }
                MelodyProviderHook.onBoseMode(actualMode);
                return true;
            }
            Logs.trace("bose mode not confirmed; keeping last confirmed UI state");
            return false;
        }

        private void apply(Operation operation) {
            if (!"cnc".equals(operation.name)) return;
            int level = Math.max(0, Math.min(10, operation.values[0]));
            // EDITH exposes writable CNC at [1.5], payload [level, enabled].
            // [31.10] is read-only for this product, so do not attempt SETGET there.
            byte[] payload = new byte[]{(byte) level, 1};
            BoseBmap.Frame answer = command(BoseBmap.BLOCK_SETTINGS,
                    BoseBmap.FUNC_CNC, BoseBmap.OP_SETGET, payload);
            if (answer == null || answer.operator == BoseBmap.OP_ERROR) {
                Logs.trace("bose CNC update rejected response="
                        + (answer == null ? "timeout" : answer.operator + ":" + BoseBmap.hex(answer.payload)));
                return;
            }
            BoseBmap.Frame confirmed = command(BoseBmap.BLOCK_SETTINGS,
                    BoseBmap.FUNC_CNC, BoseBmap.OP_GET, null);
            Logs.trace("bose CNC level=" + level + " set accepted; readback="
                    + (confirmed == null ? "timeout/closed" : BoseBmap.hex(confirmed.payload)));
        }

        private void setNoiseCancellation(boolean enabled) {
            // EDITH's live ANC switch is AudioModes[31.10]. Preserve the other
            // live settings returned by GET and change only the ANC byte.
            BoseBmap.Frame current = command(BoseBmap.BLOCK_AUDIO_MODES, 10,
                    BoseBmap.OP_GET, null);
            if (current == null || current.payload.length < 5) {
                Logs.trace("bose noise-off unavailable: audio settings read failed");
                MelodyProviderHook.refreshNoiseUi();
                return;
            }
            byte[] payload = Arrays.copyOf(current.payload, 5);
            payload[4] = (byte) (enabled ? 1 : 0);
            BoseBmap.Frame answer = command(BoseBmap.BLOCK_AUDIO_MODES, 10,
                    BoseBmap.OP_SETGET, payload);
            if (answer == null || answer.operator == BoseBmap.OP_ERROR) {
                Logs.trace("bose noise-off update rejected response=" + describe(answer));
            } else {
                Logs.trace("bose ANC update enabled=" + enabled + " payload="
                        + BoseBmap.hex(answer.payload));
            }
            // Always read the live [31.10] back so the tile reflects the true ANC
            // bit, whether or not the write went through. This is the single place
            // the OFF/ANC tile state is published for the OFF transition, so the
            // tile never flashes "ANC" before ANC is actually disabled.
            BoseBmap.Frame confirmed = command(BoseBmap.BLOCK_AUDIO_MODES, 10,
                    BoseBmap.OP_GET, null);
            if (confirmed != null && confirmed.payload.length >= 5) {
                Logs.trace("bose ANC readback=" + BoseBmap.hex(confirmed.payload));
                MelodyProviderHook.onBoseAudioSettings(confirmed.payload);
            } else {
                MelodyProviderHook.refreshNoiseUi();
            }
        }

        private void queryState() {
            BoseBmap.Frame mode = command(BoseBmap.BLOCK_AUDIO_MODES,
                    BoseBmap.FUNC_CURRENT_MODE, BoseBmap.OP_GET, null);
            if (mode != null && mode.payload.length > 0) MelodyProviderHook.onBoseMode(mode.u8(0));
            else Logs.trace("bose mode GET failed: " + describe(mode));
            BoseBmap.Frame audioSettings = command(BoseBmap.BLOCK_AUDIO_MODES, 10,
                    BoseBmap.OP_GET, null);
            if (audioSettings != null && audioSettings.operator != BoseBmap.OP_ERROR) {
                MelodyProviderHook.onBoseAudioSettings(audioSettings.payload);
            } else {
                Logs.trace("bose audio settings GET failed: " + describe(audioSettings));
            }
            BoseBmap.Frame battery = command(BoseBmap.BLOCK_BATTERY,
                    BoseBmap.FUNC_BATTERY, BoseBmap.OP_GET, null);
            if (battery != null && battery.operator != BoseBmap.OP_ERROR) {
                Logs.trace("bose battery RX payload=" + BoseBmap.hex(battery.payload));
                parseBattery(battery.payload);
            } else {
                Logs.trace("bose battery GET failed: " + describe(battery));
            }
            synced = battery != null && battery.operator != BoseBmap.OP_ERROR;
        }

        private String describe(BoseBmap.Frame frame) {
            return frame == null ? "timeout/closed"
                    : "op=" + frame.operator + " payload=" + BoseBmap.hex(frame.payload);
        }

        private void parseBattery(byte[] payload) {
            int left = -1;
            int right = -1;
            int caseLevel = -1;
            int aggregate = -1;
            if (payload != null) {
                for (int offset = 0; offset + 3 < payload.length; offset += 4) {
                    int level = payload[offset] & 0xff;
                    int component = payload[offset + 3] & 0xff;
                    if (level > 100) continue;
                    if (component == 1) right = level;
                    else if (component == 2) left = level;
                    else if (component == 3) caseLevel = level;
                    else if (component == 4) aggregate = level;
                }
            }
            if (aggregate < 0 && left >= 0 && right >= 0) aggregate = Math.min(left, right);
            if (left < 0 && right < 0 && caseLevel < 0 && aggregate < 0) {
                Logs.trace("bose battery payload contained no known components: " + BoseBmap.hex(payload));
                return;
            }
            BATTERY.put(key, new int[]{left, right, caseLevel, aggregate});
            MelodyProviderHook.onBoseBattery();
        }

        /**
         * Presence probe using only public Bluetooth API. The hidden
         * {@code BluetoothAdapter.getConnectedDevices(int)} is blocked on
         * Android 17 / ColorOS (NoSuchMethodException via reflection), which
         * made every probe fail and hid the SystemUI tile. A bonded device is
         * treated as reachable; the ACL broadcast remains the authoritative
         * disconnect signal.
         */
        private int probeConnected() {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) return 0;
                BluetoothDevice device = adapter.getRemoteDevice(address);
                if (device != null && device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    return 1;
                }
                return 0;
            } catch (Throwable error) {
                Logs.trace("bose presence probe unavailable: " + error);
            }
            return -1;
        }

        private void closeSocket() {
            BluetoothSocket current = socket;
            socket = null;
            linkDead = false;
            if (current != null) {
                try {
                    current.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
