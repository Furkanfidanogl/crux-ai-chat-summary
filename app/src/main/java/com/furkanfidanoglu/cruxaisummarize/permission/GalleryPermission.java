package com.furkanfidanoglu.cruxaisummarize.permission;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.furkanfidanoglu.cruxaisummarize.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class GalleryPermission {

    private final Fragment fragment;
    private final ImageSelectionCallback callback;

    private ActivityResultLauncher<Intent> galleryLauncher;

    private static final int MAX_FILE_SIZE = 50 * 1024 * 1024;

    public interface ImageSelectionCallback {
        void onImageSelected(byte[] imageBytes, Uri originalUri);
    }

    public GalleryPermission(Fragment fragment, ImageSelectionCallback callback) {
        this.fragment = fragment;
        this.callback = callback;
        registerLaunchers();
    }

    public void checkPermissionsAndOpenGallery() {
        openGallery();
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        galleryLauncher.launch(intent);
    }

    private void registerLaunchers() {
        galleryLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == -1 && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) processImage(uri);
                    }
                });
    }

    private void processImage(Uri uri) {
        new Thread(() -> {
            try {
                Context context = fragment.getContext();
                if (context == null) return;
                byte[] imageBytes = getBytesFromUri(uri, context);
                if (fragment.isAdded()) {
                    fragment.requireActivity().runOnUiThread(() -> {
                        if (imageBytes != null && callback != null) {
                            callback.onImageSelected(imageBytes, uri);
                        }
                    });
                }
            } catch (IOException e) {
                if (fragment.isAdded()) {
                    fragment.requireActivity().runOnUiThread(() -> {
                        Toast.makeText(fragment.getContext(),
                                fragment.getString(R.string.error_prefix) + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    });
                }
            }
        }).start();
    }

    private byte[] getBytesFromUri(Uri uri, Context context) throws IOException {
        InputStream inputStream = context.getContentResolver().openInputStream(uri);
        if (inputStream == null) return null;

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192]; // Buffer'ı 4096'dan 8192'ye çıkardım (Büyük dosya daha hızlı okunsun)
        int nRead;
        int totalBytesRead = 0;

        try {
            // 🔥 ARTIK 50 MB'a KADAR OKUYACAK
            while ((nRead = inputStream.read(data)) != -1) {
                totalBytesRead += nRead;

                if (totalBytesRead > MAX_FILE_SIZE) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        Toast.makeText(context, context.getString(R.string.msg_image_large), Toast.LENGTH_SHORT).show();
                    });
                    return null; // Null dönerek işlemi iptal et
                }

                buffer.write(data, 0, nRead);
            }
            return buffer.toByteArray();
        } finally {
            inputStream.close();
            buffer.close();
        }
    }
}