#include <jni.h>
#include <string>
#include <vector>
#include <filesystem>
#include <sys/stat.h>
#include <cstring>
#include <cerrno>
#include <unistd.h>

#include "debug.h"
#include "file_node.h"

namespace fs = std::filesystem;

FileNodeJni g_file_node_jni;

// ── JNI lifecycle ─────────────────────────────────────────────────────────────

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;

    jclass local = env->FindClass("io/nava/filex/FileNode");
    if (!local) { LOGE("JNI_OnLoad: FileNode class not found"); return JNI_ERR; }

    g_file_node_jni.cls  = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    g_file_node_jni.ctor = env->GetMethodID(local,
        "<init>", "(Ljava/lang/String;Ljava/lang/String;ZZJJI)V");
    env->DeleteLocalRef(local);

    if (!g_file_node_jni.ctor) { LOGE("JNI_OnLoad: FileNode ctor not found"); return JNI_ERR; }
    LOGI("JNI_OnLoad ok");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK)
        env->DeleteGlobalRef(g_file_node_jni.cls);
}

// ── Internals ─────────────────────────────────────────────────────────────────

// NOT thread_local — getLastError() is called from the same worker thread
// that ran the operation, captured into a local before posting to the main thread.
static thread_local std::string g_last_error;

static void set_error(const char *ctx, const char *detail) {
    g_last_error = std::string(ctx) + ": " + (detail ? detail : "unknown");
    LOGE("%s", g_last_error.c_str());
}

static std::string jstr(JNIEnv *env, jstring js) {
    if (!js) return {};
    const char *cs = env->GetStringUTFChars(js, nullptr);
    std::string s(cs);
    env->ReleaseStringUTFChars(js, cs);
    return s;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_nava_filex_FileManager_getLastError(JNIEnv *env, jclass) {
    return env->NewStringUTF(g_last_error.c_str());
}

// ── Directory listing ─────────────────────────────────────────────────────────
// Returns null on open failure (caller checks null = error).
// Returns empty array for an empty directory (not an error).
// Does NOT sort — Java sorts after filtering.

JNIEXPORT jobjectArray JNICALL
Java_io_nava_filex_FileManager_listDirectory(JNIEnv *env, jclass, jstring jpath) {
    TRACE("listDirectory");
    std::string path = jstr(env, jpath);
    LOGD("listDirectory path=%s", path.c_str());

    std::vector<FileNode> nodes;
    std::error_code ec;

    fs::directory_iterator it(path,
        fs::directory_options::skip_permission_denied, ec);

    if (ec) {
        set_error("listDirectory", ec.message().c_str());
        return nullptr;  // null = open failed; empty array = directory is empty
    }

    for (const auto &entry : it) {
        FileNode n;
        n.name         = entry.path().filename().string();
        n.absolutePath = entry.path().string();
        if (!stat_node(n.absolutePath, n)) continue;
        nodes.push_back(std::move(n));
    }

    jobjectArray arr = env->NewObjectArray((jsize)nodes.size(), g_file_node_jni.cls, nullptr);
    for (jsize i = 0; i < (jsize)nodes.size(); ++i) {
        jobject obj = file_node_to_java(env, nodes[i]);
        env->SetObjectArrayElement(arr, i, obj);
        env->DeleteLocalRef(obj);
    }
    LOGD("listDirectory returned %zu entries", nodes.size());
    return arr;
}

// ── Properties ────────────────────────────────────────────────────────────────

JNIEXPORT jobject JNICALL
Java_io_nava_filex_FileManager_getProperties(JNIEnv *env, jclass, jstring jpath) {
    TRACE("getProperties");
    std::string path = jstr(env, jpath);

    FileNode n;
    n.absolutePath = path;
    n.name = fs::path(path).filename().string();
    if (!stat_node(path, n)) {
        int saved = errno;
        set_error("getProperties", strerror(saved));
        return nullptr;
    }
    return file_node_to_java(env, n);
}

// ── Delete ────────────────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_io_nava_filex_FileManager_deleteEntry(JNIEnv *env, jclass, jstring jpath) {
    TRACE("deleteEntry");
    std::string path = jstr(env, jpath);
    LOGI("delete path=%s", path.c_str());
    std::error_code ec;
    fs::remove_all(path, ec);
    if (ec) { set_error("deleteEntry", ec.message().c_str()); return JNI_FALSE; }
    return JNI_TRUE;
}

// ── Rename ────────────────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_io_nava_filex_FileManager_renameEntry(JNIEnv *env, jclass, jstring jold, jstring jnew) {
    TRACE("renameEntry");
    std::string old_path = jstr(env, jold);
    std::string new_path = jstr(env, jnew);
    LOGI("rename %s -> %s", old_path.c_str(), new_path.c_str());
    std::error_code ec;
    fs::rename(old_path, new_path, ec);
    if (ec) { set_error("renameEntry", ec.message().c_str()); return JNI_FALSE; }
    return JNI_TRUE;
}

// ── Directory size ────────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_io_nava_filex_FileManager_directorySize(JNIEnv *env, jclass, jstring jpath) {
    TRACE("directorySize");
    std::string path = jstr(env, jpath);
    uintmax_t total = 0;
    std::error_code iter_ec;

    fs::recursive_directory_iterator it(path,
        fs::directory_options::skip_permission_denied, iter_ec);
    if (iter_ec) {
        set_error("directorySize", iter_ec.message().c_str());
        return -1L;
    }

    for (const auto &entry : it) {
        std::error_code entry_ec;
        if (entry.is_regular_file(entry_ec) && !entry_ec) {
            std::error_code size_ec;
            uintmax_t sz = fs::file_size(entry.path(), size_ec);
            if (!size_ec) total += sz;
        }
    }
    return static_cast<jlong>(total);
}

} // extern "C"
