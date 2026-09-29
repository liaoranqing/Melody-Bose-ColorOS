package com.tosasitill.az100;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * The 降噪 tile of the ColorOS volume panel lives in SystemUI but every piece of
 * its data comes from Melody, through
 * {@code content://com.oplus.melody.provider.EarphoneControlProvider}:
 *
 * <pre>
 *   query .../melody_method_active_device
 *          -&gt; name, address                       (which earphone is shown)
 *   query .../melody_method_noise_reduction, selection=address, args=[mac]
 *          -&gt; name, address, type, supports       (type = current mode,
 *                                                  supports = JSON mode list)
 *   call  melody_method_noise_reduction {name, address, type}   (the click)
 *   notify(base uri, flags) with 0x200 (mode changed) or 0x500 (wear changed,
 *          low byte carries 0x10 left / 0x1 right)
 * </pre>
 *
 * Melody knows nothing about the AZ100, so all of those are misses: no active
 * device, no support list, no wear state -- and the tile hides itself (the
 * "invisible control" the user started with) or refuses every click.  The
 * answers are therefore produced here, inside Melody's own process, and the
 * headset is driven by the same Airoha session the module uses elsewhere
 * ({@link DirectAirohaController}).
 *
 * That is also why nothing of this runs in SystemUI: during a panel render the
 * module only reacts to binder calls that arrive at this provider, and the
 * headset work happens on the Airoha worker thread owned by this process.
 *
 * The tile's rules were read from the stock code, not guessed:
 * <ul>
 *   <li>{@code NoiseReductionDetailTile.isAvailable()} needs more than one entry
 *       in support["noise"] and a wear state != 0;</li>
 *   <li>{@code EarphoneController.getType()} parses the {@code supports} column
 *       as a JSON list of ints and returns the {@code type} column as the mode;</li>
 *   <li>{@code handleUpdateState()} sets the tile state to 0 only while there is
 *       no active device, which is what greyed it out;</li>
 *   <li>the cycle is fixed in the tile ({@code [1, 5, 10, 2]}) and filtered by
 *       the support list, so leaving 10 out removes 自适应 from the panel.</li>
 * </ul>
 */
public final class MelodyProviderHook {

    private static final String PROVIDER = "com.oplus.melody.provider.EarphoneControlProvider";
    private static final String BASE_URI = "content://" + PROVIDER;
    private static final String PATH_ACTIVE = "/melody_method_active_device";
    private static final String PATH_NOISE = "/melody_method_noise_reduction";
    /**
     * Module-private read of the cached battery.  SystemUI never asks for it;
     * the Melody-side page does, because the Airoha link lives in this process
     * while that page runs in Melody's {@code :fg} process.
     */
    private static final String PATH_BATTERY = "/melody_method_az100_battery";
    private static final String METHOD_NOISE = "melody_method_noise_reduction";
    private static final String METHOD_WEAR = "melody_method_control_wear";

    /** Melody's noise ids; see the qs_noise_reduction_* strings. */
    private static final int NOISE_OFF = 1;
    private static final int NOISE_ANC = 5;
    private static final int NOISE_TRANSPARENT = 2;
    /** Modes the tile may cycle through -- 自适应 (10) is left out on purpose. */
    private static final String SUPPORTS = "[1,5,2]";

    /** Notification flags: the high byte selects the callback inside SystemUI. */
    private static final int FLAG_NOISE = 0x200;
    private static final int FLAG_WEAR = 0x500;
    /**
     * Not a SystemUI flag: its observer only looks at the high byte and knows
     * 0x100 / 0x200 / 0x500, so 0x600 is ignored there.  The Melody-side
     * control page repaints on any change of this authority, which is what this
     * flag is for: fresh battery levels have arrived.
     */
    private static final int FLAG_BATTERY = 0x600;
    /** Wear bits of the low byte: 0x10 left, 0x1 right. */
    private static final int WEAR_BITS = 0x11;
    /**
     * After a wear transition, repeat the notification a couple of times.
     * SystemUI may register its observer just after the transition. Repeating
     * for the whole connection wakes SystemUI on every provider query, so the
     * window is short.
     */
    private static final long WEAR_HEARTBEAT_MS = 10_000L;
    private static final long WEAR_HEARTBEAT_WINDOW_MS = 30_000L;

    private static volatile Context context;
    private static volatile int noiseMode = NOISE_OFF;
    /** Set once the wear state has been announced, cleared when the buds go. */
    private static volatile boolean worn;
    private static volatile long lastWearAt;
    /** When the wear flag last actually changed. Heartbeats stop after the window. */
    private static volatile long wearChangedAt;
    private static volatile boolean installed;

    private MelodyProviderHook() {
    }

    public static void install(final ClassLoader loader, Context ctx) {
        synchronized (MelodyProviderHook.class) {
            if (installed) return;
            installed = true;
        }
        context = ctx;
        if (ctx != null) Logs.traceToFile(ctx.getExternalFilesDir(null));
        try {
            Class<?> provider = XposedHelpers.findClass(PROVIDER, loader);
            XposedHelpers.findAndHookMethod(provider, "query",
                    Uri.class, String[].class, String.class, String[].class, String.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Cursor patched = patch((Uri) param.args[0], (String) param.args[2],
                                    (String[]) param.args[3], (Cursor) param.getResult());
                            if (patched != null) param.setResult(patched);
                        }
                    });
            XposedHelpers.findAndHookMethod(provider, "call",
                    String.class, String.class, Bundle.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (onCall((String) param.args[0], (Bundle) param.args[2])) {
                                param.setResult(new Bundle());
                            }
                        }
                    });
            hookMelodyBluetoothReceiver(loader);
            watchAz100Connection(ctx);
            hookBatteryProvider(loader);
            Logs.trace("melody hooks installed");
            Logs.i("melody provider hooks installed");
        } catch (Throwable t) {
            Logs.e("melody provider hook failed", t);
            Logs.trace("melody hook failed: " + t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* provider contract                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * Answer the two queries the tile depends on.  A non-null return replaces
     * Melody's own result; null leaves it alone, which keeps a real Melody
     * earphone working exactly as before.
     */
    private static Cursor patch(Uri uri, String selection, String[] args, Cursor result) {
        String path = uri == null ? null : uri.getPath();
        if (path == null) return null;
        if (PATH_ACTIVE.equals(path)) {
            if (hasRow(result)) return null;           // Melody knows a real earphone
            // A non-null row is what makes SystemUI build an ActiveDevice at
            // all; whether the controls are really shown is decided by the
            // wear flag alone (see announceWear).  Answering unconditionally
            // also covers a stale "explicitly gone" ACL hint left over from a
            // disconnect that happened before this process started.
            announceReachable();
            return activeCursor();
        }
        if (PATH_BATTERY.equals(path)) {
            // Cache only. A query must not open SPP; the once-per-connection
            // sync fills the cache.
            return batteryCursor();
        }
        if (PATH_NOISE.equals(path)) {
            if (!"address".equals(selection) || args == null || args.length == 0) return null;
            if (!Az100Hook.isAz100Mac(args[0])) return null;
            // 2+ entries in "noise" is the second isAvailable() condition;
            // the panel only ever asks with the address it got from us.
            announceReachable();
            // SystemUI hits this from media-route callbacks, not only when the
            // panel is open. syncOnce latches, so this cannot redial.
            syncIfNeeded();
            return noiseCursor();
        }
        return null;
    }

    /** A click in the panel arrives as a call on the provider. */
    private static boolean onCall(String method, Bundle extras) {
        if (extras == null) return false;
        String address = extras.getString("address");
        if (!Az100Hook.isAz100Mac(address)) return false;
        if (METHOD_NOISE.equals(method)) {
            apply(extras.getInt("type", NOISE_OFF));
            return true;
        }
        if (METHOD_WEAR.equals(method)) {
            // SystemUI asks whether wear detection should be watched; the AZ100
            // reports its own state, which is pushed through the notifications.
            return true;
        }
        return false;
    }

    private static Cursor activeCursor() {
        MatrixCursor cursor = new MatrixCursor(new String[]{"name", "address"});
        cursor.addRow(new Object[]{Az100Hook.AZ100_NAME, Az100Hook.AZ100_MAC});
        return cursor;
    }

    private static Cursor noiseCursor() {
        MatrixCursor cursor =
                new MatrixCursor(new String[]{"name", "address", "type", "supports"});
        cursor.addRow(new Object[]{Az100Hook.AZ100_NAME, Az100Hook.AZ100_MAC,
                Integer.valueOf(noiseMode), SUPPORTS});
        return cursor;
    }

    /** Cached battery as a cursor: left, right, case; -1 = not reported yet. */
    private static Cursor batteryCursor() {
        int[] values = DirectAirohaController.cachedBattery(Az100Hook.AZ100_MAC);
        MatrixCursor cursor = new MatrixCursor(
                new String[]{"name", "address", "left", "right", "case"});
        cursor.addRow(new Object[]{Az100Hook.AZ100_NAME, Az100Hook.AZ100_MAC,
                Integer.valueOf(values == null ? -1 : values[0]),
                Integer.valueOf(values == null ? -1 : values[1]),
                Integer.valueOf(values == null ? -1 : values[2])});
        return cursor;
    }

    /**
     * Battery levels arrived on this process's Airoha link: let the Melody-side
     * control page reload them (it reads the module-private battery query in
     * {@link #batteryCursor()}).
     */
    static void onHeadsetBattery() {
        if (!installed) return;
        notifyChange(FLAG_BATTERY);
        notifyBatteryProvider();
    }


    /**
     * Settings reads {@code content://com.oplus.melody.BatteryProvider}.
     * Its columns are only mac, left and right; the case has no column there.
     * A query with no selection lists every known headset, so the AZ100 row is
     * appended.  A query that already names this mac replaces that row.
     */
    private static void hookBatteryProvider(ClassLoader loader) {
        try {
            Class<?> provider = XposedHelpers.findClass(
                    "com.oplus.melody.provider.BatteryProvider", loader);
            XposedHelpers.findAndHookMethod(provider, "query",
                    Uri.class, String[].class, String.class, String[].class, String.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Cursor patched = patchBattery((String[]) param.args[3],
                                    (Cursor) param.getResult());
                            if (patched != null) param.setResult(patched);
                        }
                    });
            Logs.trace("battery provider hooked");
        } catch (Throwable t) {
            Logs.e("battery provider hook failed", t);
        }
    }

    private static Cursor patchBattery(String[] args, Cursor result) {
        int[] values = DirectAirohaController.cachedBattery(Az100Hook.AZ100_MAC);
        if (values == null) return null;
        int left = values.length > 0 ? values[0] : -1;
        int right = values.length > 1 ? values[1] : -1;
        if (left < 0 && right < 0) return null;
        boolean named = false;
        if (args != null) {
            for (String arg : args) {
                if (Az100Hook.isAz100Mac(arg)) {
                    named = true;
                    break;
                }
            }
        }
        if (named) {
            MatrixCursor cursor = new MatrixCursor(
                    new String[]{"macAddress", "headsetLeftBattery", "headsetRightBattery"});
            cursor.addRow(new Object[]{Az100Hook.AZ100_MAC,
                    Integer.valueOf(Math.max(left, 0)), Integer.valueOf(Math.max(right, 0))});
            return cursor;
        }
        if (result == null) return null;
        MatrixCursor cursor = new MatrixCursor(
                new String[]{"macAddress", "headsetLeftBattery", "headsetRightBattery"});
        int mac = result.getColumnIndex("macAddress");
        int leftCol = result.getColumnIndex("headsetLeftBattery");
        int rightCol = result.getColumnIndex("headsetRightBattery");
        if (mac >= 0 && result.moveToFirst()) {
            do {
                cursor.addRow(new Object[]{result.getString(mac),
                        Integer.valueOf(leftCol < 0 ? 0 : result.getInt(leftCol)),
                        Integer.valueOf(rightCol < 0 ? 0 : result.getInt(rightCol))});
            } while (result.moveToNext());
        }
        cursor.addRow(new Object[]{Az100Hook.AZ100_MAC,
                Integer.valueOf(Math.max(left, 0)), Integer.valueOf(Math.max(right, 0))});
        return cursor;
    }

    /** Settings observes the provider URI; the flag value is not interpreted. */
    private static void notifyBatteryProvider() {
        Context ctx = context;
        if (ctx == null) return;
        try {
            ctx.getContentResolver().notifyChange(
                    Uri.parse("content://com.oplus.melody.BatteryProvider"), null);
        } catch (Throwable t) {
            Logs.d("battery notify failed", t);
        }
    }

    private static boolean hasRow(Cursor cursor) {
        return cursor != null && cursor.getCount() > 0;
    }

    /* ------------------------------------------------------------------ */
    /* state                                                               */
    /* ------------------------------------------------------------------ */

    /**
     * One read-back per connection, so a panel opened after the buds were
     * switched on the headset itself still shows the current mode. Further
     * queries return {@link #noiseMode} and do not page the headset.
     */
    private static void syncIfNeeded() {
        Context ctx = context;
        if (ctx == null) return;
        DirectAirohaController.syncOnce(ctx, Az100Hook.AZ100_MAC);
    }

    /** A mode the user picked in the panel: local state, then the headset. */
    private static void apply(int mode) {
        noiseMode = mode;
        Logs.trace("melody click mode=" + mode);
        Context ctx = context;
        if (ctx != null) {
            DirectAirohaController.request(ctx, Az100Hook.AZ100_MAC, airohaStatus(mode));
        }
        // Let SystemUI re-read the label right away; if the headset refuses the
        // command its own report arrives later and corrects this again.  The
        // same notification is what repaints the Melody-side control page.
        notifyChange(FLAG_NOISE);
    }

    /**
     * Mode the headset reported (see {@link Az100Hook#onAirohaMode}).  Runs in
     * whichever process owns the link, hence the {@code installed} guard.
     */
    static void onHeadsetMode(int airohaStatus) {
        if (!installed) return;
        int mode = melodyMode(airohaStatus);
        if (mode == noiseMode) return;
        noiseMode = mode;
        notifyChange(FLAG_NOISE);
    }

    /**
     * Query-time wear refresh: every provider interaction is a chance to
     * re-announce the wear flag, which is the only thing that actually gates
     * the tile ({@code isAvailable()} checks it as {@code earState != 0}).
     * The ACL broadcasts keep this cheap; the one-time profile probe of
     * {@link DirectAirohaController} covers the case where Melody's process
     * starts after the buds are already connected.
     */
    private static boolean announceReachable() {
        Context ctx = context;
        boolean reachable = ctx != null
                && DirectAirohaController.reachable(ctx, Az100Hook.AZ100_MAC);
        announceWear(reachable);
        return reachable;
    }

    /**
     * Push the wear flag SystemUI's {@code earState} is built from. A short
     * repeat covers an observer that registered just after the transition.
     * It does not keep firing for the rest of the connection.
     */
    private static void announceWear(boolean wornNow) {
        long now = SystemClock.elapsedRealtime();
        boolean changed = wornNow != worn;
        if (!changed) {
            // A few repeats cover a late observer. Then stop, even if SystemUI
            // keeps querying the provider.
            if (now - wearChangedAt > WEAR_HEARTBEAT_WINDOW_MS) return;
            if (now - lastWearAt < WEAR_HEARTBEAT_MS) return;
        } else {
            worn = wornNow;
            wearChangedAt = now;
            Logs.trace("wear announce worn=" + wornNow);
        }
        lastWearAt = now;
        notifyChange(wornNow ? FLAG_WEAR | WEAR_BITS : FLAG_WEAR);
    }

    /**
     * Melody's own manifest receiver is started by the system for an incoming
     * ACL connect even when the process was dead, so hooking it is the only
     * event that survives the processes being killed between two sessions.
     */
    private static void hookMelodyBluetoothReceiver(final ClassLoader loader) {
        try {
            Class<?> receiver =
                    XposedHelpers.findClass("com.oplus.melody.app.bluetooth.BluetoothBroadcastReceiver",
                            loader);
            XposedHelpers.findAndHookMethod(receiver, "onReceive",
                    Context.class, Intent.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            onMelodyBroadcast((Intent) param.args[1]);
                        }
                    });
        } catch (Throwable t) {
            Logs.d("melody bt receiver hook failed", t);
        }
    }

    private static void onMelodyBroadcast(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || !Az100Hook.isAz100Mac(device.getAddress())) return;
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            DirectAirohaController.setPresent(true);
            announceWear(true);
            syncIfNeeded();
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            DirectAirohaController.setPresent(false);
            announceWear(false);
        }
    }

    /** Wear state, straight from the ACL broadcast: no polling, no wakeups. */
    private static void watchAz100Connection(Context ctx) {
        if (ctx == null) return;
        try {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context receiver, Intent intent) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device == null || !Az100Hook.isAz100Mac(device.getAddress())) return;
                    if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
                        DirectAirohaController.setPresent(true);
                        if (!worn) announceWear(true);
                        syncIfNeeded();
                    } else {
                        // Headset is gone: drop the SPP socket and hide the tile.
                        DirectAirohaController.setPresent(false);
                                    DirectAirohaController.disconnect(receiver, Az100Hook.AZ100_MAC,
                                "acl gone");
                        if (worn) announceWear(false);
                    }
                }
            }, filter);
        } catch (Throwable t) {
            Logs.e("melody ACL receiver failed", t);
        }
    }

    private static void notifyChange(int flags) {
        Context ctx = context;
        if (ctx == null) return;
        try {
            ctx.getContentResolver().notifyChange(Uri.parse(BASE_URI), null, flags);
        } catch (Throwable t) {
            Logs.d("melody notify failed", t);
        }
    }

    /* ------------------------------------------------------------------ */
    /* mode mapping                                                        */
    /* ------------------------------------------------------------------ */

    /** Melody mode -> Airoha status (2 ANC, 3 ambient, 1 off). */
    private static int airohaStatus(int mode) {
        if (mode == NOISE_ANC) return 2;
        if (mode == NOISE_TRANSPARENT) return 3;
        return 1;
    }

    /**
     * Airoha status -> Melody mode.  自适应 (4) is shown as 降噪: it is the same
     * noise cancelling with a level that follows the surroundings, which is how
     * the official app displays it, and the panel no longer offers it.
     */
    private static int melodyMode(int airohaStatus) {
        if (airohaStatus == 2 || airohaStatus == 4) return NOISE_ANC;
        if (airohaStatus == 3) return NOISE_TRANSPARENT;
        return NOISE_OFF;
    }
}
