package com.furkanfidanoglu.cruxaisummarize.network;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.data.model.MessageModel;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class GeminiClient {

    private static final String TAG = "GeminiClient";
    private static final int MAX_RETRIES = 3;
    private static final int MAX_CONCURRENT_REQUESTS = 2;
    private static final String FUNCTION_NAME = "processGemini";

    private static GeminiClient instance;
    private final Context context;
    private final FirebaseFunctions functions;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private static final AtomicInteger activeRequests = new AtomicInteger(0);

    // In-memory chat history for context
    private final List<Map<String, String>> chatHistory = new ArrayList<>();

    public static synchronized GeminiClient getInstance(Context context) {
        if (instance == null) {
            instance = new GeminiClient(context.getApplicationContext());
        }
        return instance;
    }

    private GeminiClient(Context context) {
        this.context = context;
        this.functions = FirebaseFunctions.getInstance();
    }

    // ─── HISTORY MANAGEMENT ─────────────────────────────────────
    public synchronized void loadHistory(List<MessageModel> messages) {
        chatHistory.clear();
        if (messages == null || messages.isEmpty()) return;

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

            if (!"user".equalsIgnoreCase(role) && !"model".equalsIgnoreCase(role)) continue;

            Map<String, String> entry = new HashMap<>();
            entry.put("role", role.toLowerCase(Locale.US));
            entry.put("text", text);
            chatHistory.add(entry);
        }

        Log.i(TAG, "Chat history loaded: " + chatHistory.size());
    }

    public synchronized void resetChat() {
        chatHistory.clear();
    }

    // ─── SEND MESSAGE ───────────────────────────────────────────
    public void sendMessage(
            String userText,
            byte[] mediaBytes,
            String mediaType,
            GeminiCallback callback
    ) {
        if (activeRequests.get() >= MAX_CONCURRENT_REQUESTS) {
            callback.onError(new GeminiException(
                    GeminiErrorType.OVERLOADED,
                    context.getString(R.string.system_busy)
            ));
            return;
        }

        activeRequests.incrementAndGet();
        sendWithRetry(userText, mediaBytes, mediaType, callback, 0, System.currentTimeMillis());
    }

    private void sendWithRetry(
            String userText,
            byte[] mediaBytes,
            String mediaType,
            GeminiCallback callback,
            int retryCount,
            long startTime
    ) {
        // Timeout guard: 30 seconds (server has 60s, but we give client 30s)
        if (System.currentTimeMillis() - startTime > 30000) {
            activeRequests.decrementAndGet();
            callback.onError(new GeminiException(
                    GeminiErrorType.TIMEOUT,
                    context.getString(R.string.request_timeout)
            ));
            return;
        }

        // ── Build payload ──
        Map<String, Object> payload = new HashMap<>();

        if (userText != null && !userText.trim().isEmpty()) {
            payload.put("text", userText);
        }

        if (mediaBytes != null && mediaType != null) {
            try {
                String base64 = Base64.encodeToString(mediaBytes, Base64.NO_WRAP);
                payload.put("mediaBase64", base64);
                payload.put("mediaType", mediaType);

                // Resolve MIME type from mediaType
                String mimeType = resolveMimeType(mediaType, mediaBytes);
                if (mimeType != null) {
                    payload.put("mimeType", mimeType);
                }
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "OOM encoding media to base64", e);
                System.gc();
                // Fall back to text-only if we have text
                if (userText == null || userText.trim().isEmpty()) {
                    activeRequests.decrementAndGet();
                    callback.onError(new GeminiException(
                            GeminiErrorType.UNKNOWN,
                            context.getString(R.string.error_media_too_large)
                    ));
                    return;
                }
                // Remove media from payload, send text only
                payload.remove("mediaBase64");
                payload.remove("mediaType");
                payload.remove("mimeType");
            }
        }

        // ── Attach chat history ──
        synchronized (this) {
            if (!chatHistory.isEmpty()) {
                payload.put("history", new ArrayList<>(chatHistory));
            }
        }

        // ── Call Cloud Function ──
        functions.getHttpsCallable(FUNCTION_NAME)
                .call(payload)
                .addOnSuccessListener(executor, result -> {
                    activeRequests.decrementAndGet();

                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> data = (Map<String, Object>) result.getData();
                        String response = (data != null) ? (String) data.get("response") : null;

                        if (response != null && !response.trim().isEmpty()) {
                            // Update local history
                            synchronized (GeminiClient.this) {
                                if (userText != null && !userText.trim().isEmpty()) {
                                    Map<String, String> userEntry = new HashMap<>();
                                    userEntry.put("role", "user");
                                    userEntry.put("text", userText);
                                    chatHistory.add(userEntry);
                                }
                                Map<String, String> modelEntry = new HashMap<>();
                                modelEntry.put("role", "model");
                                modelEntry.put("text", response);
                                chatHistory.add(modelEntry);
                            }

                            callback.onSuccess(response);
                        } else if (retryCount < MAX_RETRIES) {
                            scheduleRetry(userText, mediaBytes, mediaType, callback, retryCount + 1, startTime);
                        } else {
                            callback.onError(new GeminiException(
                                    GeminiErrorType.EMPTY_RESPONSE,
                                    context.getString(R.string.error_empty)
                            ));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Response parsing error: " + e.getMessage());
                        callback.onError(new GeminiException(
                                GeminiErrorType.UNKNOWN,
                                context.getString(R.string.error_unknown)
                        ));
                    }
                })
                .addOnFailureListener(executor, e -> {
                    activeRequests.decrementAndGet();

                    if (shouldRetry(e) && retryCount < MAX_RETRIES) {
                        scheduleRetry(userText, mediaBytes, mediaType, callback, retryCount + 1, startTime);
                    } else {
                        callback.onError(mapToFriendlyException(e));
                    }
                });
    }

    // ─── MIME TYPE HELPER ───────────────────────────────────────
    private String resolveMimeType(String mediaType, byte[] data) {
        switch (mediaType) {
            case "IMAGE":
                return "image/jpeg";
            case "AUDIO":
                return "audio/mp3";
            case "DOC":
                if (isPdf(data)) return "application/pdf";
                return "text/plain";
            default:
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

    // ─── RETRY LOGIC ────────────────────────────────────────────
    private void scheduleRetry(
            String userText,
            byte[] mediaBytes,
            String mediaType,
            GeminiCallback callback,
            int nextRetry,
            long startTime
    ) {
        long delay = nextRetry * 2000L; // 2s, 4s, 6s
        Log.w(TAG, "Scheduling retry " + nextRetry + " in " + delay + "ms");

        executor.execute(() -> {
            try {
                Thread.sleep(delay);
                sendWithRetry(userText, mediaBytes, mediaType, callback, nextRetry, startTime);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                callback.onError(new GeminiException(
                        GeminiErrorType.UNKNOWN,
                        context.getString(R.string.error_unknown)
                ));
            }
        });
    }

    private boolean shouldRetry(Throwable t) {
        if (t instanceof FirebaseFunctionsException) {
            FirebaseFunctionsException ffe = (FirebaseFunctionsException) t;
            FirebaseFunctionsException.Code code = ffe.getCode();
            return code == FirebaseFunctionsException.Code.UNAVAILABLE ||
                    code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ||
                    code == FirebaseFunctionsException.Code.INTERNAL;
        }

        String msg = safeLower(t.getMessage());
        return msg.contains("timeout") ||
                msg.contains("network") ||
                msg.contains("connection") ||
                msg.contains("socket");
    }

    // ─── ERROR MAPPING ──────────────────────────────────────────
    private GeminiException mapToFriendlyException(Throwable t) {
        if (t instanceof FirebaseFunctionsException) {
            FirebaseFunctionsException ffe = (FirebaseFunctionsException) t;
            String serverMsg = safeLower(ffe.getMessage());

            switch (ffe.getCode()) {
                case UNAUTHENTICATED:
                    return new GeminiException(GeminiErrorType.AUTH,
                            context.getString(R.string.error_auth));

                case PERMISSION_DENIED:
                    if (serverMsg.contains("safety")) {
                        return new GeminiException(GeminiErrorType.SAFETY,
                                context.getString(R.string.error_safety));
                    }
                    if (serverMsg.contains("recitation")) {
                        return new GeminiException(GeminiErrorType.SAFETY,
                                context.getString(R.string.error_recitation));
                    }
                    return new GeminiException(GeminiErrorType.AUTH,
                            context.getString(R.string.error_auth));

                case RESOURCE_EXHAUSTED:
                    return new GeminiException(GeminiErrorType.QUOTA,
                            context.getString(R.string.error_429));

                case UNAVAILABLE:
                    return new GeminiException(GeminiErrorType.NETWORK,
                            context.getString(R.string.error_503));

                case DEADLINE_EXCEEDED:
                    return new GeminiException(GeminiErrorType.TIMEOUT,
                            context.getString(R.string.error_timeout));

                case NOT_FOUND:
                    return new GeminiException(GeminiErrorType.NETWORK,
                            context.getString(R.string.error_404));

                case INTERNAL:
                    if (serverMsg.contains("empty")) {
                        return new GeminiException(GeminiErrorType.EMPTY_RESPONSE,
                                context.getString(R.string.error_empty));
                    }
                    return new GeminiException(GeminiErrorType.UNKNOWN,
                            context.getString(R.string.error_unknown));

                default:
                    break;
            }
        }

        // Fallback for non-Firebase errors (network issues etc.)
        String msg = safeLower(t.getMessage());
        Log.w(TAG, "Non-Firebase error: " + msg);

        if (msg.contains("network") || msg.contains("connect") || msg.contains("unreachable")) {
            return new GeminiException(GeminiErrorType.NETWORK,
                    context.getString(R.string.error_internet));
        }

        return new GeminiException(GeminiErrorType.UNKNOWN,
                context.getString(R.string.error_unknown));
    }

    private String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    // ─── STATIC HELPERS ─────────────────────────────────────────
    public static void clearInstance() {
        instance = null;
    }

    public static void resetRequestCounter() {
        activeRequests.set(0);
    }

    // ─── INNER TYPES (unchanged) ────────────────────────────────
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