package io.nava.filex;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class FileManager {

    static {
        System.loadLibrary("filex");
    }

    private FileManager() {}

    // ── JNI ───────────────────────────────────────────────────────────────────
    // IMPORTANT: getLastError() reads a thread_local in C++. Always call it
    // on the same background thread that ran the operation, before posting
    // results to the main thread.

    public static native FileNode[] listDirectory(String path);
    public static native FileNode   getProperties(String path);
    public static native boolean    deleteEntry(String path);
    public static native boolean    renameEntry(String oldPath, String newPath);
    public static native long       directorySize(String path);
    public static native String     getLastError();

    // ── Java helpers ──────────────────────────────────────────────────────────

    public static boolean createDirectory(String path) {
        File dir = new File(path);
        if (dir.exists()) return false;
        return dir.mkdirs();
    }

    public static boolean copyEntry(String src, String dst) {
        try {
            copyRecursive(new File(src), new File(dst));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs())
                throw new IOException("mkdirs failed: " + dst);
            File[] children = src.listFiles();
            if (children == null) return; // unreadable, skip silently
            for (File child : children) {
                copyRecursive(child, new File(dst, child.getName()));
            }
        } else {
            copyFile(src, dst);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in  = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
