package com.furkanfidanoglu.cruxaisummarize.permission;

import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.util.helpers.ContentUriUtil;

/** Selects audio by URI so large files are never duplicated in application memory. */
public final class AudioPermission {
    private static final long MAX_FILE_SIZE = 10L * 1024L * 1024L;

    public interface AudioCallback {
        void onAudioSelected(Uri uri, String fileName, long fileSize);
    }

    private final Fragment fragment;
    private final AudioCallback callback;
    private final ActivityResultLauncher<String[]> audioPickerLauncher;

    public AudioPermission(Fragment fragment, AudioCallback callback) {
        this.fragment = fragment;
        this.callback = callback;
        audioPickerLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::handleSelection);
    }

    public void checkPermissionsAndOpenPicker() {
        audioPickerLauncher.launch(new String[]{"audio/*"});
    }

    private void handleSelection(Uri uri) {
        if (uri == null || !fragment.isAdded()) return;

        ContentUriUtil.Metadata metadata = ContentUriUtil.getMetadata(fragment.requireContext(), uri);
        if (metadata.size > MAX_FILE_SIZE) {
            Toast.makeText(fragment.requireContext(), R.string.msg_audio_large, Toast.LENGTH_SHORT).show();
            return;
        }
        if (callback != null) {
            callback.onAudioSelected(uri, metadata.displayName, metadata.size);
        }
    }
}
