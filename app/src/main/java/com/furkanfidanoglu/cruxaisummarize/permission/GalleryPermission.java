package com.furkanfidanoglu.cruxaisummarize.permission;

import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

/** Opens Android's privacy-friendly photo picker without reading the image into RAM. */
public final class GalleryPermission {

    public interface ImageSelectionCallback {
        void onImageSelected(Uri imageUri);
    }

    private final ImageSelectionCallback callback;
    private final ActivityResultLauncher<PickVisualMediaRequest> galleryLauncher;

    public GalleryPermission(Fragment fragment, ImageSelectionCallback callback) {
        this.callback = callback;
        galleryLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> {
                    if (uri != null && this.callback != null) {
                        this.callback.onImageSelected(uri);
                    }
                });
    }

    public void checkPermissionsAndOpenGallery() {
        galleryLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }
}
