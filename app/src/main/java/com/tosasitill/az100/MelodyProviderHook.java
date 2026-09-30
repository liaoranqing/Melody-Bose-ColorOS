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
 *          -&gt; name, address                       (active Bose device)
 *   query .../melody_method_noise_reduction, selection=address, args=[mac]
 *          -&gt; name, address, type, supports       (current mode and cycle)
 *   call  melody_method_noise_reduction {name, address, type}   (mode change)
 *   notify(base uri, flags) with 0x200 (mode) or 0x500 (ACL reachability hint)
 * </pre>
 *
 * Melody does not expose the Bose BMAP device as its native earphone, so this
 * module supplies the active-device and mode rows inside Melody's process and
 * sends the operations through the short-lived Bose RFCOMM controller.
 * SystemUI and the Bose app are not injected or modified.
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
 *       the support list to expose four mapped Bose modes.</li>
 * </ul>
 */
public final class MelodyProviderHook {

    private static final String PROVIDER = "com.oplus.melody.provider.EarphoneControlProvider";
    private static final String BASE_URI = "content://" + PROVIDER;
    private static final String PATH_ACTIVE = "/melody_method_active_device";
    private static final String PATH_NOISE = "/melody_method_noise_reduction";
    /** Module-private cached battery data for the Bose detail surface. */
    private static final String PATH_BATTERY = "/melody_method_bose_battery";
    private static final String METHOD_NOISE = "melody_method_noise_reduction";
    private static final String METHOD_WEAR = "melody_method_control_wear";
    private static final String METHOD_BOSE_SETTINGS = "melody_method_bose_settings";

    /** Provider mode ids from the ColorOS noise-reduction tile contract. */
    private static final int NOISE_OFF = 1;
    private static final int NOISE_ANC = 5;
    private static final int NOISE_TRANSPARENT = 2;
    /** Only the three modes requested by the user are exposed to ColorOS. */
    private static final String SUPPORTS = "[1,5,2]";
    private static volatile int boseMode = BoseDeviceConfig.MODE_AWARE;
    private static volatile boolean boseNoiseCancellation = true;

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
    private static volatile Context context;
    private static volatile int noiseMode = NOISE_TRANSPARENT;
    /** Set once the wear state has been announced, cleared when the buds go. */
    /** ACL link state only; BMAP does not expose live wearing sensor data. */
    private static volatile boolean worn;
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
            hookBoseConnection(ctx);
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
            // Always return one stable Bose row. Returning Melody's row when the
            // Bose app refreshes its own state makes SystemUI lose the tile.
            announceBoseReachable();
            closeCursor(result);
            return activeCursor();
        }
        if (PATH_BATTERY.equals(path)) {
            // Cache only. A query must not open SPP; the once-per-connection
            // sync fills the cache.
            return batteryCursor();
        }
        if (PATH_NOISE.equals(path)) {
            if (!"address".equals(selection) || args == null || args.length == 0) return null;
            if (!BoseDeviceConfig.isMac(args[0])) return null;
            announceBoseReachable();
            DirectBoseController.syncOnce(context, BoseDeviceConfig.MAC);
            return boseNoiseCursor();
        }
        return null;
    }

    /** A click in the panel arrives as a call on the provider. */
    private static boolean onCall(String method, Bundle extras) {
        if (extras == null) return false;
        if (METHOD_BOSE_SETTINGS.equals(method)) {
            applyBoseSettings(extras);
            return true;
        }
        String address = extras.getString("address");
        if (BoseDeviceConfig.isMac(address)) {
            if (METHOD_NOISE.equals(method)) {
                applyBose(extras.getInt("type", NOISE_OFF));
                return true;
            }
            if (METHOD_WEAR.equals(method)) return true;
        }
        return false;
    }

    static void configureBose(Context ctx, String action, int first, int second, int third) {
        if (ctx == null) return;
        Bundle extras = new Bundle();
        extras.putString("action", action);
        extras.putInt("first", first);
        extras.putInt("second", second);
        extras.putInt("third", third);
        try {
            ctx.getContentResolver().call(Uri.parse(BASE_URI), METHOD_BOSE_SETTINGS, null, extras);
        } catch (Throwable error) {
            Logs.e("bose settings call failed", error);
        }
    }

    private static void applyBoseSettings(Bundle extras) {
        try {
            String action = extras.getString("action", "");
            if ("cnc".equals(action)) {
                DirectBoseController.setCnc(context,
                        Math.max(0, Math.min(10, extras.getInt("first"))));
            }
        } catch (Throwable error) {
            Logs.e("bose control request failed", error);
        }
    }

    private static int clampEq(int value) {
        return Math.max(-10, Math.min(10, value));
    }

    private static Cursor activeCursor() {
        MatrixCursor cursor = new MatrixCursor(new String[]{"name", "address"});
        cursor.addRow(new Object[]{BoseDeviceConfig.NAME, BoseDeviceConfig.MAC});
        return cursor;
    }

    private static Cursor boseNoiseCursor() {
        MatrixCursor cursor = new MatrixCursor(new String[]{"name", "address", "type", "supports"});
        cursor.addRow(new Object[]{BoseDeviceConfig.NAME, BoseDeviceConfig.MAC,
                Integer.valueOf(boseMelodyMode()), SUPPORTS});
        return cursor;
    }

    private static int boseMelodyMode() {
        if (boseMode == BoseDeviceConfig.MODE_AWARE) return NOISE_TRANSPARENT;
        if (boseMode == BoseDeviceConfig.MODE_QUIET) {
            return boseNoiseCancellation ? NOISE_ANC : NOISE_OFF;
        }
        return NOISE_OFF;
    }

    static int boseModeCache() {
        return boseMode;
    }

    /** Cached battery as a cursor: left, right, case and aggregate. */
    private static Cursor batteryCursor() {
        int[] values = DirectBoseController.cachedBattery(BoseDeviceConfig.MAC);
        MatrixCursor cursor = new MatrixCursor(
                new String[]{"name", "address", "left", "right", "case", "aggregate"});
        cursor.addRow(new Object[]{BoseDeviceConfig.NAME, BoseDeviceConfig.MAC,
                Integer.valueOf(values == null ? -1 : values[0]),
                Integer.valueOf(values == null ? -1 : values[1]),
                Integer.valueOf(values == null ? -1 : values[2]),
                Integer.valueOf(values == null || values.length < 4 ? -1 : values[3])});
        return cursor;
    }

    /** Battery levels arrived on the Bose BMAP link; refresh Melody's page. */
    static void onBoseBattery() {
        if (!installed) return;
        notifyChange(FLAG_BATTERY);
        notifyBatteryProvider();
    }

    static void onBoseMode(int mode) {
        if (!installed || mode < BoseDeviceConfig.MODE_QUIET || mode > BoseDeviceConfig.MODE_CINEMA) return;
        boseMode = mode;
        noiseMode = boseMelodyMode();
        notifyChange(FLAG_NOISE);
    }

    static void onBoseAudioSettings(byte[] payload) {
        if (!installed || payload == null || payload.length < 5) return;
        boseNoiseCancellation = payload[4] != 0;
        noiseMode = boseMelodyMode();
        notifyChange(FLAG_NOISE);
    }

    static void setBoseEq(int bass, int mid, int treble) {
        DirectBoseController.setEq(context, bass, mid, treble);
    }

    static void setBoseAutoPause(boolean enabled) {
        DirectBoseController.setAutoPause(context, enabled);
    }

    static void setBoseVoicePrompts(boolean enabled) {
        DirectBoseController.setVoicePrompts(context, enabled);
    }

    static void setBoseCnc(int level) {
        DirectBoseController.setCnc(context, level);
    }


    /** Hook the Melody battery provider to expose cached Bose earbud levels. */
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
        int[] values = DirectBoseController.cachedBattery(BoseDeviceConfig.MAC);
        if (values == null || values.length < 2 || (values[0] < 0 && values[1] < 0)) return null;
        MatrixCursor cursor = new MatrixCursor(
                new String[]{"macAddress", "headsetLeftBattery", "headsetRightBattery"});
        cursor.addRow(new Object[]{BoseDeviceConfig.MAC,
                Integer.valueOf(Math.max(values[0], 0)), Integer.valueOf(Math.max(values[1], 0))});
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

    private static void closeCursor(Cursor cursor) {
        if (cursor == null) return;
        try {
            cursor.close();
        } catch (Throwable error) {
            Logs.d("melody cursor close failed", error);
        }
    }

    /* ------------------------------------------------------------------ */
    /* state                                                               */
    /* ------------------------------------------------------------------ */

    /** One read-back per ACL session; provider queries must not open RFCOMM repeatedly. */
    private static void syncIfNeeded() {
        Context ctx = context;
        if (ctx == null) return;
        DirectBoseController.syncOnce(ctx, BoseDeviceConfig.MAC);
    }

    /** A mode the user picked in the panel: local state, then the headset. */
    private static void applyBose(int mode) {
        int target;
        if (mode == NOISE_ANC) target = BoseDeviceConfig.MODE_QUIET;
        else if (mode == NOISE_TRANSPARENT) target = BoseDeviceConfig.MODE_AWARE;
        else target = BoseDeviceConfig.MODE_OFF;
        boseMode = target == BoseDeviceConfig.MODE_OFF
                ? BoseDeviceConfig.MODE_QUIET : target;
        boseNoiseCancellation = target != BoseDeviceConfig.MODE_OFF;
        noiseMode = mode;
        Logs.trace("bose melody click mode=" + mode + " target=" + target);
        Context ctx = context;
        if (ctx != null) DirectBoseController.requestMode(ctx, BoseDeviceConfig.MAC, target);
        else syncIfNeeded();
        notifyChange(FLAG_NOISE);
    }

    private static boolean announceBoseReachable() {
        Context ctx = context;
        boolean reachable = ctx != null
                && DirectBoseController.reachable(ctx, BoseDeviceConfig.MAC);
        // Do not clear the tile because a synchronous presence probe can briefly
        // fail while the Bose app is open. An explicit ACL_DISCONNECTED broadcast
        // is the authoritative path for clearing the state.
        if (reachable) announceBoseWear(true);
        return reachable;
    }

    private static void announceBoseWear(boolean connected) {
        if (connected == worn) return;
        worn = connected;
        Logs.trace("bose connection hint=" + connected + " (not a wear sensor)");
        notifyChange(connected ? FLAG_WEAR | 0x01 : FLAG_WEAR);
    }

    private static void hookBoseConnection(Context ctx) {
        if (ctx == null) return;
        try {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context receiver, Intent intent) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device == null || !BoseDeviceConfig.isMac(device.getAddress())) return;
                    boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction());
                    DirectBoseController.setPresent(connected);
                    if (connected) {
                        announceBoseWear(true);
                        DirectBoseController.syncOnce(receiver, BoseDeviceConfig.MAC);
                    } else {
                        DirectBoseController.disconnect(receiver, BoseDeviceConfig.MAC, "bose acl gone");
                        announceBoseWear(false);
                    }
                }
            }, filter);
        } catch (Throwable t) {
            Logs.e("bose ACL receiver failed", t);
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

    /* Bose-to-ColorOS mode mapping is handled by boseMelodyMode(). */
}
