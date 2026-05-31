package io.nava.filex;

public class FileNode {
    public final String  name;
    public final String  absolutePath;
    public final boolean isDirectory;
    public final boolean isSymlink;
    public final long    size;        // bytes; -1 for dirs (use directorySize separately)
    public final long    modifiedMs;  // epoch millis
    public final int     permissions; // unix mode bits 0–0777

    // Called from C++ via JNI — signature must stay in sync with file_node.h
    public FileNode(String name, String absolutePath,
                    boolean isDirectory, boolean isSymlink,
                    long size, long modifiedMs, int permissions) {
        this.name         = name;
        this.absolutePath = absolutePath;
        this.isDirectory  = isDirectory;
        this.isSymlink    = isSymlink;
        this.size         = size;
        this.modifiedMs   = modifiedMs;
        this.permissions  = permissions;
    }

    /** Human-readable size string, e.g. "4.2 MB". Returns "" for directories. */
    public String formattedSize() {
        if (isDirectory) return "";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.2f GB", size / (1024.0 * 1024 * 1024));
    }

    /** rwxrwxrwx string from mode bits. */
    public String permissionString() {
        char[] s = new char[9];
        int[] bits = {
            0400, 0200, 0100,
            0040, 0020, 0010,
            0004, 0002, 0001
        };
        char[] chars = {'r','w','x','r','w','x','r','w','x'};
        for (int i = 0; i < 9; i++) {
            s[i] = (permissions & bits[i]) != 0 ? chars[i] : '-';
        }
        return new String(s);
    }
}
