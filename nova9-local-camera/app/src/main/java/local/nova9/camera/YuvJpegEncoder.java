package local.nova9.camera;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

final class YuvJpegEncoder {
    private YuvJpegEncoder() {
    }

    static byte[] encode(Image image, int quality) {
        Rect crop = image.getCropRect();
        int width = crop.width();
        int height = crop.height();
        byte[] nv21 = toNv21(image, crop, width, height);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream(width * height / 2);
        if (!yuvImage.compressToJpeg(new Rect(0, 0, width, height), quality, output)) {
            throw new IllegalStateException("JPEG encoding failed");
        }
        return output.toByteArray();
    }

    private static byte[] toNv21(Image image, Rect crop, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        byte[] output = new byte[width * height * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8];

        for (int planeIndex = 0; planeIndex < planes.length; planeIndex++) {
            int shift = planeIndex == 0 ? 0 : 1;
            int planeWidth = width >> shift;
            int planeHeight = height >> shift;
            int outputOffset;
            int outputStride;

            if (planeIndex == 0) {
                outputOffset = 0;
                outputStride = 1;
            } else if (planeIndex == 1) {
                // Android plane 1 is U; NV21 stores VU.
                outputOffset = width * height + 1;
                outputStride = 2;
            } else {
                outputOffset = width * height;
                outputStride = 2;
            }

            Image.Plane plane = planes[planeIndex];
            ByteBuffer buffer = plane.getBuffer().duplicate();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            byte[] row = new byte[rowStride];

            int start = rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift);
            buffer.position(Math.min(start, buffer.limit()));

            for (int rowIndex = 0; rowIndex < planeHeight; rowIndex++) {
                int bytesRead;
                if (pixelStride == 1 && outputStride == 1) {
                    bytesRead = planeWidth;
                    buffer.get(output, outputOffset, bytesRead);
                    outputOffset += bytesRead;
                } else {
                    bytesRead = (planeWidth - 1) * pixelStride + 1;
                    buffer.get(row, 0, bytesRead);
                    for (int column = 0; column < planeWidth; column++) {
                        output[outputOffset] = row[column * pixelStride];
                        outputOffset += outputStride;
                    }
                }

                if (rowIndex < planeHeight - 1) {
                    int next = buffer.position() + rowStride - bytesRead;
                    buffer.position(Math.min(next, buffer.limit()));
                }
            }
        }

        return output;
    }
}

