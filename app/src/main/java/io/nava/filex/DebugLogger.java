package io.nava.filex;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Dual-sink logger: Logcat (always) + buffered file on device.
 *
 * File: /sdcard/Android/data/io.nava.filex/files/filex_debug.log
 * adb filter: adb -s 192.168.101.6 logcat -s filex:V
 * Read file:  adb -s 192.168.101.6 shell cat <path shown at init>
 */
public final class DebugLogger {

    private static final String TAG       = "filex";
    private static final long   MAX_BYTES = 2L * 1024 * 1024;

    private static File           logFile;
    private static BufferedWriter writer;
    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private DebugLogger() {}

    public static synchronized void init(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return;
        logFile = new File(dir, "filex_debug.log");
        try {
            writer = new BufferedWriter(new FileWriter(logFile, true));
        } catch (IOException e) {
            Log.e(TAG, "DebugLogger init failed", e);
            return;
        }
        i("DebugLogger", "log: " + logFile.getAbsolutePath());
    }

    /** Must be called from onDestroy to flush the buffered writer. */
    public static synchronized void close() {
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
    }

    public static void d(String tag, String msg) { Log.d(TAG, fmt(tag, msg)); write("D", tag, msg); }
    public static void i(String tag, String msg) { Log.i(TAG, fmt(tag, msg)); write("I", tag, msg); }
    public static void w(String tag, String msg) { Log.w(TAG, fmt(tag, msg)); write("W", tag, msg); }
    public static void e(String tag, String msg) { Log.e(TAG, fmt(tag, msg)); write("E", tag, msg); }
    public static void e(String tag, String msg, Throwable t) {
        Log.e(TAG, fmt(tag, msg), t);
        write("E", tag, msg + " | " + Log.getStackTraceString(t));
    }

    public static String logFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : "";
    }

    private static String fmt(String tag, String msg) {
        return "[" + tag + "] " + msg;
    }

    private static synchronized void write(String level, String tag, String msg) {
        if (writer == null) return;
        try {
            if (logFile.length() > MAX_BYTES) {
                writer.close();
                //noinspection ResultOfMethodCallIgnored
                logFile.renameTo(new File(logFile.getAbsolutePath() + ".old"));
                writer = new BufferedWriter(new FileWriter(logFile, false));
            }
            writer.write(TS_FMT.format(new Date()) + " " + level + " [" + tag + "] " + msg + "\n");
            writer.flush();
        } catch (IOException ignored) {}
    }
}
