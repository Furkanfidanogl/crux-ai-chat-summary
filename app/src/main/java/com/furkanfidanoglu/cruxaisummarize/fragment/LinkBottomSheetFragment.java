package com.furkanfidanoglu.cruxaisummarize.fragment;

import android.os.Bundle;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.databinding.BottomSheetLinkBinding;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class LinkBottomSheetFragment extends BottomSheetDialogFragment {

    private BottomSheetLinkBinding binding;
    private OnLinkSelectedListener listener;

    public interface OnLinkSelectedListener {
        void onLinkSummarize(String url);
    }

    public void setListener(OnLinkSelectedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = BottomSheetLinkBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Temiz başla
        binding.etLinkInput.setText("");

        binding.btnSummarizeLink.setOnClickListener(v -> {
            String rawLink = binding.etLinkInput.getText().toString().trim();

            // 1. BOŞ KONTROLÜ
            if (rawLink.isEmpty()) {
                Toast.makeText(getContext(), getString(R.string.msg_fill_all_fields), Toast.LENGTH_SHORT).show();
                return;
            }

            // 🔥 2. MAIL ENGELİ (YENİ EKLENDİ)
            // Burası link analiz yeri, adam mail adresi yazarsa scraper patlar.
            // O yüzden içinde '@' varsa veya 'mailto:' ile başlıyorsa kibarca reddediyoruz.
            if (rawLink.contains("@") || rawLink.startsWith("mailto:")) {
                Toast.makeText(requireContext(), getString(R.string.error_invalid_website_with_mail), Toast.LENGTH_SHORT).show();
                return;
            }

            // 3. HTTP EKSİKSE EKLE
            String link = rawLink;
            if (!link.startsWith("http://") && !link.startsWith("https://")) {
                link = "https://" + link;
            }

            // 4. GEÇERLİ URL Mİ?
            // "1. soru" gibi şeyleri burada da engelliyoruz.
            if (!Patterns.WEB_URL.matcher(link).matches()) {
                Toast.makeText(requireContext(), getString(R.string.error_invalid_website), Toast.LENGTH_SHORT).show();
                return;
            }

            String lowerLink = link.toLowerCase();

            // 5. YASAKLI PLATFORM KONTROLÜ
            if (isUnsupportedPlatform(lowerLink)) {
                Toast.makeText(getContext(), getString(R.string.msg_unsupported_link), Toast.LENGTH_LONG).show();
                return;
            }

            // Her şey yolundaysa gönder
            if (listener != null) {
                listener.onLinkSummarize(link);
            }
            dismiss();
        });
    }

    private boolean isUnsupportedPlatform(String url) {
        return url.contains("youtube.com") ||
                url.contains("youtu.be") ||
                url.contains("instagram.com") ||
                url.contains("tiktok.com") ||
                url.contains("twitter.com") ||
                url.contains("x.com") ||
                url.contains("facebook.com") ||
                url.contains("spotify.com") ||
                url.contains("netflix.com") ||
                url.contains("linkedin.com");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}