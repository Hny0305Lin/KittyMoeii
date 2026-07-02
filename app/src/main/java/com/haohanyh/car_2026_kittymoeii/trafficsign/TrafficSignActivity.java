package com.haohanyh.car_2026_kittymoeii.trafficsign;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.haohanyh.car_2026_kittymoeii.Camera.CameraSearchIP;
import com.haohanyh.car_2026_kittymoeii.Camera.CameraVideoByRtsp;
import com.haohanyh.car_2026_kittymoeii.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class TrafficSignActivity extends AppCompatActivity {

    private static final String TAG = "TrafficSignActivity";
    private static final String DEFAULT_CAMERA_IP = "192.168.31.100";
    private static final long HTTP_POLLING_INTERVAL_MS = 400L;
    private static final long RTSP_FRAME_TIMEOUT_MS = 4000L;
    private static final long RTSP_WATCHDOG_INTERVAL_MS = 1000L;

    private enum PreviewMode {
        RTSP,
        HTTP
    }

    private ImageView previewImage;
    private DetectionOverlayView overlay;
    private TextView statusText;
    private TextView engineText;
    private Button modeButton;

    private final CameraSearchIP cameraGateway = CameraSearchIP.ManageCamera();
    // 使用独立实例，避免与 MainActivity 共用单例导致 Activity 切换时互相销毁播放器。
    private final CameraVideoByRtsp rtspVideoGateway = new CameraVideoByRtsp();
    // 不能在字段初始化时 new（此时 Activity 的 Context 尚未 attach），改为在 onCreate 中创建。
    private TrafficSignRecognizer recognizer;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService pollingExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean processing = new AtomicBoolean(false);

    private final CameraVideoByRtsp.FrameCallback frameCallback =
            (rgba, width, height) -> {
                Bitmap bitmap = ImageUtils.rgbaToBitmap(rgba, width, height);
                lastFrameAtMs = SystemClock.elapsedRealtime();
                processFrame(bitmap);
            };

    private volatile PreviewMode currentMode = PreviewMode.RTSP;
    private volatile boolean running = false;
    private volatile long httpGeneration = 0L;
    private volatile String cachedCameraIp = "";
    private volatile boolean shouldTryDefaultIp = true;
    private volatile long lastFrameAtMs = 0L;
    private volatile boolean rtspAutoRetried = false;
    private Bitmap lastBitmap = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_traffic_sign);

        previewImage = findViewById(R.id.signPreviewImage);
        overlay = findViewById(R.id.signOverlay);
        statusText = findViewById(R.id.signStatusText);
        engineText = findViewById(R.id.signEngineText);
        modeButton = findViewById(R.id.signModeButton);

        // 此时 Activity 已 attach Context，getApplicationContext() 安全可用。
        recognizer = new TrafficSignRecognizer(getApplicationContext());

        findViewById(R.id.signBackButton).setOnClickListener(v -> finish());
        findViewById(R.id.signRefreshButton).setOnClickListener(v -> restartCurrentMode());
        modeButton.setOnClickListener(v -> switchMode(currentMode == PreviewMode.RTSP
                ? PreviewMode.HTTP : PreviewMode.RTSP));

        engineText.setText(recognizer.isReady()
                ? getString(R.string.traffic_sign_engine_waiting)
                : getString(R.string.traffic_sign_status_model_missing));
        updateModeButton();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startMode(currentMode);
    }

    @Override
    protected void onStop() {
        stopMode();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        analysisExecutor.shutdownNow();
        pollingExecutor.shutdownNow();
        mainHandler.removeCallbacks(watchdog);
        if (recognizer != null) {
            recognizer.release();
        }
        if (lastBitmap != null && !lastBitmap.isRecycled()) {
            lastBitmap.recycle();
            lastBitmap = null;
        }
    }

    private void startMode(PreviewMode mode) {
        running = true;
        if (mode == PreviewMode.RTSP) {
            startRtspMode();
        } else {
            startHttpMode();
        }
        mainHandler.postDelayed(watchdog, RTSP_WATCHDOG_INTERVAL_MS);
    }

    private void stopMode() {
        running = false;
        httpGeneration++;
        mainHandler.removeCallbacks(watchdog);
        if (currentMode == PreviewMode.RTSP) {
            rtspVideoGateway.clearFrameCallback();
            rtspVideoGateway.release();
        }
        runOnUiThread(() -> overlay.clearDetections());
    }

    private void switchMode(PreviewMode newMode) {
        if (newMode == currentMode && running) {
            return;
        }
        stopMode();
        currentMode = newMode;
        rtspAutoRetried = false;
        updateModeButton();
        startMode(newMode);
    }

    private void restartCurrentMode() {
        analysisExecutor.execute(() -> {
            stopMode();
            rtspAutoRetried = false;
            startMode(currentMode);
            runOnUiThread(() -> Toast.makeText(this,
                    getString(R.string.traffic_sign_status_searching),
                    Toast.LENGTH_SHORT).show());
        });
    }

    private void startRtspMode() {
        String cameraIp = resolveCameraIp();
        if (cameraIp == null || cameraIp.trim().isEmpty()) {
            updateStatus(getString(R.string.traffic_sign_status_no_camera));
            return;
        }
        rtspVideoGateway.setRtspIp(cameraIp.trim());
        updateStatus("正在连接 RTSP…");
        lastFrameAtMs = SystemClock.elapsedRealtime();
        rtspVideoGateway.startHeadless(this, rtspVideoGateway.getRtspUrl(), false);
        rtspVideoGateway.setFrameCallback(frameCallback);
    }

    private void startHttpMode() {
        updateStatus(getString(R.string.traffic_sign_status_searching));
        final long generation = ++httpGeneration;
        lastFrameAtMs = SystemClock.elapsedRealtime();
        pollingExecutor.execute(() -> {
            while (running && generation == httpGeneration
                    && !Thread.currentThread().isInterrupted()) {
                String cameraIp = resolveCameraIp();
                if (cameraIp == null || cameraIp.trim().isEmpty()) {
                    updateStatus(getString(R.string.traffic_sign_status_no_camera));
                    sleepQuietly(800);
                    continue;
                }

                Bitmap bitmap;
                try {
                    bitmap = cameraGateway.getphoto(cameraIp.trim());
                } catch (Exception e) {
                    Log.w(TAG, "拉取摄像头快照失败", e);
                    bitmap = null;
                }
                if (bitmap == null) {
                    if (cameraIp.equals(DEFAULT_CAMERA_IP.trim())) {
                        shouldTryDefaultIp = false;
                    }
                    cachedCameraIp = "";
                    sleepQuietly(400);
                    continue;
                }
                lastFrameAtMs = SystemClock.elapsedRealtime();
                processFrame(bitmap);
                sleepQuietly(HTTP_POLLING_INTERVAL_MS);
            }
        });
    }

    private void processFrame(final Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        if (!processing.compareAndSet(false, true)) {
            bitmap.recycle();
            return;
        }
        analysisExecutor.execute(() -> {
            try {
                final InferenceModels.FrameAnalysisResult result = recognizer.analyze(bitmap);
                runOnUiThread(() -> {
                    statusText.setText(result.statusMessage);
                    previewImage.setImageBitmap(bitmap);
                    if (lastBitmap != null && lastBitmap != bitmap && !lastBitmap.isRecycled()) {
                        lastBitmap.recycle();
                    }
                    lastBitmap = bitmap;
                    overlay.setDetections(result.detections, result.frameWidth, result.frameHeight);
                    updateEngine();
                });
            } finally {
                processing.set(false);
            }
        });
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            if (currentMode == PreviewMode.RTSP) {
                if (rtspVideoGateway.hasPlaybackError()) {
                    if (!rtspAutoRetried) {
                        rtspAutoRetried = true;
                        updateStatus("RTSP 拉流异常，自动重试一次…");
                        restartCurrentMode();
                    } else {
                        updateStatus("RTSP 拉流异常，请点击刷新重试");
                    }
                } else if (SystemClock.elapsedRealtime() - lastFrameAtMs > RTSP_FRAME_TIMEOUT_MS) {
                    updateStatus("RTSP 暂无画面，等待视频流…");
                }
            }
            mainHandler.postDelayed(this, RTSP_WATCHDOG_INTERVAL_MS);
        }
    };

    private void updateEngine() {
        if (!recognizer.isReady()) {
            engineText.setText(getString(R.string.traffic_sign_status_model_missing));
            return;
        }
        int classCount = 0;
        try {
            int[] shape = recognizer.getClassCount();
            classCount = shape.length > 0 ? shape[shape.length - 1] : 0;
        } catch (Throwable ignored) {
        }
        engineText.setText(getString(R.string.traffic_sign_engine_format, classCount));
    }

    private String resolveCameraIp() {
        String resolved = cachedCameraIp == null ? "" : cachedCameraIp.trim();
        if (resolved.isEmpty() && shouldTryDefaultIp && !DEFAULT_CAMERA_IP.trim().isEmpty()) {
            resolved = DEFAULT_CAMERA_IP.trim();
            cameraGateway.setCameraIP(resolved);
        }
        if (resolved.isEmpty()) {
            try {
                resolved = cameraGateway.send();
            } catch (Exception e) {
                Log.w(TAG, "搜索摄像头失败", e);
                resolved = "";
            }
        }
        cachedCameraIp = resolved == null ? "" : resolved.trim();
        return cachedCameraIp;
    }

    private void updateModeButton() {
        modeButton.setText("模式：" + (currentMode == PreviewMode.RTSP ? "RTSP" : "HTTP"));
    }

    private void updateStatus(String message) {
        runOnUiThread(() -> statusText.setText(message));
    }

    private void sleepQuietly(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}