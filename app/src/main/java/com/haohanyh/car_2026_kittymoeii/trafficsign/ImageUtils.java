package com.haohanyh.car_2026_kittymoeii.trafficsign;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;

import java.nio.ByteBuffer;

public final class ImageUtils {

    private ImageUtils() {
    }

    public static Bitmap rotateBitmap(Bitmap source, int rotationDegrees) {
        if (rotationDegrees == 0) {
            return source;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        if (rotated != source) {
            source.recycle();
        }
        return rotated;
    }

    public static Bitmap cropBitmap(Bitmap source, RectF rectF) {
        int left = (int) clamp(rectF.left, 0f, source.getWidth() - 1f);
        int top = (int) clamp(rectF.top, 0f, source.getHeight() - 1f);
        int right = Math.round(clamp(rectF.right, left + 1f, source.getWidth()));
        int bottom = Math.round(clamp(rectF.bottom, top + 1f, source.getHeight()));
        return Bitmap.createBitmap(source, left, top, Math.max(1, right - left), Math.max(1, bottom - top));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static Rect toRect(RectF rectF) {
        return new Rect(
                Math.round(rectF.left),
                Math.round(rectF.top),
                Math.round(rectF.right),
                Math.round(rectF.bottom)
        );
    }

    public static Bitmap rgbaToBitmap(byte[] rgba, int width, int height) {
        if (rgba == null || width <= 0 || height <= 0) {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
        return bitmap;
    }
}