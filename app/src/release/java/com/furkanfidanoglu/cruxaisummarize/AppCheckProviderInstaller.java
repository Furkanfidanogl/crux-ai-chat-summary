package com.furkanfidanoglu.cruxaisummarize;

import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;

final class AppCheckProviderInstaller {
    private AppCheckProviderInstaller() {}

    static void install(FirebaseAppCheck appCheck) {
        appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance());
    }
}
