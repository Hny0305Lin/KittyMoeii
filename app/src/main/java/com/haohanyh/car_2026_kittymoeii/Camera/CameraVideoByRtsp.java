package com.haohanyh.car_2026_kittymoeii.Camera;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

/**
 * 摄像头 RTSP 串流播放器封装。
 *
 * <p>该类用于在 Android 端播放支持 RTSP 的摄像头视频流，默认串流地址为
 * {@code rtsp://192.168.31.100:10554/udp/av0_0}。当前实现基于 LibVLC，
 * 默认优先使用 RTP over UDP，与设备原始串流方式保持一致。</p>
 *
 * <p>职责边界：</p>
 * <p>1. 管理 RTSP 地址、LibVLC 实例和界面绑定关系。</p>
 * <p>2. 对外提供启动、暂停、停止、释放以及切换传输方式等能力。</p>
 * <p>3. 统一封装 VLCVideoLayout 绑定与 RTSP 媒体选项配置逻辑。</p>
 */
public class CameraVideoByRtsp {
    private static final String TAG = "CameraVideoByRtsp";
    private static final String DEFAULT_CAMERA_IP = "192.168.31.100";
    private static final String DEFAULT_RTSP_USERNAME = "admin";
    private static final String DEFAULT_RTSP_PASSWORD = "888888";
    private static final int DEFAULT_RTSP_PORT = 10554;
    private static final String DEFAULT_RTSP_PATH = "/udp/av0_0";
    private static final int DEFAULT_NETWORK_CACHING_MS = 600;
    private static final int DEFAULT_LIVE_CACHING_MS = 600;

    private static volatile CameraVideoByRtsp cameraVideoByRtsp;

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private VLCVideoLayout boundVideoLayout;
    private boolean viewsAttached = false;
    private boolean hasVideoOutput = false;
    private boolean hasPlaybackError = false;
    private int debugSessionId = 0;
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
        currentRtspUrl = buildRtspEndpoint(cameraIp == null ? DEFAULT_CAMERA_IP : cameraIp.trim().isEmpty() ? DEFAULT_CAMERA_IP : cameraIp.trim());
    }

    /**
     * 设置当前调试会话编号，便于对齐 Activity 与播放器日志。
     *
     * @param sessionId 当前 RTSP 会话编号
     */
    public synchronized void setDebugSessionId(int sessionId) {
        debugSessionId = sessionId;
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
     * @param videoLayout VLC 视频视图
     */
    public synchronized void start(@NonNull Context context, @NonNull VLCVideoLayout videoLayout) {
        start(context, videoLayout, currentRtspUrl, false);
    }

    /**
     * 使用指定 RTSP 地址启动播放，默认走 RTP over UDP。
     *
     * @param context 上下文
     * @param videoLayout VLC 视频视图
     * @param rtspUrl 完整 RTSP 地址
     */
    public synchronized void start(@NonNull Context context,
                                   @NonNull VLCVideoLayout videoLayout,
                                   @Nullable String rtspUrl) {
        start(context, videoLayout, rtspUrl, false);
    }

    /**
     * 启动 RTSP 播放。
     *
     * @param context 上下文
     * @param videoLayout VLC 视频视图
     * @param rtspUrl 完整 RTSP 地址
     * @param forceUseTcp {@code true} 表示强制 RTP over TCP，{@code false} 表示优先使用 UDP
     */
    public synchronized void start(@NonNull Context context,
                                   @NonNull VLCVideoLayout videoLayout,
                                   @Nullable String rtspUrl,
                                   boolean forceUseTcp) {
        String resolvedUrl = normalizeRtspUrl(rtspUrl);
        if (resolvedUrl == null) {
            Log.w(TAG, "RTSP 地址为空，无法启动播放");
            return;
        }

        currentRtspUrl = resolvedUrl;
        hasVideoOutput = false;
        hasPlaybackError = false;
        bindProcessToWifiNetworkIfAvailable(context);
        bindVideoLayout(videoLayout);
        MediaPlayer player = ensurePlayer(context);
        attachViewsIfNeeded();

        Media media = buildMedia(currentRtspUrl, forceUseTcp);
        player.setMedia(media);
        media.release();
        player.play();

        Log.i(TAG, "RTSP 播放已启动: sessionId=" + debugSessionId
                + ", url=" + maskRtspUrl(currentRtspUrl)
                + ", forceUseTcp=" + forceUseTcp);
    }

    /**
     * 暂停当前播放。
     */
    public synchronized void pause() {
        if (mediaPlayer != null) {
            mediaPlayer.pause();
        }
    }

    /**
     * 恢复当前播放。
     */
    public synchronized void resume() {
        if (mediaPlayer != null) {
            mediaPlayer.play();
        }
    }

    /**
     * 停止当前播放并断开视频输出。
     */
    public synchronized void stop() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
        }
        detachViewsIfNeeded();
        hasVideoOutput = false;
        hasPlaybackError = false;
        Log.i(TAG, "RTSP 播放已停止: sessionId=" + debugSessionId);
    }

    /**
     * 释放播放器资源并解除与界面的绑定。
     */
    public synchronized void release() {
        detachViewsIfNeeded();

        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
        boundVideoLayout = null;
        hasVideoOutput = false;
        hasPlaybackError = false;
        Log.i(TAG, "RTSP 播放器资源已释放: sessionId=" + debugSessionId);
    }

    /**
     * 判断当前是否处于正在播放状态。
     *
     * @return {@code true} 表示正在播放
     */
    public synchronized boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    /**
     * 当前是否已经创建视频输出。
     *
     * @return {@code true} 表示 VLC 已创建 vout
     */
    public synchronized boolean hasVideoOutput() {
        return hasVideoOutput;
    }

    /**
     * 当前播放过程是否已经遇到错误。
     *
     * @return {@code true} 表示最近一次播放过程中出现错误
     */
    public synchronized boolean hasPlaybackError() {
        return hasPlaybackError;
    }

    /**
     * 获取当前底层播放器实例。
     *
     * @return MediaPlayer 实例；如果尚未初始化则返回 {@code null}
     */
    @Nullable
    public synchronized MediaPlayer getPlayer() {
        return mediaPlayer;
    }

    /**
     * 绑定视频输出视图。
     *
     * @param videoLayout VLC 视频视图
     */
    private void bindVideoLayout(@NonNull VLCVideoLayout videoLayout) {
        if (boundVideoLayout != videoLayout) {
            detachViewsIfNeeded();
            boundVideoLayout = videoLayout;
        }
    }

    /**
     * 确保 LibVLC 和 MediaPlayer 实例已初始化。
     *
     * @param context 上下文
     * @return 可用的 VLC 播放器实例
     */
    @NonNull
    private MediaPlayer ensurePlayer(@NonNull Context context) {
        if (libVLC == null) {
            ArrayList<String> options = new ArrayList<>();
            options.add("--verbose=2");
            libVLC = new LibVLC(context.getApplicationContext(), options);
        }

        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer(libVLC);
            mediaPlayer.setEventListener(this::handlePlayerEvent);
        }
        return mediaPlayer;
    }

    /**
     * 构建当前播放所需的 VLC 媒体对象。
     *
     * @param player VLC 播放器
     * @param rtspUrl 完整 RTSP 地址
     * @param forceUseTcp 是否强制使用 RTSP over TCP
     * @return 已配置好的媒体对象
     */
    @NonNull
    private Media buildMedia(@NonNull String rtspUrl,
                             boolean forceUseTcp) {
        Media media = new Media(libVLC, Uri.parse(rtspUrl));
        media.setHWDecoderEnabled(false, false);
        media.addOption(":network-caching=" + DEFAULT_NETWORK_CACHING_MS);
        media.addOption(":live-caching=" + DEFAULT_LIVE_CACHING_MS);
        media.addOption(":clock-jitter=5000");
        media.addOption(":clock-synchro=1");
        media.addOption(":ipv4");
        media.addOption(":no-audio");
        if (!currentUsername.isEmpty()) {
            media.addOption(":rtsp-user=" + currentUsername);
            media.addOption(":rtsp-pwd=" + currentPassword);
        }
        if (forceUseTcp) {
            media.addOption(":rtsp-tcp");
        }
        return media;
    }

    /**
     * 绑定 VLC 视频输出到界面。
     */
    private void attachViewsIfNeeded() {
        if (mediaPlayer == null || boundVideoLayout == null || viewsAttached) {
            return;
        }
        mediaPlayer.attachViews(boundVideoLayout, null, false, false);
        viewsAttached = true;
    }

    /**
     * 解除 VLC 视频输出与界面的绑定。
     */
    private void detachViewsIfNeeded() {
        if (mediaPlayer == null || !viewsAttached) {
            return;
        }
        mediaPlayer.detachViews();
        viewsAttached = false;
    }

    /**
     * 处理 VLC 播放器事件并输出可读日志。
     *
     * @param event VLC 播放事件
     */
    private void handlePlayerEvent(@Nullable MediaPlayer.Event event) {
        if (event == null) {
            return;
        }

        switch (event.type) {
            case MediaPlayer.Event.Opening:
                Log.i(TAG, "RTSP 播放状态: sessionId=" + debugSessionId + ", state=OPENING");
                break;
            case MediaPlayer.Event.Buffering:
                Log.i(TAG, "RTSP 播放状态: sessionId=" + debugSessionId + ", state=BUFFERING");
                break;
            case MediaPlayer.Event.Playing:
                Log.i(TAG, "RTSP 播放状态: sessionId=" + debugSessionId + ", state=PLAYING");
                break;
            case MediaPlayer.Event.Paused:
                Log.i(TAG, "RTSP 播放状态: sessionId=" + debugSessionId + ", state=PAUSED");
                break;
            case MediaPlayer.Event.Stopped:
                Log.i(TAG, "RTSP 播放状态: sessionId=" + debugSessionId + ", state=STOPPED");
                hasVideoOutput = false;
                break;
            case MediaPlayer.Event.EndReached:
                Log.i(TAG, "RTSP 播放状态: sessionId=" + debugSessionId + ", state=END_REACHED");
                hasVideoOutput = false;
                break;
            case MediaPlayer.Event.EncounteredError:
                Log.e(TAG, "RTSP 播放异常: sessionId=" + debugSessionId + ", state=ENCOUNTERED_ERROR");
                hasPlaybackError = true;
                break;
            case MediaPlayer.Event.Vout:
                Log.i(TAG, "RTSP 已绑定视频输出: sessionId=" + debugSessionId);
                hasVideoOutput = true;
                break;
            default:
                break;
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

}
