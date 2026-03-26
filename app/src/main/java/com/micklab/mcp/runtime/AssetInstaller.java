package com.micklab.mcp.runtime;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

public final class AssetInstaller {
    private static final String STAMP_FILE_NAME = "asset_bundle_stamp.txt";

    private AssetInstaller() {
    }

    public static void syncAssetTree(Context context, String assetRoot, File destination)
            throws IOException {
        String expectedStamp = resolveAssetStamp(context);
        File stampFile = new File(destination, STAMP_FILE_NAME);
        if (stampFile.isFile()) {
            String existingStamp = readUtf8(stampFile).trim();
            if (expectedStamp.equals(existingStamp)) {
                return;
            }
        }
        deleteRecursively(destination);
        copyAssetTree(context.getAssets(), assetRoot, destination);
        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("Unable to create runtime directory: " + destination);
        }
        writeUtf8(stampFile, expectedStamp);
    }

    private static void copyAssetTree(AssetManager assetManager, String assetPath, File destination)
            throws IOException {
        String[] children = assetManager.list(assetPath);
        if (children == null || children.length == 0) {
            copySingleAsset(assetManager, assetPath, destination);
            return;
        }
        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("Unable to create directory: " + destination);
        }
        for (String child : children) {
            String childAssetPath = assetPath + "/" + child;
            File childDestination = new File(destination, child);
            copyAssetTree(assetManager, childAssetPath, childDestination);
        }
    }

    private static void copySingleAsset(AssetManager assetManager, String assetPath, File destination)
            throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create asset parent directory: " + parent);
        }
        try (InputStream inputStream = assetManager.open(assetPath);
             FileOutputStream outputStream = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            throw new IOException("Unable to delete stale runtime file: " + file);
        }
    }

    private static String resolveAssetStamp(Context context) throws IOException {
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    PackageManager.GET_META_DATA
            );
            return Long.toString(packageInfo.lastUpdateTime);
        } catch (PackageManager.NameNotFoundException exception) {
            throw new IOException("Unable to resolve package metadata.", exception);
        }
    }

    private static String readUtf8(File file) throws IOException {
        try (InputStream inputStream = new java.io.FileInputStream(file);
             Reader reader = new java.io.InputStreamReader(inputStream, StandardCharsets.UTF_8);
             StringWriter writer = new StringWriter()) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, read);
            }
            return writer.toString();
        }
    }

    private static void writeUtf8(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create stamp parent directory: " + parent);
        }
        try (FileOutputStream outputStream = new FileOutputStream(file);
             OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
            writer.write(value);
        }
    }
}
