package com.furkanfidanoglu.cruxaisummarize.util.helpers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Memory-bounded image decoding for camera and Photo Picker content URIs. */
public final class ImageUtil {
    private static final int MAX_DIMENSION = 2048;
    private static final int COMPRESSION_QUALITY = 84;

    private ImageUtil() {}

    /**
     * Opens the source as streams, samples before decode and only returns the compact JPEG result.
     * The original image is never copied into a full-size byte array.
     */
    @Nullable
    public static byte[] processImage(@NonNull Context context, @NonNull Uri uri) {
        Bitmap decoded = null;
        Bitmap oriented = null;

        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) return null;
                BitmapFactory.decodeStream(stream, null, bounds);
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateInSampleSize(bounds, MAX_DIMENSION, MAX_DIMENSION);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inDither = true;

            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) return null;
                decoded = BitmapFactory.decodeStream(stream, null, options);
            }
            if (decoded == null) return null;

            int orientation = readOrientation(context, uri);
            oriented = applyOrientation(decoded, orientation);

            try (ByteArrayOutputStream output = new ByteArrayOutputStream(512 * 1024)) {
                if (!oriented.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, output)) {
                    return null;
                }
                return output.toByteArray();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            return null;
        } finally {
            if (oriented != null && oriented != decoded && !oriented.isRecycled()) oriented.recycle();
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
        }
    }

    private static int readOrientation(Context context, Uri uri) {
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) return ExifInterface.ORIENTATION_NORMAL;
            ExifInterface exif = new ExifInterface(stream);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap applyOrientation(Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;
            default:
                return bitmap;
        }

        try {
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return bitmap;
        }
    }

    static int calculateInSampleSize(BitmapFactory.Options options, int requiredWidth, int requiredHeight) {
        int sampleSize = 1;
        int width = options.outWidth;
        int height = options.outHeight;
        while ((width / (sampleSize * 2)) >= requiredWidth
                || (height / (sampleSize * 2)) >= requiredHeight) {
            sampleSize *= 2;
        }
        return sampleSize;
    }
}
