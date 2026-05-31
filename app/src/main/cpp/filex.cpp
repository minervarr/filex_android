#include <jni.h>
#include <android/log.h>
#include <filesystem>
#include <string>
#include <vector>

#define LOG_TAG "filex"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

extern "C" {

JNIEXPORT jobjectArray JNICALL
Java_io_nava_filex_MainActivity_listDirectory(JNIEnv *env, jobject /*thiz*/, jstring jpath) {
    const char *path_cstr = env->GetStringUTFChars(jpath, nullptr);
    std::string path(path_cstr);
    env->ReleaseStringUTFChars(jpath, path_cstr);

    std::vector<std::string> entries;
    std::error_code ec;

    for (const auto &entry : fs::directory_iterator(path, ec)) {
        entries.push_back(entry.path().filename().string());
    }

    if (ec) {
        LOGE("directory_iterator error: %s", ec.message().c_str());
    }

    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(
            static_cast<jsize>(entries.size()), string_class, nullptr);

    for (jsize i = 0; i < static_cast<jsize>(entries.size()); ++i) {
        env->SetObjectArrayElement(result, i, env->NewStringUTF(entries[i].c_str()));
    }

    return result;
}

JNIEXPORT jlong JNICALL
Java_io_nava_filex_MainActivity_getFileSize(JNIEnv *env, jobject /*thiz*/, jstring jpath) {
    const char *path_cstr = env->GetStringUTFChars(jpath, nullptr);
    std::error_code ec;
    uintmax_t size = fs::file_size(path_cstr, ec);
    env->ReleaseStringUTFChars(jpath, path_cstr);

    if (ec) {
        LOGE("file_size error: %s", ec.message().c_str());
        return -1L;
    }
    return static_cast<jlong>(size);
}

} // extern "C"
