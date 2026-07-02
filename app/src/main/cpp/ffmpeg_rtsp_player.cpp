#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>
}

namespace {

constexpr const char* kTag = "CameraFfmpegNative";

void logInfo(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message.c_str());
}

void logError(const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

std::string ffmpegError(int errnum) {
    char errbuf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_make_error_string(errbuf, AV_ERROR_MAX_STRING_SIZE, errnum);
    return std::string(errbuf);
}

std::string injectCredentials(const std::string& url,
                              const std::string& username,
                              const std::string& password) {
    if (username.empty()) {
        return url;
    }

    const std::string scheme = "rtsp://";
    if (url.rfind(scheme, 0) != 0) {
        return url;
    }

    if (url.find('@') != std::string::npos) {
        return url;
    }

    return scheme + username + ":" + password + "@" + url.substr(scheme.size());
}

class FfmpegRtspPlayer {
public:
    FfmpegRtspPlayer() = default;

    ~FfmpegRtspPlayer() {
        stop();
    }

    bool start(JNIEnv* env,
               const std::string& rtspUrl,
               jobject surface,
               const std::string& username,
               const std::string& password,
               bool forceUseTcp) {
        stop();

        ANativeWindow* nativeWindow = nullptr;
        if (surface != nullptr) {
            nativeWindow = ANativeWindow_fromSurface(env, surface);
            if (nativeWindow == nullptr) {
                hasPlaybackError_.store(true);
                logError("ANativeWindow 创建失败，无法启动 FFmpeg RTSP 播放");
                return false;
            }
        } else {
            logInfo("未提供 Surface，将以帧回调模式运行（不渲染到窗口）");
        }

        {
            std::lock_guard<std::mutex> lock(mutex_);
            window_ = nativeWindow;
            rtspUrl_ = injectCredentials(rtspUrl, username, password);
            forceUseTcp_ = forceUseTcp;
            stopRequested_.store(false);
            playing_.store(false);
            hasVideoOutput_.store(false);
            hasPlaybackError_.store(false);
        }

        decodeThread_ = std::thread(&FfmpegRtspPlayer::decodeLoop, this);
        return true;
    }

    void stop() {
        stopRequested_.store(true);

        if (decodeThread_.joinable()) {
            decodeThread_.join();
        }

        releaseWindow();
        playing_.store(false);
        hasVideoOutput_.store(false);
    }

    bool isPlaying() const {
        return playing_.load();
    }

    bool hasVideoOutput() const {
        return hasVideoOutput_.load();
    }

    bool hasPlaybackError() const {
        return hasPlaybackError_.load();
    }

    void setFrameCallback(JNIEnv* env, jobject listener) {
        std::lock_guard<std::mutex> lock(callbackMutex_);
        if (listenerRef_ != nullptr && env != nullptr) {
            env->DeleteGlobalRef(listenerRef_);
        }
        listenerRef_ = nullptr;
        onFrameMethod_ = nullptr;
        callbackVm_ = nullptr;
        lastCallbackTime_ = std::chrono::steady_clock::time_point();

        if (listener == nullptr || env == nullptr) {
            return;
        }

        if (env->GetJavaVM(&callbackVm_) != JNI_OK) {
            logError("GetJavaVM 失败，无法注册帧回调");
            return;
        }
        listenerRef_ = env->NewGlobalRef(listener);
        if (listenerRef_ == nullptr) {
            logError("NewGlobalRef 失败，无法注册帧回调");
            return;
        }
        jclass cls = env->GetObjectClass(listener);
        if (cls != nullptr) {
            onFrameMethod_ = env->GetMethodID(cls, "onFrame", "([BII)V");
            env->DeleteLocalRef(cls);
        }
        if (onFrameMethod_ == nullptr) {
            logError("未找到帧回调方法 onFrame(byte[], int, int)");
            env->DeleteGlobalRef(listenerRef_);
            listenerRef_ = nullptr;
            callbackVm_ = nullptr;
        }
    }

    void clearFrameCallback(JNIEnv* env) {
        std::lock_guard<std::mutex> lock(callbackMutex_);
        if (listenerRef_ != nullptr && env != nullptr) {
            env->DeleteGlobalRef(listenerRef_);
        }
        listenerRef_ = nullptr;
        onFrameMethod_ = nullptr;
        callbackVm_ = nullptr;
    }

private:
    static int interruptCallback(void* opaque) {
        if (opaque == nullptr) {
            return 0;
        }
        auto* player = reinterpret_cast<FfmpegRtspPlayer*>(opaque);
        return player->stopRequested_.load() ? 1 : 0;
    }

    void decodeLoop() {
        avformat_network_init();
        const AVInputFormat* inputFormat = nullptr;
        AVFormatContext* formatContext = avformat_alloc_context();
        AVCodecContext* codecContext = nullptr;
        AVPacket* packet = av_packet_alloc();
        AVFrame* frame = av_frame_alloc();
        SwsContext* swsContext = nullptr;
        AVDictionary* options = nullptr;
        uint8_t* rgbaBuffer = nullptr;
        int videoStreamIndex = -1;
        bool success = false;

        if (formatContext == nullptr || packet == nullptr || frame == nullptr) {
            hasPlaybackError_.store(true);
            logError("FFmpeg 初始化失败：无法分配基础上下文");
            goto cleanup;
        }

        formatContext->interrupt_callback.callback = interruptCallback;
        formatContext->interrupt_callback.opaque = this;

        av_dict_set(&options, "rtsp_transport", forceUseTcp_ ? "tcp" : "udp", 0);
        av_dict_set(&options, "rw_timeout", "10000000", 0);
        av_dict_set(&options, "timeout", "10000000", 0);

        logInfo(std::string("FFmpeg 正在打开 RTSP: ")
                + (forceUseTcp_ ? "tcp" : "udp"));

        inputFormat = av_find_input_format("rtsp");
        if (inputFormat == nullptr) {
            hasPlaybackError_.store(true);
            logError("未找到 rtsp demuxer，当前 FFmpeg 构建可能缺少 RTSP 支持");
            goto cleanup;
        }

        {
            std::lock_guard<std::mutex> lock(mutex_);
            int openResult = avformat_open_input(&formatContext, rtspUrl_.c_str(), inputFormat, &options);
            av_dict_free(&options);
            if (openResult < 0) {
                hasPlaybackError_.store(true);
                logError("avformat_open_input 失败: " + ffmpegError(openResult));
                goto cleanup;
            }
        }

        if (avformat_find_stream_info(formatContext, nullptr) < 0) {
            hasPlaybackError_.store(true);
            logError("avformat_find_stream_info 失败");
            goto cleanup;
        }

        for (unsigned int i = 0; i < formatContext->nb_streams; ++i) {
            if (formatContext->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
                videoStreamIndex = static_cast<int>(i);
                break;
            }
        }

        if (videoStreamIndex < 0) {
            hasPlaybackError_.store(true);
            logError("未找到视频流");
            goto cleanup;
        }

        {
            AVCodecParameters* codecParameters = formatContext->streams[videoStreamIndex]->codecpar;
            const AVCodec* decoder = avcodec_find_decoder(codecParameters->codec_id);
            if (decoder == nullptr) {
                hasPlaybackError_.store(true);
                logError("未找到可用视频解码器");
                goto cleanup;
            }

            codecContext = avcodec_alloc_context3(decoder);
            if (codecContext == nullptr) {
                hasPlaybackError_.store(true);
                logError("avcodec_alloc_context3 失败");
                goto cleanup;
            }

            if (avcodec_parameters_to_context(codecContext, codecParameters) < 0) {
                hasPlaybackError_.store(true);
                logError("avcodec_parameters_to_context 失败");
                goto cleanup;
            }

            if (avcodec_open2(codecContext, decoder, nullptr) < 0) {
                hasPlaybackError_.store(true);
                logError("avcodec_open2 失败");
                goto cleanup;
            }
        }

        swsContext = sws_getContext(
                codecContext->width,
                codecContext->height,
                codecContext->pix_fmt,
                codecContext->width,
                codecContext->height,
                AV_PIX_FMT_RGBA,
                SWS_BILINEAR,
                nullptr,
                nullptr,
                nullptr
        );

        if (swsContext == nullptr) {
            hasPlaybackError_.store(true);
            logError("sws_getContext 失败");
            goto cleanup;
        }

        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (window_ != nullptr) {
                ANativeWindow_setBuffersGeometry(
                        window_,
                        codecContext->width,
                        codecContext->height,
                        WINDOW_FORMAT_RGBA_8888
                );
            }
        }

        {
            int rgbaBufferSize = av_image_get_buffer_size(
                    AV_PIX_FMT_RGBA,
                    codecContext->width,
                    codecContext->height,
                    1
            );
            rgbaBuffer = static_cast<uint8_t*>(av_malloc(rgbaBufferSize));
            if (rgbaBuffer == nullptr) {
                hasPlaybackError_.store(true);
                logError("RGBA 缓冲区分配失败");
                goto cleanup;
            }
        }

        while (!stopRequested_.load()) {
            int readResult = av_read_frame(formatContext, packet);
            if (readResult < 0) {
                hasPlaybackError_.store(!stopRequested_.load());
                if (!stopRequested_.load()) {
                    logError("av_read_frame 失败: " + ffmpegError(readResult));
                }
                break;
            }

            if (packet->stream_index != videoStreamIndex) {
                av_packet_unref(packet);
                continue;
            }

            int sendResult = avcodec_send_packet(codecContext, packet);
            av_packet_unref(packet);
            if (sendResult < 0) {
                hasPlaybackError_.store(true);
                logError("avcodec_send_packet 失败: " + ffmpegError(sendResult));
                break;
            }

            while (!stopRequested_.load()) {
                int receiveResult = avcodec_receive_frame(codecContext, frame);
                if (receiveResult == AVERROR(EAGAIN) || receiveResult == AVERROR_EOF) {
                    break;
                }

                if (receiveResult < 0) {
                    hasPlaybackError_.store(true);
                    logError("avcodec_receive_frame 失败: " + ffmpegError(receiveResult));
                    goto cleanup;
                }

                if (!renderFrame(frame, swsContext, rgbaBuffer, codecContext->width, codecContext->height)) {
                    hasPlaybackError_.store(true);
                    goto cleanup;
                }

                hasVideoOutput_.store(true);
                playing_.store(true);
                success = true;
            }
        }

    cleanup:
        playing_.store(false);
        if (!success && !stopRequested_.load()) {
            hasPlaybackError_.store(true);
        }

        if (rgbaBuffer != nullptr) {
            av_free(rgbaBuffer);
        }
        if (swsContext != nullptr) {
            sws_freeContext(swsContext);
        }
        if (options != nullptr) {
            av_dict_free(&options);
        }
        if (frame != nullptr) {
            av_frame_free(&frame);
        }
        if (packet != nullptr) {
            av_packet_free(&packet);
        }
        if (codecContext != nullptr) {
            avcodec_free_context(&codecContext);
        }
        if (formatContext != nullptr) {
            avformat_close_input(&formatContext);
        }
    }

    bool renderFrame(AVFrame* frame,
                     SwsContext* swsContext,
                     uint8_t* rgbaBuffer,
                     int width,
                     int height) {
        uint8_t* dstData[4] = {nullptr, nullptr, nullptr, nullptr};
        int dstLinesize[4] = {0, 0, 0, 0};
        int fillResult = av_image_fill_arrays(
                dstData,
                dstLinesize,
                rgbaBuffer,
                AV_PIX_FMT_RGBA,
                width,
                height,
                1
        );
        if (fillResult < 0) {
            logError("av_image_fill_arrays 失败: " + ffmpegError(fillResult));
            return false;
        }

        sws_scale(
                swsContext,
                frame->data,
                frame->linesize,
                0,
                height,
                dstData,
                dstLinesize
        );

        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (window_ != nullptr) {
                ANativeWindow_Buffer windowBuffer;
                if (ANativeWindow_lock(window_, &windowBuffer, nullptr) == 0) {
                    auto* dstPixels = static_cast<uint8_t*>(windowBuffer.bits);
                    int copyWidth = std::min(windowBuffer.stride * 4, dstLinesize[0]);
                    for (int y = 0; y < height; ++y) {
                        memcpy(
                                dstPixels + y * windowBuffer.stride * 4,
                                rgbaBuffer + y * dstLinesize[0],
                                copyWidth
                        );
                    }
                    ANativeWindow_unlockAndPost(window_);
                } else {
                    logError("ANativeWindow_lock 失败");
                }
            }
        }

        maybeNotifyFrame(rgbaBuffer, width, height);
        return true;
    }

    void maybeNotifyFrame(uint8_t* rgba, int width, int height) {
        JavaVM* vm = nullptr;
        JNIEnv* env = nullptr;
        bool attached = false;
        {
            // 整个 Java 调用期间持锁，确保 clearFrameCallback/setFrameCallback
            // 不会在回调执行过程中删除或替换全局引用，消除 use-after-free。
            std::lock_guard<std::mutex> lock(callbackMutex_);
            if (listenerRef_ == nullptr || callbackVm_ == nullptr || onFrameMethod_ == nullptr) {
                return;
            }

            auto now = std::chrono::steady_clock::now();
            auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                    now - lastCallbackTime_).count();
            if (elapsedMs < callbackThrottleMs_) {
                return;
            }
            lastCallbackTime_ = now;

            vm = callbackVm_;
            if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                    return;
                }
                attached = true;
            }
            if (env == nullptr) {
                return;
            }

            // 重新检查：Attach 之后仍可能已被清除。
            if (listenerRef_ == nullptr || onFrameMethod_ == nullptr) {
                if (attached) {
                    vm->DetachCurrentThread();
                }
                return;
            }

            jsize size = static_cast<jsize>(width) * height * 4;
            jbyteArray array = env->NewByteArray(size);
            if (array != nullptr) {
                env->SetByteArrayRegion(array, 0, size, reinterpret_cast<const jbyte*>(rgba));
                env->CallVoidMethod(listenerRef_, onFrameMethod_, array, width, height);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                }
                env->DeleteLocalRef(array);
            }
        }
        if (attached && vm != nullptr) {
            vm->DetachCurrentThread();
        }
    }

    void releaseWindow() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (window_ != nullptr) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    std::mutex mutex_;
    std::thread decodeThread_;
    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> playing_{false};
    std::atomic<bool> hasVideoOutput_{false};
    std::atomic<bool> hasPlaybackError_{false};
    ANativeWindow* window_ = nullptr;
    std::string rtspUrl_;
    bool forceUseTcp_ = false;

    std::mutex callbackMutex_;
    JavaVM* callbackVm_ = nullptr;
    jobject listenerRef_ = nullptr;
    jmethodID onFrameMethod_ = nullptr;
    std::chrono::steady_clock::time_point lastCallbackTime_;
    int callbackThrottleMs_ = 120;
};

jlong nativeCreate(JNIEnv*, jobject) {
    auto* player = new FfmpegRtspPlayer();
    return reinterpret_cast<jlong>(player);
}

void nativeDestroy(JNIEnv* env, jobject, jlong nativeHandle) {
    auto* player = reinterpret_cast<FfmpegRtspPlayer*>(nativeHandle);
    if (player != nullptr) {
        // 先 join 解码线程，确保没有回调在执行，再清除回调全局引用。
        player->stop();
        player->clearFrameCallback(env);
        delete player;
    }
}

jboolean nativeStart(JNIEnv* env,
                     jobject,
                     jlong nativeHandle,
                     jstring rtspUrl,
                     jobject surface,
                     jstring username,
                     jstring password,
                     jboolean forceUseTcp) {
    auto* player = reinterpret_cast<FfmpegRtspPlayer*>(nativeHandle);
    if (player == nullptr || rtspUrl == nullptr) {
        return JNI_FALSE;
    }

    const char* urlChars = env->GetStringUTFChars(rtspUrl, nullptr);
    const char* usernameChars = username == nullptr ? nullptr : env->GetStringUTFChars(username, nullptr);
    const char* passwordChars = password == nullptr ? nullptr : env->GetStringUTFChars(password, nullptr);

    bool started = player->start(
            env,
            std::string(urlChars == nullptr ? "" : urlChars),
            surface,
            std::string(usernameChars == nullptr ? "" : usernameChars),
            std::string(passwordChars == nullptr ? "" : passwordChars),
            forceUseTcp == JNI_TRUE
    );

    if (urlChars != nullptr) {
        env->ReleaseStringUTFChars(rtspUrl, urlChars);
    }
    if (usernameChars != nullptr) {
        env->ReleaseStringUTFChars(username, usernameChars);
    }
    if (passwordChars != nullptr) {
        env->ReleaseStringUTFChars(password, passwordChars);
    }

    return started ? JNI_TRUE : JNI_FALSE;
}

void nativeStop(JNIEnv*, jobject, jlong nativeHandle) {
    auto* player = reinterpret_cast<FfmpegRtspPlayer*>(nativeHandle);
    if (player != nullptr) {
        player->stop();
    }
}

void nativeSetFrameCallback(JNIEnv* env, jobject, jlong nativeHandle, jobject listener) {
    auto* player = reinterpret_cast<FfmpegRtspPlayer*>(nativeHandle);
    if (player != nullptr) {
        player->setFrameCallback(env, listener);
    }
}

void nativeClearFrameCallback(JNIEnv* env, jobject, jlong nativeHandle) {
    auto* player = reinterpret_cast<FfmpegRtspPlayer*>(nativeHandle);
    if (player != nullptr) {
        player->clearFrameCallback(env);
    }
}

jboolean nativeIsPlaying(JNIEnv*, jobject, jlong nativeHandle) {
    auto* player = reinterpret_cast<FfmpegRtspPlayer*>(nativeHandle);
    return player != nullptr && player->isPlaying() ? JNI_TRUE : JNI_FALSE;
}

jboolean nativeHasVideoOutput(JNIEnv*, jobject, jlong nativeHandle) {
    auto* player = reinterpret_cast<FfmpegRtspPlayer*>(nativeHandle);
    return player != nullptr && player->hasVideoOutput() ? JNI_TRUE : JNI_FALSE;
}

jboolean nativeHasPlaybackError(JNIEnv*, jobject, jlong nativeHandle) {
    auto* player = reinterpret_cast<FfmpegRtspPlayer*>(nativeHandle);
    return player != nullptr && player->hasPlaybackError() ? JNI_TRUE : JNI_FALSE;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_haohanyh_car_12026_1kittymoeii_Camera_CameraVideoByRtsp_nativeCreate(
        JNIEnv* env,
        jobject thiz) {
    return nativeCreate(env, thiz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_haohanyh_car_12026_1kittymoeii_Camera_CameraVideoByRtsp_nativeDestroy(
        JNIEnv* env,
        jobject thiz,
        jlong nativeHandle) {
    nativeDestroy(env, thiz, nativeHandle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_haohanyh_car_12026_1kittymoeii_Camera_CameraVideoByRtsp_nativeStart(
        JNIEnv* env,
        jobject thiz,
        jlong nativeHandle,
        jstring rtspUrl,
        jobject surface,
        jstring username,
        jstring password,
        jboolean forceUseTcp) {
    return nativeStart(env, thiz, nativeHandle, rtspUrl, surface, username, password, forceUseTcp);
}

extern "C" JNIEXPORT void JNICALL
Java_com_haohanyh_car_12026_1kittymoeii_Camera_CameraVideoByRtsp_nativeStop(
        JNIEnv* env,
        jobject thiz,
        jlong nativeHandle) {
    nativeStop(env, thiz, nativeHandle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_haohanyh_car_12026_1kittymoeii_Camera_CameraVideoByRtsp_nativeIsPlaying(
        JNIEnv* env,
        jobject thiz,
        jlong nativeHandle) {
    return nativeIsPlaying(env, thiz, nativeHandle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_haohanyh_car_12026_1kittymoeii_Camera_CameraVideoByRtsp_nativeHasVideoOutput(
        JNIEnv* env,
        jobject thiz,
        jlong nativeHandle) {
    return nativeHasVideoOutput(env, thiz, nativeHandle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_haohanyh_car_12026_1kittymoeii_Camera_CameraVideoByRtsp_nativeHasPlaybackError(
        JNIEnv* env,
        jobject thiz,
        jlong nativeHandle) {
    return nativeHasPlaybackError(env, thiz, nativeHandle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_haohanyh_car_12026_1kittymoeii_Camera_CameraVideoByRtsp_nativeSetFrameCallback(
        JNIEnv* env,
        jobject thiz,
        jlong nativeHandle,
        jobject listener) {
    nativeSetFrameCallback(env, thiz, nativeHandle, listener);
}

extern "C" JNIEXPORT void JNICALL
Java_com_haohanyh_car_12026_1kittymoeii_Camera_CameraVideoByRtsp_nativeClearFrameCallback(
        JNIEnv* env,
        jobject thiz,
        jlong nativeHandle) {
    nativeClearFrameCallback(env, thiz, nativeHandle);
}
