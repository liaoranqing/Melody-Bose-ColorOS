package com.tosasitill.bosemelody;

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
    /** Only the three modes the user wants: off, noise cancelling, transparent. */
    private static final String SUPPORTS = "[1,5,2]";
    private static volatile int boseMode = BoseDeviceConfig.MODE_AWARE;
    private static volatile boolean boseNoiseCancellation = true;
    private static volatile int confirmedNoiseMode = NOISE_TRANSPARENT;
    private static volatile boolean confirmedAncEnabled = true;

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

    static void requestBoseMode(Context ctx, int mode) {
        if (ctx == null) return;
        int providerMode = mode == NOISE_ANC ? NOISE_ANC
                : mode == NOISE_TRANSPARENT ? NOISE_TRANSPARENT : NOISE_OFF;
        DirectBoseController.requestMode(ctx, BoseDeviceConfig.MAC,
                providerMode == NOISE_OFF ? BoseDeviceConfig.MODE_OFF
                        : providerMode == NOISE_ANC ? BoseDeviceConfig.MODE_QUIET
                        : BoseDeviceConfig.MODE_AWARE);
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
            logCursorColumns("active", result);
            // Always return one stable Bose row. Returning Melody's row when the
            // Bose app refreshes its own state makes SystemUI lose the tile.
            announceBoseReachable();
            closeCursor(result);
            return activeCursor();
        }
        if (PATH_BATTERY.equals(path)) {
            // Cache only. A query must not open a competing BMAP session;
            // an explicit refresh from the module settings page fills the cache.
            return batteryCursor();
        }
        if (PATH_NOISE.equals(path)) {
            // ColorOS 17.6.3 first queries this URI without selection/args.
            // The stock provider then returns null because its activeEarphone is
            // null, which makes SystemUI hide the noise-control button. This
            // module owns the Bose row, so do not require the stock selection
            // shape; return the Bose row for both the initial and address-scoped
            // query forms.
            if (args != null && args.length > 0 && args[0] != null
                    && !BoseDeviceConfig.isMac(args[0])) return null;
            logCursorColumns("noise", result);
            announceBoseReachable();
            // Do not open a competing RFCOMM/BMAP session during every panel refresh.
            // Bose Music uses the same channel; state is refreshed only by an explicit
            // user action or immediately after a native mode click.
            closeCursor(result);
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
        return confirmedNoiseMode;
    }

    static int boseModeCache() {
        return boseMode;
    }

    /** True when the last [31.10] read-back reported ANC enabled. */
    static boolean ancConfirmed() {
        return confirmedAncEnabled;
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
        noiseMode = resolveConfirmedMode(true);
        notifyChange(FLAG_NOISE);
    }

    /**
     * Record the confirmed Bose mode without repainting the tile. Used while an
     * OFF transition is mid-flight, so the tile does not flash "ANC" before the
     * ANC bit is actually disabled. The tile is painted later by
     * {@link #refreshNoiseUi()} once the [31.10] read-back is in.
     */
    static void setBoseModeQuiet(int mode) {
        if (!installed || mode < BoseDeviceConfig.MODE_QUIET || mode > BoseDeviceConfig.MODE_CINEMA) return;
        boseMode = mode;
    }

    /** Repaint the tile from the currently known Bose mode and ANC bit. */
    static void refreshNoiseUi() {
        if (!installed) return;
        noiseMode = resolveConfirmedMode(true);
        notifyChange(FLAG_NOISE);
    }

    /**
     * Silent switching always reports Quiet even when ANC is off, so Quiet with
     * ANC=0 is the closest supported representation of the user's "off": no
     * transparency and no noise cancelling.
     */
    private static int resolveConfirmedMode(boolean notify) {
        int mapped;
        if (boseMode == BoseDeviceConfig.MODE_AWARE) mapped = NOISE_TRANSPARENT;
        else if (boseMode == BoseDeviceConfig.MODE_QUIET) {
            mapped = confirmedAncEnabled ? NOISE_ANC : NOISE_OFF;
        } else mapped = NOISE_ANC;
        confirmedNoiseMode = mapped;
        if (notify) {
            Logs.trace("bose confirmed state mode=" + boseMode
                    + " anc=" + confirmedAncEnabled + " uiMode=" + mapped);
        }
        return mapped;
    }

    static void onBoseAudioSettings(byte[] payload) {
        if (!installed || payload == null || payload.length < 5) return;
        // [31.10] layout: [cnc, autoCNC, spatial, reserved, anc]
        DirectBoseController.cacheAudioSettings(BoseDeviceConfig.MAC, payload);
        confirmedAncEnabled = payload[4] != 0;
        boseNoiseCancellation = confirmedAncEnabled;
        noiseMode = resolveConfirmedMode(true);
        notifyChange(FLAG_NOISE);
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

    /**
     * Record Melody's own column names once per query kind. They reveal which
     * extra fields (device class, icon, category) ColorOS reads to pick the
     * earphone icon in the control center.
     */
    private static volatile boolean loggedActiveColumns;
    private static volatile boolean loggedNoiseColumns;

    private static void logCursorColumns(String kind, Cursor cursor) {
        if ("active".equals(kind)) {
            if (loggedActiveColumns) return;
            loggedActiveColumns = true;
        } else {
            if (loggedNoiseColumns) return;
            loggedNoiseColumns = true;
        }
        if (cursor == null) {
            Logs.trace("bose melody " + kind + " columns=null");
            return;
        }
        String[] columns = cursor.getColumnNames();
        StringBuilder builder = new StringBuilder();
        for (String column : columns) {
            if (builder.length() > 0) builder.append(',');
            builder.append(column);
        }
        Logs.trace("bose melody " + kind + " columns=[" + builder + "] rows="
                + cursor.getCount());
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

    /** Enqueue a mode change; the UI changes only after a confirmed read-back. */
    private static void applyBose(int mode) {
        int target;
        if (mode == NOISE_ANC) target = BoseDeviceConfig.MODE_QUIET;
        else if (mode == NOISE_TRANSPARENT) target = BoseDeviceConfig.MODE_AWARE;
        else if (mode == NOISE_OFF) target = BoseDeviceConfig.MODE_OFF;
        else return;
        Logs.trace("bose melody click mode=" + mode + " target=" + target);
        Context ctx = context;
        if (ctx != null) DirectBoseController.requestMode(ctx, BoseDeviceConfig.MAC, target);
        else syncIfNeeded();
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
                    } else {
                        // Bose Music can transiently tear down the ACL/RFCOMM link while
                        // changing profiles. Keep the ColorOS tile available; otherwise
                        // SystemUI remembers the wear=false update until a full reboot.
                        DirectBoseController.disconnect(receiver, BoseDeviceConfig.MAC, "bose acl gone");
                        Logs.trace("bose ACL disconnected; keep native control tile available");
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
