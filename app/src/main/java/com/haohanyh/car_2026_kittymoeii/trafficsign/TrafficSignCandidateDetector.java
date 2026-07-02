package com.haohanyh.car_2026_kittymoeii.trafficsign;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TrafficSignCandidateDetector {

    public static final class CandidateRegion {
        public final RectF boundingBox;
        public final int boxColor;
        public final float area;

        public CandidateRegion(RectF boundingBox, int boxColor, float area) {
            this.boundingBox = boundingBox;
            this.boxColor = boxColor;
            this.area = area;
        }
    }

    private static final int MAX_ANALYSIS_EDGE = 960;
    private static final int MAX_CANDIDATE_COUNT = 32;
    private static final int MIN_COMPONENT_PIXELS_FLOOR = 48;
    private static final float MIN_COMPONENT_AREA_RATIO = 0.00018f;
    private static final int MIN_EDGE_PIXELS_FLOOR = 10;
    private static final float MIN_EDGE_RATIO = 0.024f;
    private static final float MIN_FILL_RATIO = 0.3f;
    private static final float MIN_ASPECT_RATIO = 0.4f;
    private static final float MAX_ASPECT_RATIO = 2.0f;
    private static final float MAX_AREA_RATIO = 0.16f;
    private static final float MAX_EDGE_RATIO = 0.42f;
    private static final float EDGE_MARGIN_RATIO = 0.005f;
    private static final int EDGE_MARGIN_PX = 1;
    private static final float EXPAND_RATIO = 0.2f;
    private static final float MIN_SATURATION = 0.24f;
    private static final float MIN_VALUE = 0.16f;
    private static final float MERGE_IOU_THRESHOLD = 0.2f;
    private static final float MERGE_SMALLER_RATIO = 0.8f;

    private static final int COLOR_NONE = 0;
    private static final int COLOR_RED = 1;
    private static final int COLOR_BLUE = 2;
    private static final int COLOR_YELLOW = 3;
    private static final int COLOR_GREEN = 4;

    public List<CandidateRegion> detect(Bitmap bitmap) {
        int longestEdge = Math.max(bitmap.getWidth(), bitmap.getHeight());
        float scale = longestEdge > MAX_ANALYSIS_EDGE
                ? MAX_ANALYSIS_EDGE / (float) longestEdge
                : 1f;
        int scaledWidth = Math.max(1, Math.round(bitmap.getWidth() * scale));
        int scaledHeight = Math.max(1, Math.round(bitmap.getHeight() * scale));
        Bitmap scaledBitmap = scale < 1f
                ? Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                : bitmap;

        try {
            int[] pixels = new int[scaledWidth * scaledHeight];
            scaledBitmap.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight);
            int[] colorMask = new int[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                colorMask[i] = classifyColor(pixels[i]);
            }
            boolean[] visited = new boolean[pixels.length];
            int[] queue = new int[pixels.length];
            List<CandidateRegion> detected = new ArrayList<>();

            for (int index = 0; index < colorMask.length; index++) {
                int colorId = colorMask[index];
                if (colorId == COLOR_NONE || visited[index]) {
                    continue;
                }
                float scaleBack = scale == 0f ? 1f : 1f / scale;
                CandidateRegion region = exploreRegion(
                        index, colorId, colorMask, visited,
                        scaledWidth, scaledHeight, queue, scaleBack);
                if (region != null) {
                    detected.add(region);
                }
            }
            List<CandidateRegion> merged = mergeOverlapping(detected);
            Collections.sort(merged, (a, b) -> Float.compare(b.area, a.area));
            if (merged.size() > MAX_CANDIDATE_COUNT) {
                return new ArrayList<>(merged.subList(0, MAX_CANDIDATE_COUNT));
            }
            return merged;
        } finally {
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle();
            }
        }
    }

    private CandidateRegion exploreRegion(int startIndex,
                                          int targetColor,
                                          int[] mask,
                                          boolean[] visited,
                                          int width,
                                          int height,
                                          int[] queue,
                                          float scaleBack) {
        int frameArea = width * height;
        int minFrameEdge = Math.min(width, height);
        int minComponentPixels = Math.max(MIN_COMPONENT_PIXELS_FLOOR,
                Math.round(frameArea * MIN_COMPONENT_AREA_RATIO));
        int minEdgePixels = Math.max(MIN_EDGE_PIXELS_FLOOR, Math.round(minFrameEdge * MIN_EDGE_RATIO));

        int head = 0;
        int tail = 0;
        queue[tail++] = startIndex;
        visited[startIndex] = true;

        int minX = startIndex % width;
        int maxX = minX;
        int minY = startIndex / width;
        int maxY = minY;
        int pixelCount = 0;

        while (head < tail) {
            int current = queue[head++];
            int x = current % width;
            int y = current / width;
            pixelCount++;
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;

            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    if (offsetX == 0 && offsetY == 0) {
                        continue;
                    }
                    int nextX = x + offsetX;
                    int nextY = y + offsetY;
                    if (nextX < 0 || nextX >= width || nextY < 0 || nextY >= height) {
                        continue;
                    }
                    int nextIndex = nextY * width + nextX;
                    if (!visited[nextIndex] && mask[nextIndex] == targetColor) {
                        visited[nextIndex] = true;
                        queue[tail++] = nextIndex;
                    }
                }
            }
        }

        int regionWidth = maxX - minX + 1;
        int regionHeight = maxY - minY + 1;
        int bboxArea = regionWidth * regionHeight;
        if (pixelCount < minComponentPixels || regionWidth < minEdgePixels || regionHeight < minEdgePixels) {
            return null;
        }
        float aspectRatio = regionWidth / (float) regionHeight;
        if (aspectRatio < MIN_ASPECT_RATIO || aspectRatio > MAX_ASPECT_RATIO) {
            return null;
        }
        if (bboxArea <= 0 || pixelCount / (float) bboxArea < MIN_FILL_RATIO) {
            return null;
        }
        if (bboxArea / (float) frameArea > MAX_AREA_RATIO) {
            return null;
        }
        if (regionWidth / (float) width > MAX_EDGE_RATIO || regionHeight / (float) height > MAX_EDGE_RATIO) {
            return null;
        }
        int edgeMarginX = Math.max(EDGE_MARGIN_PX, Math.round(width * EDGE_MARGIN_RATIO));
        int edgeMarginY = Math.max(EDGE_MARGIN_PX, Math.round(height * EDGE_MARGIN_RATIO));
        boolean touchesFrameEdge = minX <= edgeMarginX
                || minY <= edgeMarginY
                || maxX >= width - edgeMarginX
                || maxY >= height - edgeMarginY;
        if (touchesFrameEdge) {
            return null;
        }
        if (!passesShapeProfileFilter(mask, width, minX, maxX, minY, maxY, targetColor, pixelCount)) {
            return null;
        }

        RectF expandedRect = new RectF(
                (minX - regionWidth * EXPAND_RATIO) * scaleBack,
                (minY - regionHeight * EXPAND_RATIO) * scaleBack,
                (maxX + 1 + regionWidth * EXPAND_RATIO) * scaleBack,
                (maxY + 1 + regionHeight * EXPAND_RATIO) * scaleBack
        );
        return new CandidateRegion(
                expandedRect,
                colorToOverlay(targetColor),
                expandedRect.width() * expandedRect.height()
        );
    }

    private List<CandidateRegion> mergeOverlapping(List<CandidateRegion> input) {
        List<CandidateRegion> merged = new ArrayList<>();
        for (CandidateRegion candidate : input) {
            boolean wasMerged = false;
            for (int index = 0; index < merged.size(); index++) {
                CandidateRegion existing = merged.get(index);
                if (shouldMerge(existing.boundingBox, candidate.boundingBox)) {
                    RectF mergedRect = new RectF(
                            Math.min(existing.boundingBox.left, candidate.boundingBox.left),
                            Math.min(existing.boundingBox.top, candidate.boundingBox.top),
                            Math.max(existing.boundingBox.right, candidate.boundingBox.right),
                            Math.max(existing.boundingBox.bottom, candidate.boundingBox.bottom)
                    );
                    int color = existing.area >= candidate.area ? existing.boxColor : candidate.boxColor;
                    float area = Math.max(existing.area, candidate.area);
                    merged.set(index, new CandidateRegion(mergedRect, color, area));
                    wasMerged = true;
                    break;
                }
            }
            if (!wasMerged) {
                merged.add(candidate);
            }
        }
        return merged;
    }

    private int classifyColor(int pixel) {
        float[] hsv = new float[3];
        Color.colorToHSV(pixel, hsv);
        float hue = hsv[0];
        float saturation = hsv[1];
        float value = hsv[2];

        if (saturation < MIN_SATURATION || value < MIN_VALUE) {
            return COLOR_NONE;
        }
        if (hue <= 24f || hue >= 332f) {
            return COLOR_RED;
        }
        if (hue >= 180f && hue <= 265f) {
            return COLOR_BLUE;
        }
        if (hue >= 28f && hue <= 82f) {
            return COLOR_YELLOW;
        }
        if (hue >= 88f && hue <= 165f) {
            return COLOR_GREEN;
        }
        return COLOR_NONE;
    }

    private boolean shouldMerge(RectF first, RectF second) {
        if (!RectF.intersects(first, second)) {
            return false;
        }
        float intersectionLeft = Math.max(first.left, second.left);
        float intersectionTop = Math.max(first.top, second.top);
        float intersectionRight = Math.min(first.right, second.right);
        float intersectionBottom = Math.min(first.bottom, second.bottom);
        float intersectionArea =
                Math.max(0f, intersectionRight - intersectionLeft)
                        * Math.max(0f, intersectionBottom - intersectionTop);
        if (intersectionArea <= 0f) {
            return false;
        }
        float firstArea = first.width() * first.height();
        float secondArea = second.width() * second.height();
        float unionArea = firstArea + secondArea - intersectionArea;
        if (unionArea <= 0f) {
            return false;
        }
        float iou = intersectionArea / unionArea;
        float overlapOnSmaller = intersectionArea / Math.min(firstArea, secondArea);
        return iou >= MERGE_IOU_THRESHOLD || overlapOnSmaller >= MERGE_SMALLER_RATIO;
    }

    private boolean passesShapeProfileFilter(int[] mask,
                                              int imageWidth,
                                              int minX,
                                              int maxX,
                                              int minY,
                                              int maxY,
                                              int targetColor,
                                              int pixelCount) {
        int boxWidth = maxX - minX + 1;
        int boxHeight = maxY - minY + 1;
        if (boxWidth <= 0 || boxHeight <= 0) {
            return false;
        }

        int[] rowWidths = new int[boxHeight];
        int[] colHeights = new int[boxWidth];
        int centerPixels = 0;
        int centerLeft = minX + Math.round(boxWidth * 0.25f);
        int centerRight = maxX - Math.round(boxWidth * 0.25f);
        int centerTop = minY + Math.round(boxHeight * 0.25f);
        int centerBottom = maxY - Math.round(boxHeight * 0.25f);

        for (int y = minY; y <= maxY; y++) {
            int rowMin = Integer.MAX_VALUE;
            int rowMax = Integer.MIN_VALUE;
            for (int x = minX; x <= maxX; x++) {
                if (mask[y * imageWidth + x] != targetColor) {
                    continue;
                }
                if (x < rowMin) rowMin = x;
                if (x > rowMax) rowMax = x;
                colHeights[x - minX] += 1;
                if (x >= centerLeft && x <= centerRight && y >= centerTop && y <= centerBottom) {
                    centerPixels += 1;
                }
            }
            int rowIndex = y - minY;
            if (rowMin != Integer.MAX_VALUE) {
                rowWidths[rowIndex] = rowMax - rowMin + 1;
            }
        }

        float topAvg = averageNonZero(rowWidths, 0, Math.max(1, boxHeight / 3));
        float middleAvg = averageNonZero(rowWidths, boxHeight / 3, Math.max(boxHeight / 3 + 1, boxHeight * 2 / 3));
        float bottomAvg = averageNonZero(rowWidths, boxHeight * 2 / 3, boxHeight);
        float leftAvg = averageNonZero(colHeights, 0, Math.max(1, boxWidth / 3));
        float middleColAvg = averageNonZero(colHeights, boxWidth / 3, Math.max(boxWidth / 3 + 1, boxWidth * 2 / 3));
        float rightAvg = averageNonZero(colHeights, boxWidth * 2 / 3, boxWidth);
        float centerRatio = centerPixels / (float) pixelCount;
        float aspectRatio = boxWidth / (float) boxHeight;
        boolean nearSquare = aspectRatio >= 0.68f && aspectRatio <= 1.34f;
        boolean broadSquare = aspectRatio >= 0.6f && aspectRatio <= 1.5f;

        boolean roundLikeStrong =
                nearSquare
                        && middleAvg > topAvg * 1.14f
                        && middleAvg > bottomAvg * 1.14f
                        && middleColAvg > leftAvg * 1.08f
                        && middleColAvg > rightAvg * 1.08f;

        boolean roundLikeSoft =
                broadSquare
                        && middleAvg > topAvg * 1.06f
                        && middleAvg > bottomAvg * 1.06f
                        && middleColAvg > leftAvg * 1.03f
                        && middleColAvg > rightAvg * 1.03f
                        && centerRatio >= 0.1f && centerRatio <= 0.65f;

        boolean octagonLikeStrong =
                nearSquare
                        && topAvg > boxWidth * 0.38f
                        && bottomAvg > boxWidth * 0.38f
                        && middleAvg > topAvg * 1.03f
                        && middleAvg > bottomAvg * 1.03f
                        && centerRatio >= 0.08f && centerRatio <= 0.6f;

        boolean octagonLikeSoft =
                broadSquare
                        && topAvg > boxWidth * 0.3f
                        && bottomAvg > boxWidth * 0.3f
                        && middleAvg >= topAvg
                        && middleAvg >= bottomAvg
                        && centerRatio >= 0.06f && centerRatio <= 0.7f;

        boolean triangleUpStrong =
                aspectRatio >= 0.68f && aspectRatio <= 1.42f
                        && bottomAvg > topAvg * 1.32f
                        && middleAvg > topAvg * 1.12f
                        && centerRatio < 0.5f;

        boolean triangleUpSoft =
                aspectRatio >= 0.62f && aspectRatio <= 1.5f
                        && bottomAvg > topAvg * 1.18f
                        && middleAvg > topAvg * 1.05f
                        && centerRatio < 0.6f;

        boolean triangleDownStrong =
                aspectRatio >= 0.68f && aspectRatio <= 1.42f
                        && topAvg > bottomAvg * 1.32f
                        && middleAvg > bottomAvg * 1.12f
                        && centerRatio < 0.5f;

        boolean triangleDownSoft =
                aspectRatio >= 0.62f && aspectRatio <= 1.5f
                        && topAvg > bottomAvg * 1.18f
                        && middleAvg > bottomAvg * 1.05f
                        && centerRatio < 0.6f;

        boolean rectangleLike =
                aspectRatio >= 0.55f && aspectRatio <= 2.0f
                        && topAvg > boxWidth * 0.3f
                        && bottomAvg > boxWidth * 0.3f
                        && leftAvg > boxHeight * 0.3f
                        && rightAvg > boxHeight * 0.3f
                        && centerRatio >= 0.1f && centerRatio <= 0.75f
                        && Math.abs(topAvg - bottomAvg) / Math.max(topAvg, 1f) < 0.35f
                        && Math.abs(middleAvg - topAvg) / Math.max(topAvg, 1f) < 0.35f;

        return roundLikeStrong
                || roundLikeSoft
                || octagonLikeStrong
                || octagonLikeSoft
                || triangleUpStrong
                || triangleUpSoft
                || triangleDownStrong
                || triangleDownSoft
                || rectangleLike;
    }

    private float averageNonZero(int[] values, int startInclusive, int endExclusive) {
        float sum = 0f;
        int count = 0;
        int bound = Math.min(endExclusive, values.length);
        for (int index = startInclusive; index < bound; index++) {
            int value = values[index];
            if (value > 0) {
                sum += value;
                count += 1;
            }
        }
        return count == 0 ? 0f : sum / count;
    }

    private int colorToOverlay(int colorId) {
        switch (colorId) {
            case COLOR_RED:
                return Color.parseColor("#FF3B30");
            case COLOR_BLUE:
                return Color.parseColor("#0A84FF");
            case COLOR_YELLOW:
                return Color.parseColor("#FFD60A");
            case COLOR_GREEN:
                return Color.parseColor("#34C759");
            default:
                return Color.WHITE;
        }
    }
}