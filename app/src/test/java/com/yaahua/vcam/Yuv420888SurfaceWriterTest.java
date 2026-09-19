package com.yaahua.vcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.ByteBuffer;

/** Covers the stride-aware NV21 -> YUV_420_888 plane copy. */
public class Yuv420888SurfaceWriterTest {

    private static byte[] makeNv21(int w, int h) {
        byte[] d = new byte[w * h + (w / 2) * (h / 2) * 2];
        for (int i = 0; i < d.length; i++) d[i] = (byte) (i % 251);
        return d;
    }

    private static byte y(byte[] nv21, int w, int col, int row) {
        return nv21[row * w + col];
    }

    private static byte cr(byte[] nv21, int w, int h, int col, int row) {
        return nv21[w * h + row * w + col * 2];
    }

    private static byte cb(byte[] nv21, int w, int h, int col, int row) {
        return nv21[w * h + row * w + col * 2 + 1];
    }

    /** Tight destination: Y rowStride == width, chroma rowStride == width/2, all pixelStride 1. */
    @Test
    public void tightPlanarDestination() {
        int w = 640, h = 480;
        byte[] src = makeNv21(w, h);

        ByteBuffer yb = ByteBuffer.allocate(w * h);
        ByteBuffer ub = ByteBuffer.allocate((w / 2) * (h / 2));
        ByteBuffer vb = ByteBuffer.allocate((w / 2) * (h / 2));

        Yuv420888SurfaceWriter.writeNv21ToPlanes(src, w, h, w, h,
                yb, w, 1, ub, w / 2, 1, vb, w / 2, 1);

        for (int row = 0; row < h; row += 37) {
            for (int col = 0; col < w; col += 41) {
                assertEquals(y(src, w, col, row), yb.get(row * w + col));
            }
        }
        for (int row = 0; row < h / 2; row += 19) {
            for (int col = 0; col < w / 2; col += 23) {
                assertEquals(cb(src, w, h, col, row), ub.get(row * (w / 2) + col));
                assertEquals(cr(src, w, h, col, row), vb.get(row * (w / 2) + col));
            }
        }
    }

    /**
     * Padded/semi-planar destination, the common real-world case: Y rowStride > width, chroma
     * rowStride > width/2 and chroma pixelStride == 2. Padding bytes must stay untouched.
     */
    @Test
    public void paddedInterleavedDestination() {
        int w = 640, h = 480;
        int yRow = 768;              // padded
        int cRow = 768;              // interleaved UV row, padded
        byte[] src = makeNv21(w, h);

        byte[] yArr = new byte[yRow * h];
        byte[] cArr = new byte[cRow * (h / 2)];
        java.util.Arrays.fill(yArr, (byte) 0x7E);
        java.util.Arrays.fill(cArr, (byte) 0x7E);
        ByteBuffer yb = ByteBuffer.wrap(yArr);
        // U and V are views onto one interleaved buffer offset by a byte, as ImageReader exposes
        // them for a semi-planar (NV12-shaped) destination.
        ByteBuffer ub = ByteBuffer.wrap(cArr);
        ByteBuffer vb = ByteBuffer.wrap(cArr, 1, cArr.length - 1).slice();

        Yuv420888SurfaceWriter.writeNv21ToPlanes(src, w, h, w, h,
                yb, yRow, 1, ub, cRow, 2, vb, cRow, 2);

        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                assertEquals("y " + col + "," + row, y(src, w, col, row), yArr[row * yRow + col]);
            }
            // padding untouched
            for (int col = w; col < yRow; col++) {
                assertEquals("pad " + col + "," + row, (byte) 0x7E, yArr[row * yRow + col]);
            }
        }
        for (int row = 0; row < h / 2; row++) {
            for (int col = 0; col < w / 2; col++) {
                assertEquals("u", cb(src, w, h, col, row), cArr[row * cRow + col * 2]);
                assertEquals("v", cr(src, w, h, col, row), cArr[row * cRow + col * 2 + 1]);
            }
            // chroma row padding untouched
            for (int col = w; col < cRow; col++) {
                assertEquals("cpad", (byte) 0x7E, cArr[row * cRow + col]);
            }
        }
    }

    /** V-before-U interleave (true NV21-shaped destination) must not swap chroma. */
    @Test
    public void chromaOrderIsPreserved() {
        int w = 4, h = 4;
        byte[] src = new byte[w * h + 4 * 2];
        for (int i = 0; i < w * h; i++) src[i] = (byte) 10;
        // chroma: 2x2, pairs are (V,U)
        int off = w * h;
        src[off] = 100; src[off + 1] = (byte) 200;       // (0,0) V=100 U=200
        src[off + 2] = 101; src[off + 3] = (byte) 201;   // (1,0)
        src[off + 4] = 102; src[off + 5] = (byte) 202;   // (0,1)
        src[off + 6] = 103; src[off + 7] = (byte) 203;   // (1,1)

        ByteBuffer yb = ByteBuffer.allocate(w * h);
        ByteBuffer ub = ByteBuffer.allocate(4);
        ByteBuffer vb = ByteBuffer.allocate(4);
        Yuv420888SurfaceWriter.writeNv21ToPlanes(src, w, h, w, h, yb, w, 1, ub, 2, 1, vb, 2, 1);

        assertEquals((byte) 200, ub.get(0));
        assertEquals((byte) 201, ub.get(1));
        assertEquals((byte) 202, ub.get(2));
        assertEquals((byte) 203, ub.get(3));
        assertEquals((byte) 100, vb.get(0));
        assertEquals((byte) 103, vb.get(3));
    }

    /** Source larger than the consumer's reader: must scale, not overflow. */
    @Test
    public void downscalesWhenSizesDiffer() {
        int sw = 1280, sh = 720, dw = 640, dh = 480;
        byte[] src = makeNv21(sw, sh);
        ByteBuffer yb = ByteBuffer.allocate(dw * dh);
        ByteBuffer ub = ByteBuffer.allocate((dw / 2) * (dh / 2));
        ByteBuffer vb = ByteBuffer.allocate((dw / 2) * (dh / 2));

        Yuv420888SurfaceWriter.writeNv21ToPlanes(src, sw, sh, dw, dh,
                yb, dw, 1, ub, dw / 2, 1, vb, dw / 2, 1);

        // corners map to the source corners
        assertEquals(y(src, sw, 0, 0), yb.get(0));
        assertEquals(y(src, sw, (dw - 1) * sw / dw, (dh - 1) * sh / dh), yb.get((dh - 1) * dw + (dw - 1)));
    }

    @Test
    public void rejectsShortSourceBuffer() {
        try {
            Yuv420888SurfaceWriter.writeNv21ToPlanes(new byte[10], 640, 480, 640, 480,
                    ByteBuffer.allocate(1), 640, 1,
                    ByteBuffer.allocate(1), 320, 1,
                    ByteBuffer.allocate(1), 320, 1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
