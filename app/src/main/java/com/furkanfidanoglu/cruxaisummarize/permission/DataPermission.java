package com.furkanfidanoglu.cruxaisummarize.permission;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.furkanfidanoglu.cruxaisummarize.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class DataPermission {

    private final Fragment fragment;
    private final DataCallback callback;
    private final ActivityResultLauncher<String[]> dataPickerLauncher;

    private static final int MAX_FILE_SIZE = 512 * 1024;

    public interface DataCallback {
        void onDataSelected(byte[] fileBytes, String fileName);
    }

    public DataPermission(Fragment fragment, DataCallback callback) {
        this.fragment = fragment;
        this.callback = callback;

        this.dataPickerLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        handleFileSelection(uri);
                    }
                }
        );
    }

    public void checkPermissionsAndOpenPicker() {
        // CSV ve Excel türleri
        String[] mimeTypes = {
                "text/comma-separated-values",
                "text/csv",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        };
        dataPickerLauncher.launch(mimeTypes);
    }

    private void handleFileSelection(Uri uri) {
        try {
            Context context = fragment.getContext();
            if (context == null) return;

            InputStream inputStream = context.getContentResolver().openInputStream(uri);
            ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
            int bufferSize = 1024;
            byte[] buffer = new byte[bufferSize];
            int len;
            int totalBytesRead = 0;

            // 🔥 DÖNGÜ İÇİ KONTROL (RAM KORUMASI)
            while ((len = inputStream.read(buffer)) != -1) {
                totalBytesRead += len;

                // Eğer dosya 512 KB'ı aştıysa okumayı DURDUR
                if (totalBytesRead > MAX_FILE_SIZE) {
                    inputStream.close();
                    // "Dosya çok büyük" uyarısı
                    Toast.makeText(context, context.getString(R.string.msg_dataset_large), Toast.LENGTH_SHORT).show();
                    return; // Callback çağrılmaz, Fragment tarafına null gitmez, işlem biter.
                }

                byteBuffer.write(buffer, 0, len);
            }

            byte[] fileBytes = byteBuffer.toByteArray();
            inputStream.close();

            String fileName = getFileName(context, uri);

            if (callback != null) {
                callback.onDataSelected(fileBytes, fileName);
            }

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(fragment.getContext(), fragment.getString(R.string.error_read_file), Toast.LENGTH_SHORT).show();
        }
    }

    private String getFileName(Context context, Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1) {
                        result = cursor.getString(index);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result;
    }
}