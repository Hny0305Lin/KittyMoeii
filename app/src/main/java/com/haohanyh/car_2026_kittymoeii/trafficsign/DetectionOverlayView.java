package com.haohanyh.car_2026_kittymoeii.trafficsign;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.haohanyh.car_2026_kittymoeii.trafficsign.InferenceModels.DetectionResult;

import java.util.Collections;
import java.util.List;

public class DetectionOverlayView extends View {

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int sourceWidth = 1;
    private int sourceHeight = 1;
    private List<DetectionResult> detections = Collections.emptyList();

    public DetectionOverlayView(Context context) {
        super(context);
        initPaints();
    }

    public DetectionOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaints();
    }

    public DetectionOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initPaints();
    }

    private void initPaints() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(6f);
        textBgPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(38f);
    }

    public void setDetections(List<DetectionResult> results, int imageWidth, int imageHeight) {
        detections = results == null ? Collections.<DetectionResult>emptyList() : results;
        sourceWidth = Math.max(1, imageWidth);
        sourceHeight = Math.max(1, imageHeight);
        invalidate();
    }

    public void clearDetections() {
        detections = Collections.emptyList();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (detections.isEmpty()) {
            return;
        }

        float scale = Math.max(getWidth() / (float) sourceWidth, getHeight() / (float) sourceHeight);
        float offsetX = (getWidth() - sourceWidth * scale) / 2f;
        float offsetY = (getHeight() - sourceHeight * scale) / 2f;

        for (DetectionResult detection : detections) {
            float left = clamp(detection.boundingBox.left * scale + offsetX, 0f, getWidth());
            float top = clamp(detection.boundingBox.top * scale + offsetY, 0f, getHeight());
            float right = clamp(detection.boundingBox.right * scale + offsetX, 0f, getWidth());
            float bottom = clamp(detection.boundingBox.bottom * scale + offsetY, 0f, getHeight());
            RectF safeRect = new RectF(left, top, right, bottom);
            if (safeRect.width() <= 1f || safeRect.height() <= 1f) {
                continue;
            }

            boxPaint.setColor(detection.boxColor);
            textBgPaint.setColor(blendWithBlack(detection.boxColor));
            textPaint.setColor(isBrightColor(detection.boxColor) ? Color.BLACK : Color.WHITE);

            canvas.drawRoundRect(safeRect, 18f, 18f, boxPaint);

            String label = detection.label + " " + (int) (detection.score * 100f) + "%";
            float textWidth = textPaint.measureText(label);
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float textHeight = metrics.descent - metrics.ascent;
            float bubbleHeight = textHeight + 22f;
            boolean showAbove = safeRect.top > bubbleHeight + 12f;
            float bgTop = showAbove
                    ? safeRect.top - bubbleHeight - 8f
                    : Math.min(safeRect.top + 8f, getHeight() - bubbleHeight - 4f);
            float bgBottom = Math.min(bgTop + bubbleHeight, getHeight());
            float bgRight = Math.min(safeRect.left + textWidth + 28f, getWidth());

            canvas.drawRoundRect(safeRect.left, bgTop, bgRight, bgBottom, 14f, 14f, textBgPaint);
            float baseline = bgTop + 11f - metrics.ascent;
            canvas.drawText(label, safeRect.left + 14f, baseline, textPaint);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int blendWithBlack(int color) {
        int r = (int) (Color.red(color) * 0.75f);
        int g = (int) (Color.green(color) * 0.75f);
        int b = (int) (Color.blue(color) * 0.75f);
        return Color.argb(220, r, g, b);
    }

    private static boolean isBrightColor(int color) {
        float brightness = Color.red(color) * 0.299f
                + Color.green(color) * 0.587f
                + Color.blue(color) * 0.114f;
        return brightness >= 170f;
    }
}