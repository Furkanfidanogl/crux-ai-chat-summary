package com.furkanfidanoglu.cruxaisummarize.data.model;

import androidx.annotation.Keep;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.Exclude;

@Keep
public class MessageModel {
    private String id;
    private String role;
    private String content;
    private String type; // Değerler: "TEXT", "IMAGE", "AUDIO", "DOC" (YENİ)
    private Timestamp timestamp;

    // Medya URL'leri (Firestore'a kaydedilir)
    private String imageUrl;
    private String audioUrl;
    private String docUrl; // YENİ: PDF/TXT linki

    // Dosya İsmi
    private String fileName;

    // Anlık Veriler (Firestore'a KAYDEDİLMEZ - Sadece UI ve Upload için)
    @Exclude
    private byte[] image;

    @Exclude
    private byte[] audio;

    @Exclude
    private byte[] doc; // YENİ: PDF/TXT verisi

    public MessageModel() {
        // Firestore için boş constructor şart
    }

    public MessageModel(String id, String role, String content, String type, Timestamp timestamp) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp;
    }

    // --- GETTERS & SETTERS ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Timestamp getTimestamp() { return timestamp; }
    public void setTimestamp(Timestamp timestamp) { this.timestamp = timestamp; }

    // --- IMAGE ---
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    @Exclude
    public byte[] getImage() { return image; }
    @Exclude
    public void setImage(byte[] image) { this.image = image; }

    // --- AUDIO ---
    public String getAudioUrl() { return audioUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }

    @Exclude
    public byte[] getAudio() { return audio; }
    @Exclude
    public void setAudio(byte[] audio) { this.audio = audio; }

    // --- DOC (YENİ) ---
    public String getDocUrl() { return docUrl; }
    public void setDocUrl(String docUrl) { this.docUrl = docUrl; }

    @Exclude
    public byte[] getDoc() { return doc; }
    @Exclude
    public void setDoc(byte[] doc) { this.doc = doc; }

    // --- FILE NAME ---
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
}