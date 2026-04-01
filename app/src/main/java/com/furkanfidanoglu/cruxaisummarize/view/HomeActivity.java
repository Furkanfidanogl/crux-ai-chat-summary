package com.furkanfidanoglu.cruxaisummarize.view;

import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.animation.NavigationAnimHelper;
import com.furkanfidanoglu.cruxaisummarize.util.helpers.BaseActivity;
import com.furkanfidanoglu.cruxaisummarize.util.managers.FirebaseDBManager;
import com.furkanfidanoglu.cruxaisummarize.util.managers.BillingManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;

// 🔥 GÜNCELLEME İÇİN GEREKLİ IMPORTLAR
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.google.android.gms.tasks.Task;

public class HomeActivity extends BaseActivity {

    FirebaseAuth auth;
    private AppUpdateManager appUpdateManager;
    private static final int UPDATE_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);

        setContentView(R.layout.activity_home);

        // 🔥 1. GÜNCELLEME YÖNETİCİSİNİ BAŞLAT (YENİ EKLENDİ)
        appUpdateManager = AppUpdateManagerFactory.create(this);
        checkUpdate(); // Güncelleme var mı diye sor

        // 2. ANA KUTU AYARI (MEVCUT KOD - DOKUNULMADI)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());

            // Eğer klavye açıksa: Klavye yüksekliğinden, alttaki sistem çubuğunu çıkar.
            int bottomPadding = 0;
            if (imeInsets.bottom > 0) {
                bottomPadding = imeInsets.bottom - systemBars.bottom;
            }

            // Negatif sayı çıkarsa 0 yap
            if (bottomPadding < 0) bottomPadding = 0;

            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomPadding);

            return insets;
        });

        // 3. BOTTOM NAV AYARI (MEVCUT KOD - DOKUNULMADI)
        BottomNavigationView bottomNav = findViewById(R.id.bottomNavigationView);
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(0, 0, 0, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) {
            FirebaseDBManager.getInstance().createUserIfNotExist();
        }

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);

        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();
            NavigationAnimHelper.setupWithAnimations(bottomNav, navController);
        }

        BillingManager.getInstance(this).checkSubscriptionStatus();
    }

    // 🔥 GÜNCELLEME KONTROL METODU (YENİ EKLENDİ)
    private void checkUpdate() {
        Task<AppUpdateInfo> appUpdateInfoTask = appUpdateManager.getAppUpdateInfo();

        appUpdateInfoTask.addOnSuccessListener(appUpdateInfo -> {
            // LOGLARI BURADAN TAKİP ET
            Log.d("UpdateTest", "Mevcut Durum: " + appUpdateInfo.updateAvailability());
            Log.d("UpdateTest", "Mevcut Versiyon: " + appUpdateInfo.availableVersionCode());
            Log.d("UpdateTest", "Update Available Kodu Kaç?: " + UpdateAvailability.UPDATE_AVAILABLE);

            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {

                Log.d("UpdateTest", "Güncelleme bulundu, ekran açılıyor...");
                try {
                    appUpdateManager.startUpdateFlowForResult(
                            appUpdateInfo,
                            AppUpdateType.IMMEDIATE,
                            this,
                            UPDATE_REQUEST_CODE);
                } catch (IntentSender.SendIntentException e) {
                    Log.e("UpdateTest", "Hata oluştu: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                Log.d("UpdateTest", "Güncelleme API tarafından tetiklenmedi. Sebebi yukarıdaki kodlarda.");
            }
        });
    }

    // 🔥 KULLANICI UYGULAMADAN ÇIKIP GERİ GELİRSE (YENİ EKLENDİ)
    @Override
    protected void onResume() {
        super.onResume();

        appUpdateManager.getAppUpdateInfo().addOnSuccessListener(appUpdateInfo -> {
            // Güncelleme yarıda kaldıysa devam ettir
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                try {
                    appUpdateManager.startUpdateFlowForResult(
                            appUpdateInfo,
                            AppUpdateType.IMMEDIATE,
                            this,
                            UPDATE_REQUEST_CODE);
                } catch (IntentSender.SendIntentException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    // 🔥 KULLANICI GÜNCELLEMEYİ REDDEDERSE (YENİ EKLENDİ)
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == UPDATE_REQUEST_CODE) {
            if (resultCode != RESULT_OK) {
                Log.e("UpdateTest", "Kullanıcı güncellemeyi reddetti veya başarısız oldu.");
                finish(); // Zorunlu güncellemeyi yapmıyorsa uygulamayı kapat
            }
        }
    }
}