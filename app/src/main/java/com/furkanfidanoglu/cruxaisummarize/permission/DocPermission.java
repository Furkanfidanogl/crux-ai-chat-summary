package com.furkanfidanoglu.cruxaisummarize.permission;

import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.util.helpers.ContentUriUtil;

/** Selects documents by URI and defers all I/O to the consumer's background executor. */
public final class DocPermission {
    private static final long MAX_FILE_SIZE = 8L * 1024L * 1024L;

    public interface DocCallback {
        void onDocSelected(Uri uri, String fileName, long fileSize);
    }

    private final Fragment fragment;
    private final DocCallback callback;
    private final ActivityResultLauncher<String[]> docPickerLauncher;

    public DocPermission(Fragment fragment, DocCallback callback) {
        this.fragment = fragment;
        this.callback = callback;
        docPickerLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::handleSelection);
    }

    public void checkPermissionsAndOpenPicker() {
        docPickerLauncher.launch(new String[]{
                "application/pdf",
                "text/plain",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        });
    }

    private void handleSelection(Uri uri) {
        if (uri == null || !fragment.isAdded()) return;

        ContentUriUtil.Metadata metadata = ContentUriUtil.getMetadata(fragment.requireContext(), uri);
        if (metadata.size > MAX_FILE_SIZE) {
            Toast.makeText(fragment.requireContext(), R.string.msg_file_large, Toast.LENGTH_SHORT).show();
            return;
        }
        if (callback != null) {
            callback.onDocSelected(uri, metadata.displayName, metadata.size);
        }
    }
}
