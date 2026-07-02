package com.haohanyh.car_2026_kittymoeii.trafficsign;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import com.haohanyh.car_2026_kittymoeii.trafficsign.DemoModelConfig.ClassificationModelConfig;
import com.haohanyh.car_2026_kittymoeii.trafficsign.DemoModelConfig.DemoPipelineConfig;
import com.haohanyh.car_2026_kittymoeii.trafficsign.InferenceModels.ClassificationResult;
import com.haohanyh.car_2026_kittymoeii.trafficsign.InferenceModels.DetectionResult;
import com.haohanyh.car_2026_kittymoeii.trafficsign.InferenceModels.EngineClassificationResult;
import com.haohanyh.car_2026_kittymoeii.trafficsign.InferenceModels.FrameAnalysisResult;
import com.haohanyh.car_2026_kittymoeii.trafficsign.InferenceModels.InferenceEngine;
import com.haohanyh.car_2026_kittymoeii.trafficsign.InferenceModels.TaskSummary;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class TrafficSignRecognizer {

    private static final float MIN_DETECTION_SCORE = 0.45f;
    private static final int MAX_DETECTIONS = 24;

    private final TrafficSignCandidateDetector candidateDetector = new TrafficSignCandidateDetector();
    private final Context context;
    private final DemoPipelineConfig config;

    private Interpreter interpreter;
    private List<String> labels = Collections.emptyList();
    private boolean initialized = false;
    private boolean initFailed = false;

    public TrafficSignRecognizer(Context context) {
        this(context, DemoModelConfig.CURRENT);
    }

    public TrafficSignRecognizer(Context context, DemoPipelineConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
    }

    private synchronized void ensureInitialized() {
        if (initialized || initFailed) {
            return;
        }
        ClassificationModelConfig model = config.tfliteClassification;
        labels = AssetUtils.readLabels(context, model.labelAssetPath);
        try {
            if (!AssetUtils.assetExists(context, model.modelAssetPath)) {
                initFailed = true;
                return;
            }
            Interpreter.Options options = new Interpreter.Options();
            int cores = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
            options.setNumThreads(cores);
            interpreter = new Interpreter(loadMappedModel(context, model.modelAssetPath), options);
            initialized = true;
        } catch (Throwable ignored) {
            // 捕获 Throwable 以兼容 UnsatisfiedLinkError 等 Error，避免把 Activity 直接打挂。
            initFailed = true;
        }
    }

    public boolean isReady() {
        ensureInitialized();
        return initialized && interpreter != null;
    }

    public int[] getClassCount() {
        ensureInitialized();
        if (interpreter == null) {
            return new int[]{0};
        }
        return interpreter.getOutputTensor(0).shape();
    }

    public FrameAnalysisResult analyze(Bitmap bitmap) {
        if (bitmap == null) {
            return new FrameAnalysisResult(0, 0, Collections.<DetectionResult>emptyList(), "无画面");
        }
        try {
            List<DetectionResult> detections = detectTrafficSigns(bitmap);
            String message = detections.isEmpty()
                    ? "未发现交通标志"
                    : "已框选 " + detections.size() + " 个交通标志";
            return new FrameAnalysisResult(bitmap.getWidth(), bitmap.getHeight(), detections, message);
        } catch (Exception e) {
            return new FrameAnalysisResult(bitmap.getWidth(), bitmap.getHeight(),
                    Collections.<DetectionResult>emptyList(), "识别异常: " + e.getMessage());
        }
    }

    private List<DetectionResult> detectTrafficSigns(Bitmap bitmap) {
        if (!isReady()) {
            return Collections.emptyList();
        }
        List<TrafficSignCandidateDetector.CandidateRegion> regions = candidateDetector.detect(bitmap);
        if (regions.isEmpty()) {
            return Collections.emptyList();
        }

        List<DetectionResult> detections = new ArrayList<>();
        ClassificationModelConfig model = config.tfliteClassification;
        for (TrafficSignCandidateDetector.CandidateRegion region : regions) {
            Bitmap crop = ImageUtils.cropBitmap(bitmap, region.boundingBox);
            EngineClassificationResult result = classify(crop);
            crop.recycle();
            if (!result.summary.isReady || result.topResults.isEmpty()) {
                continue;
            }
            ClassificationResult top1 = result.topResults.get(0);
            if (top1.score < MIN_DETECTION_SCORE) {
                continue;
            }
            detections.add(new DetectionResult(
                    toDisplayLabel(top1.label),
                    top1.score,
                    region.boundingBox,
                    region.boxColor
            ));
        }
        Collections.sort(detections, new Comparator<DetectionResult>() {
            @Override
            public int compare(DetectionResult a, DetectionResult b) {
                return Float.compare(b.score, a.score);
            }
        });
        if (detections.size() > MAX_DETECTIONS) {
            return new ArrayList<>(detections.subList(0, MAX_DETECTIONS));
        }
        return detections;
    }

    private EngineClassificationResult classify(Bitmap bitmap) {
        if (!isReady() || interpreter == null) {
            return new EngineClassificationResult(
                    new TaskSummary(InferenceEngine.TFLITE, "TFLite 分类模型缺失", 0L, false),
                    Collections.<ClassificationResult>emptyList());
        }
        try {
            long startedAt = SystemClock.elapsedRealtime();
            ByteBuffer inputBuffer = createImageInputBuffer(bitmap, config.tfliteClassification);
            int classCount = interpreter.getOutputTensor(0).shape()[interpreter.getOutputTensor(0).shape().length - 1];
            if (classCount < 1) {
                classCount = 1;
            }
            float[][] output = new float[1][classCount];
            interpreter.run(inputBuffer, output);
            long latency = SystemClock.elapsedRealtime() - startedAt;
            return new EngineClassificationResult(
                    new TaskSummary(InferenceEngine.TFLITE, "TFLite 分类运行中", latency, true),
                    scoresToTopK(output[0], labels, config.tfliteClassification.topK));
        } catch (Throwable e) {
            return new EngineClassificationResult(
                    new TaskSummary(InferenceEngine.TFLITE, "TFLite 分类异常: " + e.getMessage(), 0L, false),
                    Collections.<ClassificationResult>emptyList());
        }
    }

    public void release() {
        synchronized (this) {
            if (interpreter != null) {
                try {
                    interpreter.close();
                } catch (Exception ignored) {
                }
                interpreter = null;
            }
            initialized = false;
            initFailed = false;
        }
    }

    private static String toDisplayLabel(String rawLabel) {
        if (rawLabel == null || rawLabel.trim().isEmpty()) {
            return "未命名标志";
        }
        if (rawLabel.startsWith("class_")) {
            String suffix = rawLabel.substring("class_".length());
            boolean allDigit = !suffix.isEmpty();
            for (int i = 0; i < suffix.length(); i++) {
                if (!Character.isDigit(suffix.charAt(i))) {
                    allDigit = false;
                    break;
                }
            }
            return allDigit ? "类别 " + suffix : "未命名标志";
        }
        return rawLabel;
    }

    private static MappedByteBuffer loadMappedModel(Context context, String assetPath) throws Exception {
        android.content.res.AssetFileDescriptor afd = context.getAssets().openFd(assetPath);
        FileInputStream inputStream = new FileInputStream(afd.getFileDescriptor());
        try {
            FileChannel fileChannel = inputStream.getChannel();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength());
        } finally {
            inputStream.close();
            afd.close();
        }
    }

    private static List<ClassificationResult> scoresToTopK(float[] scores, List<String> labels, int topK) {
        List<ClassificationResult> list = new ArrayList<>(scores.length);
        for (int index = 0; index < scores.length; index++) {
            String label = index < labels.size() ? labels.get(index) : "Class " + index;
            list.add(new ClassificationResult(label, scores[index]));
        }
        Collections.sort(list, new Comparator<ClassificationResult>() {
            @Override
            public int compare(ClassificationResult a, ClassificationResult b) {
                return Float.compare(b.score, a.score);
            }
        });
        if (list.size() > topK) {
            return new ArrayList<>(list.subList(0, topK));
        }
        return list;
    }

    private static ByteBuffer createImageInputBuffer(Bitmap bitmap, ClassificationModelConfig config) {
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, config.inputWidth, config.inputHeight, true);
        int pixelCount = config.inputWidth * config.inputHeight;
        int channelCount = 3;
        int bytesPerValue = config.quantizedInput ? 1 : 4;
        ByteBuffer buffer = ByteBuffer.allocateDirect(pixelCount * channelCount * bytesPerValue)
                .order(ByteOrder.nativeOrder());
        int[] pixels = new int[pixelCount];
        scaled.getPixels(pixels, 0, config.inputWidth, 0, 0, config.inputWidth, config.inputHeight);

        for (int pixel : pixels) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            if (config.quantizedInput) {
                buffer.put((byte) r);
                buffer.put((byte) g);
                buffer.put((byte) b);
            } else {
                buffer.putFloat((r - config.meanRgb[0]) / config.stdRgb[0]);
                buffer.putFloat((g - config.meanRgb[1]) / config.stdRgb[1]);
                buffer.putFloat((b - config.meanRgb[2]) / config.stdRgb[2]);
            }
        }
        buffer.rewind();
        if (scaled != bitmap) {
            scaled.recycle();
        }
        return buffer;
    }
}