package com.micklab.mcp.runtime;

public final class NativeImageBridge {
    private static final NativeImageBridge INSTANCE = new NativeImageBridge();

    private boolean loaded;

    private NativeImageBridge() {
    }

    public static NativeImageBridge getInstance() {
        return INSTANCE;
    }

    public synchronized void load() {
        if (loaded) {
            return;
        }
        System.loadLibrary("mcp_native");
        loaded = true;
    }

    public synchronized String describe() {
        return "loaded=" + loaded;
    }

    public synchronized byte[] invertGrayscale(byte[] grayscalePixels, int width, int height) {
        load();
        return nativeInvertGrayscale(grayscalePixels, width, height);
    }

    public synchronized String runtimeInfo() {
        load();
        return nativeRuntimeInfo();
    }

    private native byte[] nativeInvertGrayscale(byte[] grayscalePixels, int width, int height);

    private native String nativeRuntimeInfo();
}
