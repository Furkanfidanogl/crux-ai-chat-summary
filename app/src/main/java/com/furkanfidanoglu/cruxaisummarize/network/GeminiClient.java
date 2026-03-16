package com.furkanfidanoglu.cruxaisummarize.network;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.data.model.MessageModel;
import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.ChatFutures;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Candidate;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.FinishReason;
import com.google.ai.client.generativeai.type.GenerateContentResponse;
import com.google.ai.client.generativeai.type.GenerationConfig;
import com.google.ai.client.generativeai.type.RequestOptions;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class GeminiClient {

    private static final String TAG = "GeminiClient";
    private static final int MAX_RETRIES = 3;
    private static final int MAX_CONCURRENT_REQUESTS = 2; // Aynı anda max 2 istek

    private static GeminiClient instance;
    private final Context context;
    private static final AtomicInteger activeRequests = new AtomicInteger(0);

    public static synchronized GeminiClient getInstance(Context context) {
        if (instance == null) {
            instance = new GeminiClient(context.getApplicationContext());
        }
        return instance;
    }

    private GenerativeModelFutures model;
    private ChatFutures chat;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isInitializing = new AtomicBoolean(false);
    private String currentApiKey;

    private GeminiClient(Context context) {
        this.context = context;
        fetchConfigAndInitModel();
    }

    private void fetchConfigAndInitModel() {
        if (isInitializing.get()) return;
        isInitializing.set(true);

        String apiKey = com.furkanfidanoglu.cruxaisummarize.BuildConfig.GEMINI_API_KEY;

        // 1. Önce Kasayı Kontrol Et
        if (apiKey == null || apiKey.isEmpty()) {
            Log.e(TAG, "CRITICAL ERROR: API Key in BuildConfig is missing!");
            isInitializing.set(false);
            return;
        }

        // 2. Remote Config Başlat (Firestore Silindi!)
        com.google.firebase.remoteconfig.FirebaseRemoteConfig mFirebaseRemoteConfig = com.google.firebase.remoteconfig.FirebaseRemoteConfig.getInstance();
        com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings configSettings = new com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings.Builder()
                // DİKKAT: Test aşamasında anında görmek için burayı 0 yapabilirsin.
                // Play Store'a atarken tekrar 3600 (1 saat) yap ki boşuna yorulmasın.
                .setMinimumFetchIntervalInSeconds(3600)
                .build();
        mFirebaseRemoteConfig.setConfigSettingsAsync(configSettings);

        // 3. Buluttan Promptu Çek
        mFirebaseRemoteConfig.fetchAndActivate()
                .addOnCompleteListener(executor, task -> { // Executor ile arka plana aldık, UI kasmaz
                    isInitializing.set(false);

                    if (task.isSuccessful()) {
                        // 🟢 BAŞARILI: Yeni prompt buluttan geldi!
                        String systemPrompt = mFirebaseRemoteConfig.getString("system_prompt");
                        Log.d(TAG, "Remote Config'den prompt başarıyla çekildi.");

                        // Eğer Firebase'de yanlışlıkla boş bırakıldıysa, null yap ki kendi fallback promptun devreye girsin
                        if (systemPrompt.trim().isEmpty()) {
                            systemPrompt = null;
                        }

                        initializeModel(apiKey, systemPrompt);

                    } else {
                        // Cihazın hafızasında kalan (daha önceden çekilmiş) bir prompt varsa onu kullanırız.
                        String cachedPrompt = mFirebaseRemoteConfig.getString("system_prompt");
                        String finalPrompt = cachedPrompt.trim().isEmpty() ? null : cachedPrompt;

                        initializeModel(apiKey, finalPrompt);
                    }
                });
    }
    private synchronized void initializeModel(String apiKey, String firebasePrompt) {
        if (apiKey.equals(currentApiKey) && model != null) return;

        currentApiKey = apiKey;

        try {
            String finalPrompt = (firebasePrompt != null && !firebasePrompt.isEmpty())
                    ? firebasePrompt
                    : "You are CruxAI. Help the user safely.";

            Content systemInstruction = new Content.Builder()
                    .addText(finalPrompt)
                    .build();

            GenerationConfig.Builder configBuilder = new GenerationConfig.Builder();
            configBuilder.maxOutputTokens = 8192;
            configBuilder.temperature = 0.7f;
            configBuilder.topP = 0.9f;
            GenerationConfig config = configBuilder.build();

            GenerativeModel gm = new GenerativeModel(
                    "gemini-3.1-flash-lite-preview",
                    apiKey,
                    config,
                    null,
                    new RequestOptions(),
                    null,
                    null,
                    systemInstruction
            );

            model = GenerativeModelFutures.from(gm);
            chat = model.startChat();
            Log.d(TAG, "Gemini model initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Model initialization failed: " + e.getMessage());
            model = null;
            chat = null;
        }
    }

    public synchronized void loadHistory(List<MessageModel> messages) {
        if (model == null || messages == null || messages.isEmpty()) return;

        List<Content> history = new ArrayList<>();

        for (MessageModel msg : messages) {
            if (msg == null || "loading".equals(msg.getId())) continue;

            String role = msg.getRole();
            String text = msg.getContent();

            if (text == null || text.trim().isEmpty()) {
                if (msg.getFileName() != null) {
                    text = "[User uploaded file: " + msg.getFileName() + "]";
                } else {
                    continue;
                }
            }

            Content.Builder builder = new Content.Builder();

            if ("user".equalsIgnoreCase(role)) {
                builder.setRole("user");
            } else if ("model".equalsIgnoreCase(role)) {
                builder.setRole("model");
            } else {
                continue;
            }

            builder.addText(text);
            history.add(builder.build());
        }

        if (!history.isEmpty()) {
            try {
                chat = model.startChat(history);
                Log.i(TAG, "Chat history loaded: " + history.size());
            } catch (Exception e) {
                Log.e(TAG, "Failed to load chat history: " + e.getMessage());
                chat = model.startChat(); // Fallback
            }
        }
    }

    public void sendMessage(
            String userText,
            byte[] mediaBytes,
            String mediaType,
            GeminiCallback callback
    ) {
        // 🔥 CRITICAL FIX: Aynı anda çok istek gelirse kuyruğa al
        if (activeRequests.get() >= MAX_CONCURRENT_REQUESTS) {
            callback.onError(new GeminiException(
                    GeminiErrorType.OVERLOADED,
                    context.getString(R.string.system_busy)
            ));
            return;
        }

        activeRequests.incrementAndGet();
        sendMessageInternal(userText, mediaBytes, mediaType, callback, 0, System.currentTimeMillis());
    }

    private void sendMessageInternal(
            String userText,
            byte[] mediaBytes,
            String mediaType,
            GeminiCallback callback,
            int retryCount,
            long startTime
    ) {
        // 🔥 CRITICAL FIX: 15 saniyeden uzun süren istekleri iptal et
        if (System.currentTimeMillis() - startTime > 15000) {
            activeRequests.decrementAndGet();
            callback.onError(new GeminiException(
                    GeminiErrorType.TIMEOUT,
                    context.getString(R.string.request_timeout)
            ));
            return;
        }

        if (model == null) {
            // Model yoksa yeniden başlatmayı dene
            fetchConfigAndInitModel();

            if (retryCount == 0) {
                // İlk denemede model yoksa, 1 saniye bekle ve tekrar dene
                executor.execute(() -> {
                    try {
                        Thread.sleep(1000);
                        sendMessageInternal(userText, mediaBytes, mediaType, callback, 1, startTime);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        activeRequests.decrementAndGet();
                        callback.onError(new GeminiException(
                                GeminiErrorType.INITIALIZING,
                                context.getString(R.string.error_initializing)
                        ));
                    }
                });
                return;
            } else {
                activeRequests.decrementAndGet();
                callback.onError(new GeminiException(
                        GeminiErrorType.INITIALIZING,
                        context.getString(R.string.error_initializing)
                ));
                return;
            }
        }

        if (chat == null) {
            try {
                chat = model.startChat();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start chat: " + e.getMessage());
                activeRequests.decrementAndGet();
                callback.onError(new GeminiException(
                        GeminiErrorType.UNKNOWN,
                        context.getString(R.string.error_unknown)
                ));
                return;
            }
        }

        Content.Builder contentBuilder = new Content.Builder();

        if (userText != null && !userText.trim().isEmpty()) {
            contentBuilder.addText(userText);
        }

        if (mediaBytes != null && mediaType != null) {
            try {
                switch (mediaType) {
                    case "IMAGE":
                        Bitmap bitmap = decodeSafeBitmap(mediaBytes);
                        if (bitmap != null) contentBuilder.addImage(bitmap);
                        break;
                    case "AUDIO":
                        contentBuilder.addBlob("audio/mp3", mediaBytes);
                        break;
                    case "DOC":
                        if (isPdf(mediaBytes)) {
                            contentBuilder.addBlob("application/pdf", mediaBytes);
                        } else {
                            // Büyük dosyaları 8000 karakterle sınırla
                            String docText = new String(mediaBytes);
                            if (docText.length() > 8000) {
                                docText = docText.substring(0, 8000) + "\n...[truncated]";
                            }
                            contentBuilder.addText("Document Content:\n" + docText);
                        }
                        break;
                }
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "Out of memory processing media", e);
                System.gc();
                // Medya hatasında bile text'i gönder
                if (userText != null && !userText.trim().isEmpty()) {
                    contentBuilder = new Content.Builder().addText(userText);
                } else {
                    activeRequests.decrementAndGet();
                    callback.onError(new GeminiException(
                            GeminiErrorType.UNKNOWN,
                            context.getString(R.string.error_media_too_large)
                    ));
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Media processing error: " + e.getMessage());
                // Medya hatasında bile text'i gönder
                if (userText != null && !userText.trim().isEmpty()) {
                    contentBuilder = new Content.Builder().addText(userText);
                } else {
                    activeRequests.decrementAndGet();
                    callback.onError(new GeminiException(
                            GeminiErrorType.UNKNOWN,
                            context.getString(R.string.error_media)
                    ));
                    return;
                }
            }
        }

        try {
            ListenableFuture<GenerateContentResponse> future =
                    chat.sendMessage(contentBuilder.build());

            Futures.addCallback(future, new FutureCallback<GenerateContentResponse>() {
                @Override
                public void onSuccess(GenerateContentResponse result) {
                    activeRequests.decrementAndGet();

                    if (result == null || result.getCandidates() == null || result.getCandidates().isEmpty()) {
                        // Boş response için otomatik retry
                        if (retryCount < MAX_RETRIES) {
                            scheduleRetry(userText, mediaBytes, mediaType, callback, retryCount + 1, startTime);
                        } else {
                            callback.onError(new GeminiException(
                                    GeminiErrorType.EMPTY_RESPONSE,
                                    context.getString(R.string.error_empty)
                            ));
                        }
                        return;
                    }

                    Candidate candidate = result.getCandidates().get(0);
                    FinishReason reason = candidate.getFinishReason();

                    if (reason == FinishReason.SAFETY) {
                        callback.onError(new GeminiException(
                                GeminiErrorType.SAFETY,
                                context.getString(R.string.error_safety)
                        ));
                        return;
                    }

                    if (reason == FinishReason.RECITATION) {
                        callback.onError(new GeminiException(
                                GeminiErrorType.SAFETY,
                                context.getString(R.string.error_recitation)
                        ));
                        return;
                    }

                    String text = result.getText();
                    if (text != null && !text.trim().isEmpty()) {
                        callback.onSuccess(text);
                    } else if (retryCount < MAX_RETRIES) {
                        // Boş text için retry
                        scheduleRetry(userText, mediaBytes, mediaType, callback, retryCount + 1, startTime);
                    } else {
                        callback.onError(new GeminiException(
                                GeminiErrorType.EMPTY_RESPONSE,
                                context.getString(R.string.error_empty)
                        ));
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    activeRequests.decrementAndGet();

                    // Akıllı retry kararı
                    if (shouldRetry(t) && retryCount < MAX_RETRIES) {
                        scheduleRetry(userText, mediaBytes, mediaType, callback, retryCount + 1, startTime);
                    } else {
                        callback.onError(mapToFriendlyException(t));
                    }
                }
            }, executor);
        } catch (Exception e) {
            activeRequests.decrementAndGet();
            Log.e(TAG, "Send message exception: " + e.getMessage());
            callback.onError(new GeminiException(
                    GeminiErrorType.UNKNOWN,
                    context.getString(R.string.error_unknown)
            ));
        }
    }

    private void scheduleRetry(
            String userText,
            byte[] mediaBytes,
            String mediaType,
            GeminiCallback callback,
            int nextRetry,
            long startTime
    ) {
        long delay = nextRetry * 2000L; // 2, 4, 6 saniye

        Log.w(TAG, "Scheduling retry " + nextRetry + " in " + delay + "ms");

        executor.execute(() -> {
            try {
                Thread.sleep(delay);
                sendMessageInternal(userText, mediaBytes, mediaType, callback, nextRetry, startTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callback.onError(new GeminiException(
                        GeminiErrorType.UNKNOWN,
                        context.getString(R.string.error_unknown)
                ));
            }
        });
    }

    private boolean shouldRetry(Throwable t) {
        String msg = safeLower(t.getMessage());
        String trace = Log.getStackTraceString(t);

        // Retry edilebilir hatalar
        return msg.contains("503") || // Service Unavailable
                msg.contains("504") || // Gateway Timeout
                msg.contains("500") || // Internal Server Error
                msg.contains("overloaded") ||
                msg.contains("timeout") ||
                msg.contains("deadline") ||
                msg.contains("socket") ||
                msg.contains("connection") ||
                msg.contains("network") ||
                trace.contains("java.net") ||
                msg.contains("unavailable") ||
                msg.contains("ioexception");
    }

    private GeminiException mapToFriendlyException(Throwable t) {
        String msg = safeLower(t.getMessage());
        Log.w(TAG, "API Error: " + msg);

        if (msg.contains("503") || msg.contains("overloaded")) {
            return new GeminiException(GeminiErrorType.NETWORK,
                    context.getString(R.string.error_503));
        }
        if (msg.contains("504") || msg.contains("timeout") || msg.contains("deadline")) {
            return new GeminiException(GeminiErrorType.TIMEOUT,
                    context.getString(R.string.error_timeout));
        }
        if (msg.contains("429") || msg.contains("quota")) {
            return new GeminiException(GeminiErrorType.QUOTA,
                    context.getString(R.string.error_429));
        }
        if (msg.contains("401") || msg.contains("403") || msg.contains("api key")) {
            return new GeminiException(GeminiErrorType.AUTH,
                    context.getString(R.string.error_auth));
        }
        if (msg.contains("safety") || msg.contains("blocked")) {
            return new GeminiException(GeminiErrorType.SAFETY,
                    context.getString(R.string.error_safety));
        }
        if (msg.contains("network") || msg.contains("connect") || msg.contains("unreachable")) {
            return new GeminiException(GeminiErrorType.NETWORK,
                    context.getString(R.string.error_internet));
        }
        if (msg.contains("404")) {
            return new GeminiException(GeminiErrorType.NETWORK,
                    context.getString(R.string.error_404));
        }

        // Genel hata
        return new GeminiException(GeminiErrorType.UNKNOWN,
                context.getString(R.string.error_unknown));
    }

    private String getStringSafe(int resId) {
        if (context != null) {
            try {
                return context.getString(resId);
            } catch (Exception e) {
                return "Error occurred";
            }
        }
        return "Error (no context)";
    }

    private Bitmap decodeSafeBitmap(byte[] bytes) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            opts.inPreferredConfig = Bitmap.Config.RGB_565; // Bellek optimizasyonu
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory decoding bitmap");
            System.gc();
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Bitmap decode failed: " + e.getMessage());
            return null;
        }
    }

    private boolean isPdf(byte[] data) {
        return data != null &&
                data.length >= 4 &&
                data[0] == 0x25 && // %
                data[1] == 0x50 && // P
                data[2] == 0x44 && // D
                data[3] == 0x46;   // F
    }

    private String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    public synchronized void resetChat() {
        if (model != null) {
            chat = model.startChat();
        }
    }

    public static void clearInstance() {
        instance = null;
    }

    // 🔥 YENİ: Request counter'ı sıfırla
    public static void resetRequestCounter() {
        activeRequests.set(0);
    }

    public interface GeminiCallback {
        void onSuccess(String response);

        void onError(Throwable t);
    }

    public enum GeminiErrorType {
        NETWORK, QUOTA, AUTH, SAFETY, EMPTY_RESPONSE,
        INITIALIZING, UNKNOWN, TIMEOUT, OVERLOADED
    }

    public static class GeminiException extends Exception {
        public final GeminiErrorType type;

        public GeminiException(GeminiErrorType type, String message) {
            super(message);
            this.type = type;
        }

        public GeminiException(GeminiErrorType type, Throwable cause) {
            super(cause);
            this.type = type;
        }
    }
}