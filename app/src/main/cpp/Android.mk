LOCAL_PATH := $(call my-dir)

FFMPEG_ROOT := $(LOCAL_PATH)/../../../../third_party/ffmpegAndroid/$(TARGET_ARCH_ABI)

include $(CLEAR_VARS)
LOCAL_MODULE := avcodec-prebuilt
LOCAL_SRC_FILES := ../../../../third_party/ffmpegAndroid/$(TARGET_ARCH_ABI)/libavcodec.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := avformat-prebuilt
LOCAL_SRC_FILES := ../../../../third_party/ffmpegAndroid/$(TARGET_ARCH_ABI)/libavformat.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := avutil-prebuilt
LOCAL_SRC_FILES := ../../../../third_party/ffmpegAndroid/$(TARGET_ARCH_ABI)/libavutil.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := swscale-prebuilt
LOCAL_SRC_FILES := ../../../../third_party/ffmpegAndroid/$(TARGET_ARCH_ABI)/libswscale.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := swresample-prebuilt
LOCAL_SRC_FILES := ../../../../third_party/ffmpegAndroid/$(TARGET_ARCH_ABI)/libswresample.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := camera_ffmpeg_player
LOCAL_SRC_FILES := ffmpeg_rtsp_player.cpp
LOCAL_CPPFLAGS := -std=c++17 -fexceptions -frtti
LOCAL_C_INCLUDES := $(FFMPEG_ROOT)/include
LOCAL_SHARED_LIBRARIES := \
    avcodec-prebuilt \
    avformat-prebuilt \
    avutil-prebuilt \
    swscale-prebuilt \
    swresample-prebuilt
LOCAL_LDLIBS := -landroid -llog
include $(BUILD_SHARED_LIBRARY)
