package com.furkanfidanoglu.cruxaisummarize.util.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class BaseActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        newBase = updateLocale(newBase);
        super.attachBaseContext(newBase);
    }

    protected Context updateLocale(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("Settings", Context.MODE_PRIVATE);

        // Sistem dili
        String systemLang = Locale.getDefault().getLanguage();
        String defaultLang = "en";

        if ("tr".equals(systemLang)) {
            defaultLang = "tr";
        }

        // SharedPreferences'den seçilen dili al
        String lang = prefs.getString("My_Lang", defaultLang);

        Locale locale = new Locale(lang);
        Locale.setDefault(locale);

        android.content.res.Configuration config = context.getResources().getConfiguration();
        config.setLocale(locale);
        config.setLayoutDirection(locale); // RTL diller için

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return context.createConfigurationContext(config);
        } else {
            context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());
            return context;
        }
    }
}