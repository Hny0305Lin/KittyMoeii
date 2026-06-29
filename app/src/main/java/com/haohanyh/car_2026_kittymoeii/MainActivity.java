package com.haohanyh.car_2026_kittymoeii;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.haohanyh.car_2026_kittymoeii.Car.CarIPBean;
import com.haohanyh.car_2026_kittymoeii.Car.CarLink;
import com.haohanyh.car_2026_kittymoeii.Car.CarState;
import com.haohanyh.car_2026_kittymoeii.Camera.CameraSearchIP;
import com.haohanyh.car_2026_kittymoeii.Camera.CameraVideoByRtsp;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements CarLink.INFO {
    private static final String TAG = "MainActivity";
    private static final boolean USE_RTSP_STREAM = true;
    private static final boolean FORCE_RTSP_INTERLEAVED_TCP = false;
    // 先尝试固定 IP，连不上时再回退到 UDP 搜索。
    private static final String DEFAULT_CAMERA_IP = "192.168.31.100";
    private static final String DEFAULT_CAR_IP = DEFAULT_CAMERA_IP;
    private static final long CAMERA_REFRESH_INTERVAL_MS = 120L;
    private static final long RTSP_RETRY_INTERVAL_MS = 2500L;
    private static final long RTSP_START_TIMEOUT_MS = 10000L;
    private static final long RTSP_HEALTH_CHECK_INTERVAL_MS = 800L;

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger rtspSessionCounter = new AtomicInteger(0);
    private final CameraSearchIP cameraGateway = CameraSearchIP.ManageCamera();
    private final CameraVideoByRtsp rtspVideoGateway = CameraVideoByRtsp.ManageCamera();
    private ImageView carImage;
    private SurfaceView rtspPlayerView;
    private volatile boolean previewRunning = false;
    private volatile boolean previewTaskSubmitted = false;
    private String cachedCameraIp = "";
    private boolean shouldTryDefaultCameraIp = true;
    private boolean carLinkStarted = false;
    private boolean hasShownSearchFailToast = false;
    private boolean hasShownLoadFailToast = false;
    private volatile int activeRtspSessionId = 0;
    private volatile boolean rtspSessionStarting = false;
    private volatile long rtspSessionStartAtMs = 0L;
    private volatile boolean rtspUseTcpForNextSession = FORCE_RTSP_INTERLEAVED_TCP;

    /**
     * 初始化页面、绑定视图并设置基础交互。
     *
     * @param savedInstanceState Activity 重建时保存的状态
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        carImage = findViewById(R.id.carImage);
        rtspPlayerView = findViewById(R.id.rtspPlayerView);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        carImage.setOnClickListener(v -> restartCameraPreview());
        rtspPlayerView.setOnClickListener(v -> restartCameraPreview());
        applyPreviewMode();
        Toast.makeText(this, R.string.camera_refresh_hint, Toast.LENGTH_SHORT).show();
    }

    /**
     * 页面进入前台时启动摄像头预览和小车 Socket 监听。
     */
    @Override
    protected void onStart() {
        super.onStart();
        startPreview();
        startCarSocketLink();
    }

    /**
     * 页面离开前台时停止摄像头预览循环。
     */
    @Override
    protected void onStop() {
        stopPreview();
        super.onStop();
    }

    /**
     * 根据当前模式启动摄像头预览。
     *
     * <p>当 {@link #USE_RTSP_STREAM} 为 {@code true} 时，使用 RTSP 串流；否则使用
     * HTTP 快照轮询。</p>
     */
    private void startPreview() {
        if (USE_RTSP_STREAM) {
            startRtspPreview();
        } else {
            startCameraPreviewLoop();
        }
    }

    /**
     * 停止当前模式下的摄像头预览。
     */
    private void stopPreview() {
        if (USE_RTSP_STREAM) {
            stopRtspPreview();
        } else {
            stopCameraPreviewLoop();
        }
    }

    /**
     * 按当前布尔开关应用预览视图显示状态。
     */
    private void applyPreviewMode() {
        carImage.setVisibility(USE_RTSP_STREAM ? View.GONE : View.VISIBLE);
        rtspPlayerView.setVisibility(USE_RTSP_STREAM ? View.VISIBLE : View.GONE);
        setImageEnabled(true);
    }

    /**
     * 启动摄像头 HTTP 快照轮询任务。
     *
     * <p>该方法会持续尝试解析摄像头 IP、拉取快照并刷新到页面顶部图像区域。</p>
     */
    private void startCameraPreviewLoop() {
        previewRunning = true;
        carImage.setEnabled(false);
        if (previewTaskSubmitted) {
            return;
        }

        previewTaskSubmitted = true;
        cameraExecutor.execute(() -> {
            while (previewRunning && !Thread.currentThread().isInterrupted()) {
                String cameraIp = resolveCameraIp();
                if (cameraIp == null || cameraIp.trim().isEmpty()) {
                    if (!hasShownSearchFailToast) {
                        hasShownSearchFailToast = true;
                        showToast(R.string.camera_search_failed);
                    }
                    sleepQuietly(800);
                    continue;
                }

                Bitmap bitmap = cameraGateway.getphoto(cameraIp);
                if (bitmap == null) {
                    if (cameraIp.equals(DEFAULT_CAMERA_IP.trim())) {
                        shouldTryDefaultCameraIp = false;
                    }
                    cachedCameraIp = "";
                    if (!hasShownLoadFailToast) {
                        hasShownLoadFailToast = true;
                        showToast(R.string.camera_load_failed);
                    }
                    sleepQuietly(400);
                    continue;
                }

                hasShownSearchFailToast = false;
                hasShownLoadFailToast = false;
                runOnUiThread(() -> {
                    carImage.setImageBitmap(bitmap);
                    carImage.setEnabled(true);
                });
                sleepQuietly(CAMERA_REFRESH_INTERVAL_MS);
            }
            previewTaskSubmitted = false;
        });
    }

    /**
     * 启动 RTSP 串流预览。
     *
     * <p>当前按设备原始流协议走 RTP over UDP；如果后续需要排查网络兼容性，
     * 可将 {@link #FORCE_RTSP_INTERLEAVED_TCP} 改为 {@code true} 强制切换到 TCP。</p>
     */
    private void startRtspPreview() {
        previewRunning = true;
        setImageEnabled(false);
        if (previewTaskSubmitted) {
            Log.d(TAG, "RTSP 预览启动请求被忽略: 轮询任务已提交");
            return;
        }

        previewTaskSubmitted = true;
        cameraExecutor.execute(() -> {
            Log.i(TAG, "RTSP 轮询线程已启动");
            while (previewRunning && !Thread.currentThread().isInterrupted()) {
                String cameraIp = resolveCameraIp();
                if (cameraIp == null || cameraIp.trim().isEmpty()) {
                    if (!hasShownSearchFailToast) {
                        hasShownSearchFailToast = true;
                        showToast(R.string.camera_search_failed);
                    }
                    sleepQuietly(800);
                    continue;
                }

                cachedCameraIp = cameraIp.trim();
                boolean rtspHealthy = rtspVideoGateway.isPlaying() || rtspVideoGateway.hasVideoOutput();
                boolean rtspErrored = rtspVideoGateway.hasPlaybackError();

                if (rtspSessionStarting) {
                    if (rtspHealthy && !rtspErrored) {
                        Log.i(TAG, "RTSP 会话已稳定，继续保活: sessionId=" + activeRtspSessionId
                                + ", playing=" + rtspVideoGateway.isPlaying()
                                + ", vout=" + rtspVideoGateway.hasVideoOutput());
                        hasShownSearchFailToast = false;
                        hasShownLoadFailToast = false;
                        setImageEnabled(true);
                        sleepQuietly(RTSP_HEALTH_CHECK_INTERVAL_MS);
                        continue;
                    }

                    long startElapsed = SystemClock.elapsedRealtime() - rtspSessionStartAtMs;
                    if (!rtspErrored && startElapsed < RTSP_START_TIMEOUT_MS) {
                        sleepQuietly(RTSP_HEALTH_CHECK_INTERVAL_MS);
                        continue;
                    }

                    boolean timedOutWithoutError = !rtspErrored && startElapsed >= RTSP_START_TIMEOUT_MS;
                    if (timedOutWithoutError && !rtspUseTcpForNextSession) {
                        rtspUseTcpForNextSession = true;
                        Log.w(TAG, "RTSP 启动超时，下一次改用 TCP 重试: sessionId=" + activeRtspSessionId);
                    }

                    Log.w(TAG, "RTSP 会话异常，准备重建: sessionId=" + activeRtspSessionId
                            + ", elapsedMs=" + startElapsed
                            + ", playing=" + rtspVideoGateway.isPlaying()
                            + ", vout=" + rtspVideoGateway.hasVideoOutput()
                            + ", error=" + rtspVideoGateway.hasPlaybackError()
                            + ", nextTcp=" + rtspUseTcpForNextSession);
                    rtspSessionStarting = false;
                    stopRtspSession(activeRtspSessionId, rtspErrored ? "playback-error" : "start-timeout");
                    if (!hasShownLoadFailToast) {
                        hasShownLoadFailToast = true;
                        showToast(R.string.camera_load_failed);
                    }
                    sleepQuietly(RTSP_RETRY_INTERVAL_MS);
                    continue;
                }

                int sessionId = rtspSessionCounter.incrementAndGet();
                activeRtspSessionId = sessionId;
                rtspSessionStarting = true;
                rtspSessionStartAtMs = SystemClock.elapsedRealtime();
                Log.i(TAG, "RTSP 准备启动会话: sessionId=" + sessionId
                        + ", ip=" + cachedCameraIp
                        + ", playing=" + rtspVideoGateway.isPlaying()
                        + ", vout=" + rtspVideoGateway.hasVideoOutput()
                        + ", error=" + rtspVideoGateway.hasPlaybackError());
                startRtspSession(cachedCameraIp, sessionId);
                sleepQuietly(RTSP_HEALTH_CHECK_INTERVAL_MS);
            }

            Log.i(TAG, "RTSP 轮询线程退出");
            rtspSessionStarting = false;
            stopRtspSession(activeRtspSessionId, "preview-loop-exit");
            previewTaskSubmitted = false;
        });
    }

    /**
     * 停止摄像头 HTTP 快照轮询。
     */
    private void stopCameraPreviewLoop() {
        previewRunning = false;
    }

    /**
     * 停止 RTSP 串流预览。
     */
    private void stopRtspPreview() {
        previewRunning = false;
        rtspSessionStarting = false;
        stopRtspSession(activeRtspSessionId, "stop-preview");
    }

    /**
     * 在主线程重建并启动一次 RTSP 会话。
     *
     * @param cameraIp 当前摄像头 IP
     */
    private void startRtspSession(String cameraIp, int sessionId) {
        runOnUiThread(() -> {
            Log.i(TAG, "RTSP 主线程启动会话: sessionId=" + sessionId + ", ip=" + cameraIp);
            rtspVideoGateway.release();
            rtspVideoGateway.setRtspIp(cameraIp);
            rtspVideoGateway.setDebugSessionId(sessionId);
            rtspVideoGateway.start(
                    this,
                    rtspPlayerView,
                    rtspVideoGateway.getRtspUrl(),
                    rtspUseTcpForNextSession
            );
        });
    }

    /**
     * 在主线程停止当前 RTSP 会话并释放播放器资源。
     */
    private void stopRtspSession(int sessionId, String reason) {
        runOnUiThread(() -> {
            Log.i(TAG, "RTSP 主线程停止会话: sessionId=" + sessionId + ", reason=" + reason);
            rtspVideoGateway.release();
        });
    }

    /**
     * 手动重启摄像头预览流程。
     *
     * <p>会清空缓存 IP、恢复固定 IP 优先策略，并触发一次重新加载。</p>
     */
    private void restartCameraPreview() {
        cachedCameraIp = "";
        shouldTryDefaultCameraIp = true;
        hasShownSearchFailToast = false;
        hasShownLoadFailToast = false;
        rtspSessionStarting = false;
        rtspUseTcpForNextSession = FORCE_RTSP_INTERLEAVED_TCP;
        Toast.makeText(this, R.string.camera_loading, Toast.LENGTH_SHORT).show();
        Log.i(TAG, "手动重启摄像头预览");
        stopPreview();
        startPreview();
    }

    /**
     * 启动小车 Socket 数据监听。
     *
     * <p>如果已经建立过监听，则不会重复启动；首次启动时会将当前小车 IP 写入
     * {@link CarIPBean}，并把当前 Activity 作为数据回调实现类传入。</p>
     */
    private void startCarSocketLink() {
        if (carLinkStarted) {
            return;
        }

        String carIp = resolveCarIp();
        if (carIp.isEmpty()) {
            Log.w(TAG, "小车 IP 为空，暂不启动 Socket 数据监听");
            return;
        }

        CarIPBean.NeedManageIP().IPCar = carIp;
        CarLink.getCarLink().getDataInfo(carIp, this);
        carLinkStarted = true;
        Log.i(TAG, "已启动小车 Socket 数据监听: " + carIp);
    }

    /**
     * 解析当前要使用的摄像头 IP。
     *
     * <p>优先使用缓存 IP；如果允许固定 IP 优先，则先尝试默认 IP；否则回退到
     * UDP 搜索结果。</p>
     *
     * @return 当前可用于拉取摄像头画面的 IP
     */
    private String resolveCameraIp() {
        if (!cachedCameraIp.trim().isEmpty()) {
            return cachedCameraIp.trim();
        }
        if (shouldTryDefaultCameraIp && !DEFAULT_CAMERA_IP.trim().isEmpty()) {
            cachedCameraIp = DEFAULT_CAMERA_IP.trim();
            cameraGateway.setCameraIP(cachedCameraIp);
            return cachedCameraIp;
        }
        cachedCameraIp = cameraGateway.send();
        return cachedCameraIp;
    }

    /**
     * 解析当前要使用的小车 Socket IP。
     *
     * @return 已保存的小车 IP；如果未保存则返回默认 IP
     */
    private String resolveCarIp() {
        String carIp = CarIPBean.NeedManageIP().IPCar;
        if (carIp != null && !carIp.trim().isEmpty()) {
            return carIp.trim();
        }
        return DEFAULT_CAR_IP.trim();
    }

    /**
     * 在主线程显示一个短 Toast 提示。
     *
     * @param messageResId 字符串资源 ID
     */
    private void showToast(int messageResId) {
        runOnUiThread(() -> Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show());
    }

    /**
     * 设置顶部摄像头图像区域是否允许点击。
     *
     * @param enabled {@code true} 表示允许交互，{@code false} 表示禁用
     */
    private void setImageEnabled(boolean enabled) {
        runOnUiThread(() -> {
            carImage.setEnabled(enabled);
            rtspPlayerView.setEnabled(enabled);
        });
    }

    /**
     * 接收并分发小车回传到 Android 端的数据帧。
     *
     * @param bytes 原始数据内容
     * @param whatcar 车辆类型标识，6 表示主车，8 表示从车
     * @param subscript 当前数据帧有效长度
     */
    @Override
    public void DataInfo(byte[] bytes, int whatcar, int subscript) {
        if (bytes == null || subscript <= 0) {
            Log.w(TAG, "收到空的小车数据帧");
            return;
        }

        switch (whatcar) {
            case 6:
                handleMainCarData(bytes, subscript);
                break;
            case 8:
                handleSlaveCarData(bytes, subscript);
                break;
            default:
                Log.w(TAG, "收到未知车辆类型数据: whatcar=" + whatcar + ", subscript=" + subscript);
                break;
        }
    }

    /**
     * 处理主车回传的数据帧。
     *
     * <p>当前优先支持状态帧解析：当收到 11 字节状态数据时，将码盘、光照、
     * 超声波和救援坐标写入 {@link CarState}。</p>
     *
     * @param bytes 主车原始数据
     * @param subscript 数据帧有效长度
     */
    private void handleMainCarData(byte[] bytes, int subscript) {
        switch (subscript) {
            case 8:
                if (bytes.length >= 4) {
                    Log.i(TAG, "收到主车 8 字节控制帧: type=0x"
                            + Integer.toHexString(bytes[2] & 0xFF).toUpperCase()
                            + ", param=0x"
                            + Integer.toHexString(bytes[3] & 0xFF).toUpperCase());
                }
                break;
            case 11:
                if (bytes.length >= 9) {
                    CarState.getCarState().setByte(Arrays.copyOfRange(bytes, 3, 9));
                    Log.i(TAG, "主车状态更新: codeDisk=" + CarState.getCarState().getCodeDisk()
                            + ", light=" + CarState.getCarState().getLight()
                            + ", ultraSonic=" + CarState.getCarState().getUltraSonic()
                            + ", rescue=" + CarState.getCarState().getJiuYuan());
                }
                break;
            case 20:
                if (bytes.length >= 3) {
                    Log.i(TAG, "收到主车 20 字节扩展帧: type=0x"
                            + Integer.toHexString(bytes[2] & 0xFF).toUpperCase());
                }
                break;
            default:
                Log.d(TAG, "收到主车数据: subscript=" + subscript + ", bytes=" + bytes.length);
                break;
        }
    }

    /**
     * 处理从车回传的数据帧。
     *
     * @param bytes 从车原始数据
     * @param subscript 数据帧有效长度
     */
    private void handleSlaveCarData(byte[] bytes, int subscript) {
        Log.d(TAG, "收到从车数据: subscript=" + subscript + ", bytes=" + bytes.length);
    }

    /**
     * 销毁页面并释放后台任务资源。
     */
    @Override
    protected void onDestroy() {
        previewRunning = false;
        rtspSessionStarting = false;
        Log.i(TAG, "页面销毁，释放 RTSP 资源");
        rtspVideoGateway.release();
        super.onDestroy();
        cameraExecutor.shutdownNow();
    }

    /**
     * 以安全方式执行线程休眠，避免中断异常向外抛出。
     *
     * @param delayMillis 休眠时长，单位毫秒
     */
    private void sleepQuietly(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
