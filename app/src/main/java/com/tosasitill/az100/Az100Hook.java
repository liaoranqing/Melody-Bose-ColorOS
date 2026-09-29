package com.tosasitill.az100;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Volume-panel adapter for the Technics EAH-AZ100.
 *
 * The panel lives in SystemUI but reads everything from Melody's
 * EarphoneControlProvider.  This module is therefore scoped to
 * {@code com.oplus.melody} only: it answers that provider and drives the
 * headset through {@link DirectAirohaController}.  SystemUI and Device Space
 * are not injected.
 */
public final class Az100Hook implements IXposedHookLoadPackage {

    private static final String MELODY = "com.oplus.melody";

    static final String AZ100_MAC = "B8:20:8E:EE:84:85";
    static final String AZ100_NAME = "Technics EAH-AZ100";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!MELODY.equals(param.packageName)) return;
        final ClassLoader loader = param.classLoader;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Context ctx = (Context) param.args[0];
                            Context app = ctx == null ? null : ctx.getApplicationContext();
                            MelodyProviderHook.install(loader, app != null ? app : ctx);
                        }
                    });
        } catch (Throwable t) {
            Logs.e("Melody hook failed", t);
        }
    }

    static boolean isAz100Mac(String address) {
        return address != null && AZ100_MAC.equalsIgnoreCase(address);
    }

    /** Headset reported a mode.  Only the volume tile consumes it. */
    static void onAirohaMode(String address, int status) {
        if (!isAz100Mac(address)) return;
        MelodyProviderHook.onHeadsetMode(status);
    }

    /** Battery samples are cached by the controller; the tile does not show them. */
    static void onAirohaBattery(String address, int[] values) {
        if (!isAz100Mac(address) || values == null) return;
        MelodyProviderHook.onHeadsetBattery();
    }
}
