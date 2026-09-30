package com.tosasitill.az100;

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

/** Short-lived Bose BMAP RFCOMM controller owned by the Melody process. */
final class DirectBoseController {
    private static final long CONNECT_TIMEOUT_MS = 8_000L;
    private static final long RESPONSE_TIMEOUT_MS = 3_000L;
    private static final long PRESENCE_TTL_MS = 10_000L;
    private static final long POST_WRITE_DELAY_MS = 200L;
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, int[]> BATTERY = new ConcurrentHashMap<>();
    private static volatile int aclPresent;

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

    private static final class Session implements Runnable, BoseBmap.Sink {
        private final String address;
        private final String key;
        private volatile Context context;
        private final Object lock = new Object();
        private final Object replyLock = new Object();
        private int wantedMode = -1;
        private boolean wantedSync;
        private Operation operation;
        private boolean workerStarted;
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

        Session(Context context, String address) {
            this.context = context;
            this.address = address;
            this.key = BoseDeviceConfig.normalize(address);
        }

        boolean reachable() {
            if (aclPresent < 0) return false;
            if (aclPresent > 0) return true;
            long now = SystemClock.elapsedRealtime();
            if (now - probeAt < PRESENCE_TTL_MS) return probeResult != 0;
            probeAt = now;
            probeResult = probeConnected();
            return probeResult != 0;
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

        private void startWorker() {
            if (workerStarted) return;
            workerStarted = true;
            Thread thread = new Thread(this, "bose-bmap-worker");
            thread.setDaemon(true);
            thread.start();
        }

        @Override public void run() {
            try {
                while (true) {
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
                    if (mode >= 0 && aclPresent < 0) continue;
                    if (currentOperation != null && aclPresent < 0) continue;
                    if ((mode >= 0 || currentOperation != null || sync) && !reachable()) {
                        Logs.trace("bose operation skipped: device not reachable");
                        if (sync) {
                            synchronized (lock) {
                                syncInFlight = false;
                            }
                        }
                        continue;
                    }
                    if (!open()) {
                        requeue(mode, currentOperation, sync);
                        break;
                    }
                    try {
                        if (mode >= 0) setMode(mode);
                        if (currentOperation != null) apply(currentOperation);
                        if (sync) queryState();
                    } finally {
                        synchronized (lock) {
                            syncInFlight = false;
                        }
                        closeSocket();
                    }
                }
            } catch (Throwable error) {
                Logs.e("bose worker crashed", error);
            } finally {
                synchronized (lock) {
                    workerStarted = false;
                    if (wantedMode >= 0 || wantedSync || operation != null) startWorker();
                }
            }
        }

        private boolean open() {
            BluetoothSocket existing = socket;
            if (existing != null && existing.isConnected()) return true;
            if (!connectorRunning) {
                connectorRunning = true;
                Thread connector = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            connectSocket();
                        } finally {
                            connectorRunning = false;
                        }
                    }
                }, "bose-bmap-connect");
                connector.setDaemon(true);
                connector.start();
            }
            long deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS;
            while ((socket == null || !socket.isConnected())
                    && connectorRunning && SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(80L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            BluetoothSocket current = socket;
            if (current == null || !current.isConnected()) {
                Logs.trace("bose connect watchdog fired");
                closeSocket();
                return false;
            }
            startReader(current);
            Logs.trace("bose BMAP link ready " + key + " channel=" + BoseDeviceConfig.RFCOMM_CHANNEL);
            return true;
        }

        private void connectSocket() {
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
                Thread.sleep(POST_WRITE_DELAY_MS + 300L);
                drainStartup(opened);
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
                    try {
                        OutputStream output = current.getOutputStream();
                        output.write(packet);
                        output.flush();
                        Logs.trace("bose tx " + BoseBmap.hex(packet));
                    } catch (Throwable error) {
                        Logs.trace("bose write failed: " + error);
                        return null;
                    }
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
                // ANC bit disabled; switch to Quiet first, then write ANC=0.
                if (!switchAudioMode(BoseDeviceConfig.MODE_QUIET)) return;
                setNoiseCancellation(false);
                return;
            }
            if (mode < BoseDeviceConfig.MODE_QUIET || mode > BoseDeviceConfig.MODE_CINEMA) return;
            switchAudioMode(mode);
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
            byte[] payload = current != null && current.payload.length >= 5
                    ? Arrays.copyOf(current.payload, 5)
                    : new byte[]{5, 0, 0, 0, 0};
            payload[4] = (byte) (enabled ? 1 : 0);
            BoseBmap.Frame answer = command(BoseBmap.BLOCK_AUDIO_MODES, 10,
                    BoseBmap.OP_SETGET, payload);
            if (answer == null || answer.operator == BoseBmap.OP_ERROR) {
                Logs.trace("bose ANC update rejected response=" + describe(answer));
                return;
            }
            Logs.trace("bose ANC update enabled=" + enabled + " payload="
                    + BoseBmap.hex(answer.payload));
            if (answer.payload.length >= 5) {
                MelodyProviderHook.onBoseAudioSettings(answer.payload);
            } else {
                BoseBmap.Frame confirmed = command(BoseBmap.BLOCK_AUDIO_MODES, 10,
                        BoseBmap.OP_GET, null);
                if (confirmed != null && confirmed.payload.length >= 5) {
                    Logs.trace("bose ANC readback=" + BoseBmap.hex(confirmed.payload));
                    MelodyProviderHook.onBoseAudioSettings(confirmed.payload);
                } else {
                    Logs.trace("bose ANC update returned no valid readback");
                }
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
