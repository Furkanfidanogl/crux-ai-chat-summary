package com.furkanfidanoglu.cruxaisummarize.permission;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.google.android.material.snackbar.Snackbar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class GalleryPermission {

    private final Fragment fragment;
    private final ImageSelectionCallback callback;

    private ActivityResultLauncher<Intent> galleryLauncher;
    private ActivityResultLauncher<String> permissionLauncher;

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
        Context context = fragment.getContext();
        if (context == null) return;

        String permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_IMAGES;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        if (ContextCompat.checkSelfPermission(context, permission)
                != PackageManager.PERMISSION_GRANTED) {

            if (fragment.shouldShowRequestPermissionRationale(permission)) {
                Snackbar.make(fragment.requireView(),
                                fragment.getString(R.string.perm_gallery_required),
                                Snackbar.LENGTH_INDEFINITE)
                        .setAction(fragment.getString(R.string.action_allow),
                                v -> permissionLauncher.launch(permission))
                        .show();
            } else {
                permissionLauncher.launch(permission);
            }
        } else {
            openGallery();
        }
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

        permissionLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        openGallery();
                    } else {
                        Toast.makeText(fragment.getContext(),
                                fragment.getString(R.string.perm_denied),
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void processImage(Uri uri) {
        try {
            byte[] imageBytes = getBytesFromUri(uri, fragment.requireContext());
            if (imageBytes != null && callback != null) {
                callback.onImageSelected(imageBytes, uri);
            }
        } catch (IOException e) {
            Toast.makeText(fragment.getContext(),
                    fragment.getString(R.string.error_prefix) + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
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
                    Toast.makeText(context, context.getString(R.string.msg_image_large), Toast.LENGTH_SHORT).show();
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