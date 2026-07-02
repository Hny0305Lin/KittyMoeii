package com.haohanyh.car_2026_kittymoeii.trafficsign;

import android.graphics.RectF;

import java.util.Collections;
import java.util.List;

public final class InferenceModels {

    private InferenceModels() {
    }

    public enum InferenceEngine {
        TFLITE
    }

    public static final class ClassificationResult {
        public final String label;
        public final float score;

        public ClassificationResult(String label, float score) {
            this.label = label;
            this.score = score;
        }
    }

    public static final class DetectionResult {
        public final String label;
        public final float score;
        public final RectF boundingBox;
        public final int boxColor;

        public DetectionResult(String label, float score, RectF boundingBox, int boxColor) {
            this.label = label;
            this.score = score;
            this.boundingBox = boundingBox;
            this.boxColor = boxColor;
        }
    }

    public static final class TaskSummary {
        public final InferenceEngine engine;
        public final String message;
        public final long latencyMs;
        public final boolean isReady;

        public TaskSummary(InferenceEngine engine, String message, long latencyMs, boolean isReady) {
            this.engine = engine;
            this.message = message;
            this.latencyMs = latencyMs;
            this.isReady = isReady;
        }
    }

    public static final class EngineClassificationResult {
        public final TaskSummary summary;
        public final List<ClassificationResult> topResults;

        public EngineClassificationResult(TaskSummary summary, List<ClassificationResult> topResults) {
            this.summary = summary;
            this.topResults = topResults == null ? Collections.<ClassificationResult>emptyList() : topResults;
        }
    }

    public static final class FrameAnalysisResult {
        public final int frameWidth;
        public final int frameHeight;
        public final List<DetectionResult> detections;
        public final String statusMessage;

        public FrameAnalysisResult(int frameWidth, int frameHeight,
                                   List<DetectionResult> detections, String statusMessage) {
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;
            this.detections = detections == null ? Collections.<DetectionResult>emptyList() : detections;
            this.statusMessage = statusMessage;
        }
    }
}