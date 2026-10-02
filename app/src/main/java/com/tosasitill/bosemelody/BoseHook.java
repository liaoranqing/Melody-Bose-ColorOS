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
            hookDetailPreferenceFragment(loader);
                        }
                    });
        } catch (Throwable error) {
            Logs.e("Bose Melody hook failed", error);
        }
    }

    private static void hookDetailPreferenceFragment(ClassLoader loader) {
        try {
            Class<?> fragment = XposedHelpers.findClass("androidx.preference.g", loader);
            XposedHelpers.findAndHookMethod(fragment, "onViewCreated",
                    android.view.View.class, android.os.Bundle.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object object = param.thisObject;
                            String name = object.getClass().getName();
                            if (name.contains("detail") || name.contains("Detail")) {
                                Activity activity = ((android.view.View) param.args[0]).getContext()
                                        instanceof Activity
                                        ? (Activity) ((android.view.View) param.args[0]).getContext()
                                        : null;
                                if (activity != null) BoseDetailPanel.install(activity);
                            }
                        }
                    });
            Logs.trace("melody preference detail hook installed");
        } catch (Throwable error) {
            Logs.e("melody preference detail hook failed", error);
        }
    }

    private static void hookDetailActivity(ClassLoader loader) {
        try {
            Class<?> detail = XposedHelpers.findClass(
                    "com.oplus.melody.ui.component.detail.DetailMainActivity", loader);
            XposedHelpers.findAndHookMethod(detail, "onCreate", android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            final Activity activity = (Activity) param.thisObject;
                            activity.getWindow().getDecorView().postDelayed(
                                    () -> BoseDetailPanel.install(activity), 250L);
                            activity.getWindow().getDecorView().postDelayed(
                                    () -> BoseDetailPanel.install(activity), 900L);
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
