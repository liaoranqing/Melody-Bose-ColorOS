package com.tosasitill.bosemelody;

import android.app.Application;
import android.content.Context;
import android.app.Activity;

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
                            Context active = app != null ? app : context;
            MelodyProviderHook.install(loader, active);
            hookDetailActivity(loader);
                        }
                    });
        } catch (Throwable error) {
            Logs.e("Bose Melody hook failed", error);
        }
    }

    private static void hookDetailActivity(ClassLoader loader) {
        try {
            Class<?> detail = XposedHelpers.findClass(
                    "com.oplus.melody.ui.component.detail.DetailMainActivity", loader);
            XposedHelpers.findAndHookMethod(detail, "onCreate", android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            BoseDetailPanel.install((Activity) param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(detail, "onStart", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    BoseDetailPanel.install((Activity) param.thisObject);
                }
            });
            Logs.trace("melody detail activity hooked");
        } catch (Throwable error) {
            Logs.e("melody detail activity hook failed", error);
        }
    }
}
