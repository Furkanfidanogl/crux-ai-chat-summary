package com.furkanfidanoglu.cruxaisummarize;

import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;

final class AppCheckProviderInstaller {
    private AppCheckProviderInstaller() {}

    static void install(FirebaseAppCheck appCheck) {
        appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance());
    }
}
