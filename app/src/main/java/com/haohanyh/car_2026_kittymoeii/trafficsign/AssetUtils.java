package com.haohanyh.car_2026_kittymoeii.trafficsign;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class AssetUtils {

    private AssetUtils() {
    }

    public static boolean assetExists(Context context, String assetPath) {
        try {
            context.getAssets().open(assetPath).close();
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    public static List<String> readLabels(Context context, String assetPath) {
        List<String> labels = new ArrayList<>();
        if (!assetExists(context, assetPath)) {
            return labels;
        }
        try {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(context.getAssets().open(assetPath)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        labels.add(trimmed);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return labels;
    }

    public static File copyAssetToCache(Context context, String assetPath) {
        String safeName = assetPath.replace('/', '_');
        File targetFile = new File(context.getCacheDir(), safeName);
        if (targetFile.exists() && targetFile.length() > 0L) {
            return targetFile;
        }
        File parent = targetFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (java.io.InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
        }
        return targetFile;
    }
}