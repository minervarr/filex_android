#pragma once
#include <android/log.h>

#define FILEX_TAG "filex"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, FILEX_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  FILEX_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  FILEX_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, FILEX_TAG, __VA_ARGS__)

#define TRACE(func) LOGD("[TRACE] " func " enter")
