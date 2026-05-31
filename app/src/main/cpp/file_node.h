#pragma once
#include <jni.h>
#include <string>
#include <sys/stat.h>
#include "debug.h"

struct FileNode {
    std::string name;
    std::string absolutePath;
    bool        isDirectory = false;
    bool        isSymlink   = false;
    long long   size        = 0;
    long long   modifiedMs  = 0;
    int         permissions = 0;
};

// Cached JNI IDs — populated once in JNI_OnLoad, valid for the library lifetime.
struct FileNodeJni {
    jclass    cls  = nullptr;  // global ref
    jmethodID ctor = nullptr;
};
extern FileNodeJni g_file_node_jni;

// Stat a path using lstat; if it's a symlink also stat() the target for isDirectory.
inline bool stat_node(const std::string &path, FileNode &out) {
    struct stat lst{};
    if (lstat(path.c_str(), &lst) != 0) {
        LOGE("lstat failed path=%s errno=%d", path.c_str(), errno);
        return false;
    }
    out.isSymlink   = S_ISLNK(lst.st_mode);
    out.permissions = static_cast<int>(lst.st_mode & 0777);
    out.size        = static_cast<long long>(lst.st_size);
    out.modifiedMs  = static_cast<long long>(lst.st_mtime) * 1000LL;

    if (out.isSymlink) {
        // Follow the link to decide if target is a directory
        struct stat st{};
        out.isDirectory = (stat(path.c_str(), &st) == 0) && S_ISDIR(st.st_mode);
    } else {
        out.isDirectory = S_ISDIR(lst.st_mode);
    }
    return true;
}

// Build a Java FileNode object using cached JNI IDs. Never calls FindClass.
inline jobject file_node_to_java(JNIEnv *env, const FileNode &n) {
    jstring jname = env->NewStringUTF(n.name.c_str());
    jstring jpath = env->NewStringUTF(n.absolutePath.c_str());
    jobject obj   = env->NewObject(
        g_file_node_jni.cls, g_file_node_jni.ctor,
        jname, jpath,
        n.isDirectory ? JNI_TRUE : JNI_FALSE,
        n.isSymlink   ? JNI_TRUE : JNI_FALSE,
        (jlong)n.size,
        (jlong)n.modifiedMs,
        (jint)n.permissions);
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(jpath);
    return obj;
}
