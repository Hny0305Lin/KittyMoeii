package com.haohanyh.car_2026_kittymoeii.Camera;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 摄像头 RTSP 串流播放器封装。
 *
 * <p>该类已切换为 FFmpeg + JNI 实现，负责把 RTSP 视频流解码为 RGBA 后直接渲染到
 * {@link SurfaceView} 对应的原生窗口。</p>
 *
 * <p>当前只处理视频轨，音频轨默认忽略，优先服务于小车摄像头实时预览。</p>
 */
public class CameraVideoByRtsp {
    private static final String TAG = "CameraVideoByRtsp";
    private static final String DEFAULT_CAMERA_IP = "192.168.31.100";
    private static final String DEFAULT_RTSP_USERNAME = "admin";
    private static final String DEFAULT_RTSP_PASSWORD = "888888";
    private static final int DEFAULT_RTSP_PORT = 10554;
    private static final String DEFAULT_RTSP_PATH = "/udp/av0_0";

    /**
     * 解码帧回调：RGBA 字节按行存储，可直接用于构建 ARGB_8888 Bitmap。
     */
    public interface FrameCallback {
        void onFrame(byte[] rgba, int width, int height);
    }

    private static volatile CameraVideoByRtsp cameraVideoByRtsp;

    static {
        System.loadLibrary("camera_ffmpeg_player");
    }

    private long nativeHandle = 0L;
    private SurfaceView boundSurfaceView;
    private SurfaceHolder.Callback surfaceCallback;
    private boolean surfaceReady = false;
    private boolean shouldPlayWhenSurfaceReady = false;
    private boolean pendingForceUseTcp = false;
    private String currentUsername = DEFAULT_RTSP_USERNAME;
    private String currentPassword = DEFAULT_RTSP_PASSWORD;
    private String currentRtspUrl = buildRtspUrl(DEFAULT_CAMERA_IP);

    public CameraVideoByRtsp() {
    }

    /**
     * 获取 RTSP 播放器单例。
     *
     * @return RTSP 播放器管理实例
     */
    public static CameraVideoByRtsp ManageCamera() {
        if (cameraVideoByRtsp == null) {
            synchronized (CameraVideoByRtsp.class) {
                if (cameraVideoByRtsp == null) {
                    cameraVideoByRtsp = new CameraVideoByRtsp();
                }
            }
        }
        return cameraVideoByRtsp;
    }

    /**
     * 根据摄像头 IP 生成默认 RTSP 播放地址。
     *
     * @param cameraIp 摄像头 IP 地址
     * @return 完整 RTSP 地址
     */
    public String buildRtspUrl(@Nullable String cameraIp) {
        String resolvedIp = cameraIp == null ? DEFAULT_CAMERA_IP : cameraIp.trim();
        if (resolvedIp.isEmpty()) {
            resolvedIp = DEFAULT_CAMERA_IP;
        }
        return buildRtspEndpoint(resolvedIp);
    }

    /**
     * 配置 RTSP 认证信息。
     *
     * @param username 用户名
     * @param password 密码
     */
    public synchronized void setCredentials(@Nullable String username, @Nullable String password) {
        currentUsername = username == null ? "" : username.trim();
        currentPassword = password == null ? "" : password.trim();
        currentRtspUrl = buildRtspEndpoint(extractIpFromRtspUrl(currentRtspUrl));
    }

    /**
     * 设置当前要播放的 RTSP 地址。
     *
     * @param rtspUrl 完整 RTSP 地址
     */
    public synchronized void setRtspUrl(@Nullable String rtspUrl) {
        String resolvedUrl = normalizeRtspUrl(rtspUrl);
        if (resolvedUrl != null) {
            currentRtspUrl = resolvedUrl;
        }
    }

    /**
     * 根据摄像头 IP 更新当前 RTSP 地址。
     *
     * @param cameraIp 摄像头 IP 地址
     */
    public synchronized void setRtspIp(@Nullable String cameraIp) {
        String resolvedIp = cameraIp == null ? DEFAULT_CAMERA_IP : cameraIp.trim();
        if (resolvedIp.isEmpty()) {
            resolvedIp = DEFAULT_CAMERA_IP;
        }
        currentRtspUrl = buildRtspEndpoint(resolvedIp);
    }

    /**
     * 获取当前 RTSP 地址。
     *
     * @return 当前使用的 RTSP 地址
     */
    @NonNull
    public synchronized String getRtspUrl() {
        return currentRtspUrl;
    }

    /**
     * 使用当前 RTSP 地址启动播放，默认走 RTP over UDP。
     *
     * @param context 上下文
     * @param surfaceView 视频输出视图
     */
    public synchronized void start(@NonNull Context context, @NonNull SurfaceView surfaceView) {
        start(context, surfaceView, currentRtspUrl, false);
    }

    /**
     * 使用指定 RTSP 地址启动播放，默认走 RTP over UDP。
     *
     * @param context 上下文
     * @param surfaceView 视频输出视图
     * @param rtspUrl 完整 RTSP 地址
     */
    public synchronized void start(@NonNull Context context,
                                   @NonNull SurfaceView surfaceView,
                                   @Nullable String rtspUrl) {
        start(context, surfaceView, rtspUrl, false);
    }

    /**
     * 启动 RTSP 播放。
     *
     * @param context 上下文
     * @param surfaceView 视频输出视图
     * @param rtspUrl 完整 RTSP 地址
     * @param forceUseTcp {@code true} 表示强制 RTSP over TCP，{@code false} 表示优先使用 UDP
     */
    public synchronized void start(@NonNull Context context,
                                   @NonNull SurfaceView surfaceView,
                                   @Nullable String rtspUrl,
                                   boolean forceUseTcp) {
        String resolvedUrl = normalizeRtspUrl(rtspUrl);
        if (resolvedUrl == null) {
            Log.w(TAG, "RTSP 地址为空，无法启动播放");
            return;
        }

        currentRtspUrl = resolvedUrl;
        pendingForceUseTcp = forceUseTcp;
        shouldPlayWhenSurfaceReady = true;
        bindProcessToWifiNetworkIfAvailable(context);
        bindSurfaceView(surfaceView);
        startNativeIfPossible();
    }

    /**
     * 暂停当前播放。
     *
     * <p>当前 FFmpeg 实现没有单独暂停能力，统一映射为停止拉流。</p>
     */
    public synchronized void pause() {
        stop();
    }

    /**
     * 恢复当前播放。
     *
     * <p>当前 FFmpeg 实现由上层负责重启会话，因此此方法仅在已有可播放意图时重试启动。</p>
     */
    public synchronized void resume() {
        startNativeIfPossible();
    }

    /**
     * 停止当前播放并断开视频输出。
     */
    public synchronized void stop() {
        shouldPlayWhenSurfaceReady = false;
        if (nativeHandle != 0L) {
            nativeStop(nativeHandle);
        }
        Log.i(TAG, "RTSP 播放已停止");
    }

    /**
     * 释放播放器资源并解除与界面的绑定。
     */
    public synchronized void release() {
        shouldPlayWhenSurfaceReady = false;
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0L;
        }
        unbindSurfaceView();
        Log.i(TAG, "RTSP 播放器资源已释放");
    }

    /**
     * 注册解码帧回调，用于在不依赖 Surface 渲染的场景下做画面分析。
     *
     * @param callback 帧回调；传 {@code null} 表示清除
     */
    public synchronized void setFrameCallback(@Nullable FrameCallback callback) {
        ensureNativeHandle();
        nativeSetFrameCallback(nativeHandle, callback);
    }

    /**
     * 清除已注册的解码帧回调。
     */
    public synchronized void clearFrameCallback() {
        if (nativeHandle != 0L) {
            nativeClearFrameCallback(nativeHandle);
        }
    }

    /**
     * 以无 Surface 模式启动 RTSP 拉流，仅通过 {@link #setFrameCallback(FrameCallback)} 回调帧。
     *
     * @param context 上下文
     * @param rtspUrl 完整 RTSP 地址
     * @param forceUseTcp {@code true} 表示强制 RTSP over TCP
     */
    public synchronized void startHeadless(@NonNull Context context,
                                           @Nullable String rtspUrl,
                                           boolean forceUseTcp) {
        String resolvedUrl = normalizeRtspUrl(rtspUrl);
        if (resolvedUrl == null) {
            Log.w(TAG, "RTSP 地址为空，无法启动 headless 拉流");
            return;
        }
        currentRtspUrl = resolvedUrl;
        pendingForceUseTcp = forceUseTcp;
        bindProcessToWifiNetworkIfAvailable(context);
        ensureNativeHandle();
        nativeStop(nativeHandle);
        nativeStart(nativeHandle, currentRtspUrl, null,
                currentUsername, currentPassword, pendingForceUseTcp);
        Log.i(TAG, "RTSP headless 拉流已启动: url=" + maskRtspUrl(currentRtspUrl)
                + ", forceUseTcp=" + pendingForceUseTcp);
    }

    /**
     * 判断当前是否处于正在播放状态。
     *
     * @return {@code true} 表示正在播放
     */
    public synchronized boolean isPlaying() {
        return nativeHandle != 0L && nativeIsPlaying(nativeHandle);
    }

    /**
     * 当前是否已经创建视频输出。
     *
     * @return {@code true} 表示 native 层已经成功渲染过视频帧
     */
    public synchronized boolean hasVideoOutput() {
        return nativeHandle != 0L && nativeHasVideoOutput(nativeHandle);
    }

    /**
     * 当前播放过程是否已经遇到错误。
     *
     * @return {@code true} 表示最近一次播放过程中出现错误
     */
    public synchronized boolean hasPlaybackError() {
        return nativeHandle != 0L && nativeHasPlaybackError(nativeHandle);
    }

    /**
     * 绑定视频输出视图。
     *
     * @param surfaceView 视频输出视图
     */
    private void bindSurfaceView(@NonNull SurfaceView surfaceView) {
        if (boundSurfaceView == surfaceView) {
            return;
        }

        unbindSurfaceView();
        boundSurfaceView = surfaceView;
        ensureSurfaceCallback();
        boundSurfaceView.getHolder().addCallback(surfaceCallback);
        Surface surface = boundSurfaceView.getHolder().getSurface();
        surfaceReady = surface != null && surface.isValid();
    }

    /**
     * 解除与当前 SurfaceView 的绑定。
     */
    private void unbindSurfaceView() {
        if (boundSurfaceView != null && surfaceCallback != null) {
            boundSurfaceView.getHolder().removeCallback(surfaceCallback);
        }
        boundSurfaceView = null;
        surfaceReady = false;
    }

    /**
     * 确保 Surface 回调已创建。
     */
    private void ensureSurfaceCallback() {
        if (surfaceCallback != null) {
            return;
        }

        surfaceCallback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                synchronized (CameraVideoByRtsp.this) {
                    surfaceReady = true;
                    Log.i(TAG, "RTSP Surface 已创建");
                    startNativeIfPossible();
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                synchronized (CameraVideoByRtsp.this) {
                    surfaceReady = holder.getSurface().isValid();
                    Log.i(TAG, "RTSP Surface 已更新: size=" + width + "x" + height);
                }
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                synchronized (CameraVideoByRtsp.this) {
                    surfaceReady = false;
                    if (nativeHandle != 0L) {
                        nativeStop(nativeHandle);
                    }
                    Log.i(TAG, "RTSP Surface 已销毁");
                }
            }
        };
    }

    /**
     * 在 Surface 可用时启动 native RTSP 拉流。
     */
    private void startNativeIfPossible() {
        if (!shouldPlayWhenSurfaceReady) {
            return;
        }
        if (boundSurfaceView == null) {
            Log.w(TAG, "RTSP 视图为空，无法启动 native 播放");
            return;
        }

        Surface surface = boundSurfaceView.getHolder().getSurface();
        if (!surfaceReady || surface == null || !surface.isValid()) {
            Log.i(TAG, "RTSP Surface 尚未可用，等待重试");
            return;
        }

        ensureNativeHandle();
        nativeStop(nativeHandle);
        boolean started = nativeStart(
                nativeHandle,
                currentRtspUrl,
                surface,
                currentUsername,
                currentPassword,
                pendingForceUseTcp
        );
        if (!started) {
            Log.e(TAG, "RTSP native 启动失败: url=" + maskRtspUrl(currentRtspUrl));
            return;
        }

        Log.i(TAG, "RTSP 播放已启动: url=" + maskRtspUrl(currentRtspUrl)
                + ", forceUseTcp=" + pendingForceUseTcp);
    }

    /**
     * 确保 native 播放器句柄已创建。
     */
    private void ensureNativeHandle() {
        if (nativeHandle == 0L) {
            nativeHandle = nativeCreate();
        }
    }

    /**
     * 将当前进程尽量绑定到可用的 WiFi 网络，避免系统继续使用蜂窝网络作为默认出口。
     *
     * @param context 上下文
     */
    private void bindProcessToWifiNetworkIfAvailable(@NonNull Context context) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return;
        }

        Network[] networks = connectivityManager.getAllNetworks();
        for (Network network : networks) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue;
            }

            boolean bindResult = connectivityManager.bindProcessToNetwork(network);
            Log.i(TAG, "RTSP 网络绑定: wifiNetwork=" + network + ", bindResult=" + bindResult);
            return;
        }
    }

    /**
     * 规范化 RTSP 地址。
     *
     * @param rtspUrl 待处理的 RTSP 地址
     * @return 可用的 RTSP 地址；如果为空则返回 {@code null}
     */
    @Nullable
    private String normalizeRtspUrl(@Nullable String rtspUrl) {
        if (rtspUrl == null) {
            return currentRtspUrl;
        }

        String resolvedUrl = rtspUrl.trim();
        if (resolvedUrl.isEmpty()) {
            return null;
        }

        return resolvedUrl;
    }

    /**
     * 根据 IP 拼接 RTSP 地址。
     *
     * @param cameraIp 摄像头 IP 地址
     * @return 完整 RTSP 地址
     */
    @NonNull
    private String buildRtspEndpoint(@NonNull String cameraIp) {
        return "rtsp://" + cameraIp + ":" + DEFAULT_RTSP_PORT + DEFAULT_RTSP_PATH;
    }

    /**
     * 从 RTSP 地址中提取 IP 或主机名。
     *
     * @param rtspUrl 完整 RTSP 地址
     * @return 主机部分；解析失败时回退默认 IP
     */
    @NonNull
    private String extractIpFromRtspUrl(@Nullable String rtspUrl) {
        if (rtspUrl == null || rtspUrl.trim().isEmpty()) {
            return DEFAULT_CAMERA_IP;
        }

        Uri uri = Uri.parse(rtspUrl);
        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) {
            return DEFAULT_CAMERA_IP;
        }
        return host.trim();
    }

    /**
     * 对日志中的 RTSP 地址做脱敏处理，避免明文输出密码。
     *
     * @param rtspUrl 完整 RTSP 地址
     * @return 脱敏后的 RTSP 地址
     */
    @NonNull
    private String maskRtspUrl(@Nullable String rtspUrl) {
        if (rtspUrl == null || rtspUrl.trim().isEmpty()) {
            return "";
        }

        Uri uri = Uri.parse(rtspUrl);
        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) {
            return rtspUrl;
        }
        return "rtsp://" + host + ":" + DEFAULT_RTSP_PORT + DEFAULT_RTSP_PATH;
    }

    private native long nativeCreate();

    private native void nativeDestroy(long nativeHandle);

    private native boolean nativeStart(long nativeHandle,
                                       @NonNull String rtspUrl,
                                       @NonNull Surface surface,
                                       @Nullable String username,
                                       @Nullable String password,
                                       boolean forceUseTcp);

    private native void nativeStop(long nativeHandle);

    private native boolean nativeIsPlaying(long nativeHandle);

    private native boolean nativeHasVideoOutput(long nativeHandle);

    private native boolean nativeHasPlaybackError(long nativeHandle);

    private native void nativeSetFrameCallback(long nativeHandle, FrameCallback callback);

    private native void nativeClearFrameCallback(long nativeHandle);
}
