package com.furkanfidanoglu.cruxaisummarize.util.managers;

import com.furkanfidanoglu.cruxaisummarize.data.model.MessageModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SessionManager {
    private static volatile SessionManager instance;
    private static final Object lock = new Object();

    private String activeChatId;
    private List<MessageModel> activeMessages;
    private final Object sessionLock = new Object();

    private SessionManager() {
        activeMessages = new ArrayList<>();
    }

    public static SessionManager getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new SessionManager();
                }
            }
        }
        return instance;
    }

    public String getActiveChatId() {
        synchronized (sessionLock) {
            return activeChatId;
        }
    }

    public void setActiveChatId(String activeChatId) {
        synchronized (sessionLock) {
            this.activeChatId = activeChatId;
        }
    }

    public List<MessageModel> getActiveMessages() {
        synchronized (sessionLock) {
            if (activeMessages == null || activeMessages.isEmpty()) {
                return Collections.emptyList();
            }
            // Defensive Copy: Dışarıya referans değil, kopya veriyoruz. Güvenli.
            return new ArrayList<>(activeMessages);
        }
    }

    public void setActiveMessages(List<MessageModel> messages) {
        synchronized (sessionLock) {
            if (messages == null) {
                this.activeMessages = new ArrayList<>();
            } else {
                this.activeMessages = new ArrayList<>(messages);
            }
        }
    }

    public void addMessage(MessageModel message) {
        if (message == null) return;

        synchronized (sessionLock) {
            if (activeMessages == null) {
                activeMessages = new ArrayList<>();
            }
            activeMessages.add(message);
        }
    }

    public void clearSession() {
        synchronized (sessionLock) {
            this.activeChatId = null;
            if (activeMessages != null) {
                activeMessages.clear();
            }
        }
    }

    public void removeLastMessage() {
        synchronized (sessionLock) {
            if (activeMessages != null && !activeMessages.isEmpty()) {
                int lastIndex = activeMessages.size() - 1;
                if (lastIndex >= 0) {
                    activeMessages.remove(lastIndex);
                }
            }
        }
    }

    public int getMessageCount() {
        synchronized (sessionLock) {
            return (activeMessages != null) ? activeMessages.size() : 0;
        }
    }

    public boolean hasActiveSession() {
        synchronized (sessionLock) {
            return activeChatId != null && activeMessages != null && !activeMessages.isEmpty();
        }
    }
}