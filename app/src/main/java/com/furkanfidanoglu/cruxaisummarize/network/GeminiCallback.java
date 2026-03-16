package com.furkanfidanoglu.cruxaisummarize.network;

public interface GeminiCallback {
    void onSuccess(String response);
    void onError(Throwable t);
}
