package com.tosasitill.bosemelody;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Xposed entry point for the Bose QC Earbuds Ultra 2 Melody adapter. */
public final class BoseHook implements IXposedHookLoadPackage {
    private static final String MELODY = "com.oplus.melody";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!MELODY.equals(param.packageName)) return;
        final ClassLoader loader = param.classLoader;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            Context app = context == null ? null : context.getApplicationContext();
                            MelodyProviderHook.install(loader, app != null ? app : context);
                        }
                    });
        } catch (Throwable error) {
            Logs.e("Bose Melody hook failed", error);
        }
    }
}
