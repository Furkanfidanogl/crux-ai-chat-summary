package com.furkanfidanoglu.cruxaisummarize.data.model;

import androidx.annotation.Keep;
import com.google.firebase.Timestamp;

@Keep
public class FirebaseDB {

    private String uid;
    private String email;
    private Timestamp created_at;

    private String plan_type; // "free" veya "premium"
    private Timestamp premium_expiry_date;

    private String usage_date;
    private int daily_credits; // YENİ ADI: Artık her şey (resim, ses, doc, link) burayı artıracak.

    private int total_saved_time;
    private int total_summaries;

    public FirebaseDB() {
        // Firestore için boş constructor şart
    }

    public FirebaseDB(String uid, String email, Timestamp created_at) {
        this.uid = uid;
        this.email = email;
        this.created_at = created_at;

        this.plan_type = "free";
        this.premium_expiry_date = null;

        this.usage_date = "";
        this.daily_credits = 0; // Tek sayaç başlangıcı

        this.total_saved_time = 0;
        this.total_summaries = 0;
    }

    // --- GETTERS & SETTERS ---

    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    // getDisplay_name SİLİNDİ

    public Timestamp getCreated_at() { return created_at; }
    public void setCreated_at(Timestamp created_at) { this.created_at = created_at; }

    public String getPlan_type() { return plan_type; }
    public void setPlan_type(String plan_type) { this.plan_type = plan_type; }

    public Timestamp getPremium_expiry_date() { return premium_expiry_date; }
    public void setPremium_expiry_date(Timestamp premium_expiry_date) { this.premium_expiry_date = premium_expiry_date; }

    public String getUsage_date() { return usage_date; }
    public void setUsage_date(String usage_date) { this.usage_date = usage_date; }

    // daily_credits
    public int getDaily_credits() { return daily_credits; }
    public void setDaily_credits(int daily_credits) { this.daily_credits = daily_credits; }


    public int getTotal_saved_time() { return total_saved_time; }
    public void setTotal_saved_time(int total_saved_time) { this.total_saved_time = total_saved_time; }

    public int getTotal_summaries() { return total_summaries; }
    public void setTotal_summaries(int total_summaries) { this.total_summaries = total_summaries; }
}