package com.yaahua.vcam;

import android.view.Surface;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Per-ImageReader metadata, keyed by the reader's own {@link Surface}.
 *
 * <p>VCAM previously kept a single global {@code SharedState.imageReaderFormat}, which is whatever
 * the most recent {@code ImageReader.newInstance()} happened to request. Chromium creates several
 * readers, so that global routinely describes the wrong one. Keying on the Surface lets every
 * target get its own format.
 */
public final class ReaderSurfaceInfo {

    public final int width;
    public final int height;
    public final int format;
    public final int maxImages;

    public ReaderSurfaceInfo(int width, int height, int format, int maxImages) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.maxImages = maxImages;
    }

    private static final Map<Surface, ReaderSurfaceInfo> REGISTRY =
            Collections.synchronizedMap(new WeakHashMap<Surface, ReaderSurfaceInfo>());

    public static void register(Surface surface, ReaderSurfaceInfo info) {
        if (surface == null) return;
        REGISTRY.put(surface, info);
        XposedBridge.log("【VCAM】[C2][Reader] surface=" + surface + " " + info.width + "x" + info.height
                + " format=" + info.format + " maxImages=" + info.maxImages);
    }

    /** @return the info recorded for this surface, or null when it was never seen. */
    public static ReaderSurfaceInfo get(Surface surface) {
        if (surface == null) return null;
        return REGISTRY.get(surface);
    }

    /**
     * Format of the reader behind {@code surface}, falling back to the legacy global when the
     * surface was not captured (e.g. a reader created before VCAM hooked, or via a constructor
     * overload we do not hook).
     */
    public static int formatOf(Surface surface) {
        ReaderSurfaceInfo info = get(surface);
        return (info != null) ? info.format : SharedState.imageReaderFormat;
    }

    public static int maxImagesOf(Surface surface, int fallback) {
        ReaderSurfaceInfo info = get(surface);
        return (info != null && info.maxImages > 0) ? info.maxImages : fallback;
    }

    @Override
    public String toString() {
        return width + "x" + height + " format=" + format + " maxImages=" + maxImages;
    }
}
