package com.furkanfidanoglu.cruxaisummarize;

import android.app.Application;

import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;

public class MyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        FirebaseApp.initializeApp(this);

        AppCheckProviderInstaller.install(FirebaseAppCheck.getInstance());
    }
}
