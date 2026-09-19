package com.yaahua.vcam;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageWriter;
import android.os.Build;
import android.view.Surface;

import java.nio.ByteBuffer;

import de.robv.android.xposed.XposedBridge;

/**
 * Feeds decoded video frames into a consumer {@link Surface} that belongs to an
 * {@link android.media.ImageReader} created with {@link ImageFormat#YUV_420_888}.
 *
 * <p>Background: VCAM's normal Camera2 path hands the reader's Surface straight to
 * {@code MediaCodec.configure(..., surface, ...)}. The decoder then renders opaque,
 * vendor-defined gralloc buffers into it. A YUV_420_888 reader (as used by Chromium's
 * {@code cr_VideoCapture}) cannot lock those buffers for CPU access, so
 * {@code SurfaceImage.nativeCreatePlanes()} produces null plane pointers and the consumer
 * fails with {@code IllegalStateException} on {@code acquireLatestImage()} (or, in Opera's
 * case, a hard JNI abort in {@code NewDirectByteBuffer}).
 *
 * <p>This class instead produces real, CPU-writable YUV_420_888 images via {@link ImageWriter},
 * honouring each destination plane's {@code rowStride} and {@code pixelStride}.
 */
public final class Yuv420888SurfaceWriter {

    private static final String TAG = "【VCAM】[C2][YUV] ";

    private final Surface target;
    private final int maxImages;

    private ImageWriter writer;
    private boolean stridesLogged;
    private volatile boolean released;

    public Yuv420888SurfaceWriter(Surface target, int maxImages) {
        this.target = target;
        this.maxImages = maxImages;
    }

    /** @return true if an ImageWriter could be attached to the target surface. */
    public synchronized boolean open() {
        if (released) return false;
        if (writer != null) return true;
        if (target == null || !target.isValid()) {
            XposedBridge.log(TAG + "target surface invalid, not opening writer");
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writer = ImageWriter.newInstance(target, maxImages, ImageFormat.YUV_420_888);
            } else {
                // API 23..28: size and format are inherited from the consumer surface.
                writer = ImageWriter.newInstance(target, maxImages);
            }
            XposedBridge.log(TAG + "writer created maxImages=" + maxImages
                    + " api=" + Build.VERSION.SDK_INT);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "failed to create ImageWriter: " + t);
            writer = null;
            return false;
        }
    }

    /**
     * Copies one NV21 frame into the consumer. Scales with nearest-neighbour sampling when the
     * decoded video size differs from the consumer's image size.
     *
     * @return true when the frame was queued.
     */
    public synchronized boolean writeNv21(byte[] nv21, int srcWidth, int srcHeight) {
        if (released || nv21 == null || srcWidth <= 1 || srcHeight <= 1) return false;
        if (writer == null && !open()) return false;

        Image image = null;
        boolean queued = false;
        try {
            image = writer.dequeueInputImage();
            if (image == null) return false;

            Image.Plane[] planes = image.getPlanes();
            if (planes.length < 3) {
                XposedBridge.log(TAG + "unexpected plane count " + planes.length);
                return false;
            }
            if (!stridesLogged) {
                stridesLogged = true;
                XposedBridge.log(TAG + "src=NV21 " + srcWidth + "x" + srcHeight
                        + " dst=YUV_420_888 " + image.getWidth() + "x" + image.getHeight());
                XposedBridge.log(TAG + "Y rowStride=" + planes[0].getRowStride()
                        + " pixelStride=" + planes[0].getPixelStride());
                XposedBridge.log(TAG + "U rowStride=" + planes[1].getRowStride()
                        + " pixelStride=" + planes[1].getPixelStride());
                XposedBridge.log(TAG + "V rowStride=" + planes[2].getRowStride()
                        + " pixelStride=" + planes[2].getPixelStride());
            }

            writeNv21ToPlanes(nv21, srcWidth, srcHeight,
                    image.getWidth(), image.getHeight(),
                    planes[0].getBuffer(), planes[0].getRowStride(), planes[0].getPixelStride(),
                    planes[1].getBuffer(), planes[1].getRowStride(), planes[1].getPixelStride(),
                    planes[2].getBuffer(), planes[2].getRowStride(), planes[2].getPixelStride());

            writer.queueInputImage(image);
            queued = true;
            return true;
        } catch (Throwable t) {
            // IllegalStateException (no free slot / abandoned surface), BufferOverflowException,
            // BufferUnderflowException, IllegalArgumentException on size change, ...
            XposedBridge.log(TAG + "frame dropped: " + t);
            if (isFatal(t)) closeWriterLocked();
            return false;
        } finally {
            // Never leave an Image dequeued.
            if (image != null && !queued) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean isFatal(Throwable t) {
        String m = String.valueOf(t.getMessage());
        return m.contains("abandoned") || m.contains("Surface") || t instanceof IllegalArgumentException;
    }

    public synchronized void release() {
        released = true;
        closeWriterLocked();
    }

    private void closeWriterLocked() {
        if (writer != null) {
            try {
                writer.close();
            } catch (Throwable ignored) {
            }
            writer = null;
            stridesLogged = false;
        }
    }

    // ====================================================================================
    // Pure copy logic — no Android dependencies, covered by unit tests.
    // ====================================================================================

    /**
     * Copies an NV21 source frame ({@code Y} plane followed by interleaved {@code V,U}) into three
     * destination planes laid out according to their own row/pixel strides, as required by
     * {@link ImageFormat#YUV_420_888}. Destination rows are written pixel-by-pixel so that stride
     * padding bytes are never treated as image data.
     *
     * <p>When source and destination dimensions differ, nearest-neighbour sampling is used.
     */
    static void writeNv21ToPlanes(byte[] nv21, int srcWidth, int srcHeight,
                                  int dstWidth, int dstHeight,
                                  ByteBuffer yBuf, int yRowStride, int yPixelStride,
                                  ByteBuffer uBuf, int uRowStride, int uPixelStride,
                                  ByteBuffer vBuf, int vRowStride, int vPixelStride) {
        final int srcChromaW = srcWidth / 2;
        final int srcChromaH = srcHeight / 2;
        final int ySize = srcWidth * srcHeight;
        final int required = ySize + srcChromaW * srcChromaH * 2;
        if (nv21.length < required) {
            throw new IllegalArgumentException("NV21 buffer too small: " + nv21.length + " < " + required);
        }

        // --- luma ---
        for (int row = 0; row < dstHeight; row++) {
            final int srcRow = (dstHeight == srcHeight) ? row : (int) ((long) row * srcHeight / dstHeight);
            final int srcBase = srcRow * srcWidth;
            final int dstBase = row * yRowStride;
            for (int col = 0; col < dstWidth; col++) {
                final int srcCol = (dstWidth == srcWidth) ? col : (int) ((long) col * srcWidth / dstWidth);
                yBuf.put(dstBase + col * yPixelStride, nv21[srcBase + srcCol]);
            }
        }

        // --- chroma ---
        final int dstChromaW = dstWidth / 2;
        final int dstChromaH = dstHeight / 2;
        for (int row = 0; row < dstChromaH; row++) {
            final int srcRow = (dstChromaH == srcChromaH) ? row : (int) ((long) row * srcChromaH / dstChromaH);
            // NV21 chroma row stride in bytes == srcWidth (srcChromaW interleaved V,U pairs)
            final int srcBase = ySize + srcRow * srcWidth;
            final int uDstBase = row * uRowStride;
            final int vDstBase = row * vRowStride;
            for (int col = 0; col < dstChromaW; col++) {
                final int srcCol = (dstChromaW == srcChromaW) ? col : (int) ((long) col * srcChromaW / dstChromaW);
                final int p = srcBase + srcCol * 2;
                vBuf.put(vDstBase + col * vPixelStride, nv21[p]);      // NV21: Cr first
                uBuf.put(uDstBase + col * uPixelStride, nv21[p + 1]);  // then Cb
            }
        }
    }
}
