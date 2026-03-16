package com.furkanfidanoglu.cruxaisummarize.util.helpers;

import android.os.Handler;
import android.os.Looper;
import android.util.Patterns;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class WebScraper {

    public interface ScrapeCallback {
        void onSuccess(String cleanContent);
        void onError(String error);
    }

    public static void scrapeUrl(String rawUrl, ScrapeCallback callback) {
        // UI Thread Handler'ı (Çökme Önleyici)
        Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                // 🛡️ 1. GÜVENLİK DUVARI: URL ve Mail Kontrolü
                if (rawUrl == null || rawUrl.trim().isEmpty()) {
                    mainHandler.post(() -> callback.onError("Empty URL"));
                    return;
                }

                String url = rawUrl.trim();

                // Eğer "1." veya "abc@gmail.com" geldiyse burası yakalar ve reddeder.
                // Sadece geçerli WEB URL'lerine izin veriyoruz.
                if (!Patterns.WEB_URL.matcher(url).matches() && !url.startsWith("http")) {
                    // Mail ise veya saçma sapan bir şeyse (örn: "1.") sessizce hata dön
                    mainHandler.post(() -> callback.onError("Invalid Web URL"));
                    return;
                }

                // Protokol eksikse ekle
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }

                // 🌐 2. BAĞLANTI (Jsoup)
                Document doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                        .timeout(10000)
                        .get();

                // 🧹 3. TEMİZLİK
                doc.select("nav, header, footer, script, style, noscript, aside, .sidebar, .menu, .ad, .advertisement, .popup, .comments, .related").remove();

                String cleanText = "";

                // İçerik Yakalama
                Elements article = doc.select("article");
                if (!article.isEmpty()) {
                    cleanText = article.text();
                } else {
                    Elements main = doc.select("main, #content, .content, .entry-content, .post-body");
                    if (!main.isEmpty()) {
                        cleanText = main.text();
                    } else {
                        cleanText = doc.select("p").text();
                    }
                }

                // Yedek Plan (Body)
                if (cleanText.trim().length() < 200) {
                    String bodyText = doc.body().text();
                    if (bodyText.length() > cleanText.length()) cleanText = bodyText;
                }

                // SPA/React Desteği
                if (cleanText.trim().length() < 100) {
                    String title = doc.title();
                    String description = "";
                    Element metaDesc = doc.select("meta[name=description]").first();
                    if (metaDesc != null) description = metaDesc.attr("content");
                    if (description.isEmpty()) {
                        Element ogDesc = doc.select("meta[property=og:description]").first();
                        if (ogDesc != null) description = ogDesc.attr("content");
                    }
                    cleanText = "PAGE TITLE: " + title + "\n\nSUMMARY: " + description;
                }

                // Limit
                if (cleanText.length() > 8000) {
                    cleanText = cleanText.substring(0, 8000) + "... (Content Truncated)";
                }

                String finalResult = cleanText;

                // ✅ 4. SONUCU GÜVENLE DÖNDÜR (Main Thread)
                if (finalResult.trim().isEmpty()) {
                    mainHandler.post(() -> callback.onError("No readable text found."));
                } else {
                    mainHandler.post(() -> callback.onSuccess(finalResult));
                }

            } catch (Exception e) {
                // Hata durumunda da Main Thread
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }).start();
    }
}