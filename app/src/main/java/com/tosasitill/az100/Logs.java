package com.tosasitill.az100;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedBridge;

/**
 * Single logging gate.
 *
 * Hot paths must never build a message unless {@link #DEBUG} is on. The
 * {@link #trace} channel records Bose BMAP link events and operations under
 * {@code MelodyEarphone}, while one-shot lifecycle errors also go through
 * LSPosed logging:
 *
 * <pre>adb logcat -s MelodyEarphone:V</pre>
 */
final class Logs {

    /** Flip to true, rebuild and reinstall for verbose LSPosed logging. */
    static final boolean DEBUG = false;

    static final String TAG = "MelodyEarphone";

    static final String TRACE_TAG = "MelodyEarphone";

    private static volatile File traceFile;
    private static volatile boolean traceOpened;

    /**
     * Mirror the trace into a file the module can always write and {@code adb
     * shell} can always read ({@code /sdcard/Android/data/&lt;pkg&gt;/files/}).
     * logd drops messages from a process as chatty as SystemUI, which made the
     * logcat-only trace useless for diagnosis.
     */
    static void traceToFile(File directory) {
        if (directory == null) return;
        traceFile = new File(directory, "bose-melody.log");
    }

    private Logs() {
    }

    static void d(String message) {
        if (DEBUG) XposedBridge.log(TAG + ": " + message);
    }

    static void d(String message, Throwable t) {
        if (DEBUG) XposedBridge.log(TAG + ": " + message + " - " + t);
    }

    /** One-shot lifecycle events (process install, fatal setup errors). */
    static void i(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    static void e(String message, Throwable t) {
        XposedBridge.log(TAG + ": " + message + " - " + t);
    }

    /** Airoha link timeline; only called on link events, never on hot paths. */
    static void trace(String message) {
        Log.i(TRACE_TAG, message);
        File file = traceFile;
        if (file == null) return;
        try {
            boolean append = traceOpened;
            if (!append) traceOpened = true;
            FileOutputStream out = new FileOutputStream(file, append);
            String line = System.currentTimeMillis() + " " + message + '\n';
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Throwable ignored) {
        }
    }
}
