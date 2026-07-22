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

public class DocPermission {

    private final Fragment fragment;
    private final DocCallback callback;
    private final ActivityResultLauncher<String[]> docPickerLauncher;

    private static final long MAX_FILE_SIZE = 8 * 1024 * 1024;

    public interface DocCallback {
        void onDocSelected(byte[] docBytes, String fileName);
    }

    public DocPermission(Fragment fragment, DocCallback callback) {
        this.fragment = fragment;
        this.callback = callback;

        docPickerLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        // A. ÖNCE BOYUT KONTROLÜ (Dosyayı okumadan!)
                        long fileSize = getFileSize(uri);

                        if (fileSize > MAX_FILE_SIZE) {
                            Toast.makeText(fragment.getContext(), fragment.getString(R.string.msg_file_large), Toast.LENGTH_SHORT).show();
                            return; // 🛑 Büyükse iptal et
                        }

                        // B. RAM'E ALMA (BACKGROUND THREAD)
                        new Thread(() -> {
                            byte[] docBytes = getBytesFromUri(uri);
                            String fileName = getFileName(uri);

                            if (fragment.isAdded()) {
                                fragment.requireActivity().runOnUiThread(() -> {
                                    if (docBytes != null) {
                                        callback.onDocSelected(docBytes, fileName);
                                    } else {
                                        Toast.makeText(fragment.getContext(), fragment.getString(R.string.error_read_doc), Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        }).start();
                    }
                }
        );
    }

    public void checkPermissionsAndOpenPicker() {
        openDocPicker();
    }

    private void openDocPicker() {
        docPickerLauncher.launch(new String[]{
                "application/pdf",
                "text/plain",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" // .docx
        });
    }

    // Dosyayı okumadan boyutunu öğrenen metod
    private long getFileSize(Uri uri) {
        long size = 0;
        try (Cursor cursor = fragment.requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (!cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return size;
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = fragment.requireContext().getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private byte[] getBytesFromUri(Uri uri) {
        Context context = fragment.getContext();
        if (context == null) return null;

        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream()) {

            if (inputStream == null) return null;

            int bufferSize = 8 * 1024;
            byte[] buffer = new byte[bufferSize];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                byteBuffer.write(buffer, 0, len);
            }
            return byteBuffer.toByteArray();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}