package com.furkanfidanoglu.cruxaisummarize.permission;

import android.net.Uri;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.util.helpers.ContentUriUtil;

/** Selects CSV/Excel data without materializing the complete file on the main thread. */
public final class DataPermission {
    private static final long MAX_FILE_SIZE = 512L * 1024L;

    public interface DataCallback {
        void onDataSelected(Uri uri, String fileName, long fileSize);
    }

    private final Fragment fragment;
    private final DataCallback callback;
    private final ActivityResultLauncher<String[]> dataPickerLauncher;

    public DataPermission(Fragment fragment, DataCallback callback) {
        this.fragment = fragment;
        this.callback = callback;
        dataPickerLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::handleSelection);
    }

    public void checkPermissionsAndOpenPicker() {
        dataPickerLauncher.launch(new String[]{
                "text/comma-separated-values",
                "text/csv",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        });
    }

    private void handleSelection(Uri uri) {
        if (uri == null || !fragment.isAdded()) return;

        ContentUriUtil.Metadata metadata = ContentUriUtil.getMetadata(fragment.requireContext(), uri);
        if (metadata.size > MAX_FILE_SIZE) {
            Toast.makeText(fragment.requireContext(), R.string.msg_dataset_large, Toast.LENGTH_SHORT).show();
            return;
        }
        if (callback != null) {
            callback.onDataSelected(uri, metadata.displayName, metadata.size);
        }
    }
}
