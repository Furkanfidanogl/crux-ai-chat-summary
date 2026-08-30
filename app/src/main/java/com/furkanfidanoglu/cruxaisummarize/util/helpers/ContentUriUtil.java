package com.furkanfidanoglu.cruxaisummarize.util.helpers;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;

/** Small, safe helpers for metadata exposed by Android's Storage Access Framework. */
public final class ContentUriUtil {
    private ContentUriUtil() {}

    @NonNull
    public static Metadata getMetadata(@NonNull Context context, @NonNull Uri uri) {
        String name = uri.getLastPathSegment();
        long size = -1L;

        try (Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } catch (RuntimeException ignored) {
            // Some document providers do not expose metadata. Bounded consumers still validate input.
        }

        if (size < 0L) {
            try (AssetFileDescriptor descriptor = context.getContentResolver()
                    .openAssetFileDescriptor(uri, "r")) {
                if (descriptor != null) size = descriptor.getLength();
            } catch (Exception ignored) {
                size = -1L;
            }
        }

        if (name == null || name.trim().isEmpty()) name = "attachment";
        return new Metadata(name, size);
    }

    public static final class Metadata {
        public final String displayName;
        public final long size;

        public Metadata(String displayName, long size) {
            this.displayName = displayName;
            this.size = size;
        }
    }
}
