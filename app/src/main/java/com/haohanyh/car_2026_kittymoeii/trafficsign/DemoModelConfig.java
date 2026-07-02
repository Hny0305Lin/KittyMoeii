package com.haohanyh.car_2026_kittymoeii.trafficsign;

import com.haohanyh.car_2026_kittymoeii.trafficsign.InferenceModels.InferenceEngine;

public final class DemoModelConfig {

    private DemoModelConfig() {
    }

    public static final class ClassificationModelConfig {
        public final InferenceEngine engine;
        public final String modelAssetPath;
        public final String labelAssetPath;
        public final int inputWidth;
        public final int inputHeight;
        public final float[] meanRgb;
        public final float[] stdRgb;
        public final int topK;
        public final boolean quantizedInput;

        public ClassificationModelConfig(InferenceEngine engine,
                                         String modelAssetPath,
                                         String labelAssetPath,
                                         int inputWidth,
                                         int inputHeight,
                                         float[] meanRgb,
                                         float[] stdRgb,
                                         int topK,
                                         boolean quantizedInput) {
            this.engine = engine;
            this.modelAssetPath = modelAssetPath;
            this.labelAssetPath = labelAssetPath;
            this.inputWidth = inputWidth;
            this.inputHeight = inputHeight;
            this.meanRgb = meanRgb;
            this.stdRgb = stdRgb;
            this.topK = topK;
            this.quantizedInput = quantizedInput;
        }
    }

    public static final class DemoPipelineConfig {
        public final ClassificationModelConfig tfliteClassification;

        public DemoPipelineConfig(ClassificationModelConfig tfliteClassification) {
            this.tfliteClassification = tfliteClassification;
        }
    }

    public static final DemoPipelineConfig CURRENT = new DemoPipelineConfig(
            new ClassificationModelConfig(
                    InferenceEngine.TFLITE,
                    "traffic_sign_100ep_float32.tflite",
                    "traffic_sign_label_list.txt",
                    224,
                    224,
                    new float[]{127.5f, 127.5f, 127.5f},
                    new float[]{127.5f, 127.5f, 127.5f},
                    5,
                    false
            )
    );
}