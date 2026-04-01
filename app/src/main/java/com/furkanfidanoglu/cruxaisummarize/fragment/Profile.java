package com.furkanfidanoglu.cruxaisummarize.fragment;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.navigation.Navigation;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.data.model.FirebaseDB;
import com.furkanfidanoglu.cruxaisummarize.databinding.FragmentProfileBinding;
import com.furkanfidanoglu.cruxaisummarize.util.managers.BillingManager;
import com.furkanfidanoglu.cruxaisummarize.util.managers.FirebaseDBManager;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

public class Profile extends Fragment {
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private FragmentProfileBinding binding;
    private SharedPreferences prefs;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        prefs = requireContext().getSharedPreferences("UserStats", Context.MODE_PRIVATE);

        if (auth.getCurrentUser() != null) {
            binding.emailText.setText(auth.getCurrentUser().getEmail());
            loadFromCache();
            fetchUserStats();
        }

        binding.btnManagePlan.setOnClickListener(this::goToPlans);
        binding.btnHelpSupport.setOnClickListener(this::btnWebSite);
        binding.btnLogout.setOnClickListener(this::btnLogout);
        binding.btnChangePassword.setOnClickListener(this::btnChangePassword);
        binding.btnDeleteAccount.setOnClickListener(this::btnDeleteAccount);

        binding.btnLanguage.setOnClickListener(v -> {
            BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
            View sheetView = getLayoutInflater().inflate(R.layout.change_language, null);
            dialog.setContentView(sheetView);

            // Tüm diller için tıklama olayları
            sheetView.findViewById(R.id.cardEnglish).setOnClickListener(v1 -> { changeLanguage("en"); dialog.dismiss(); });
            sheetView.findViewById(R.id.cardGerman).setOnClickListener(v1 -> { changeLanguage("de"); dialog.dismiss(); });
            sheetView.findViewById(R.id.cardSpanish).setOnClickListener(v1 -> { changeLanguage("es"); dialog.dismiss(); });
            sheetView.findViewById(R.id.cardFrench).setOnClickListener(v1 -> { changeLanguage("fr"); dialog.dismiss(); });
            sheetView.findViewById(R.id.cardKorean).setOnClickListener(v1 -> { changeLanguage("ko"); dialog.dismiss(); });
            sheetView.findViewById(R.id.cardTurkish).setOnClickListener(v1 -> { changeLanguage("tr"); dialog.dismiss(); });

            dialog.show();
        });
    }

    private void changeLanguage(String langCode) {
        SharedPreferences settingsPrefs = requireContext().getSharedPreferences("Settings", Context.MODE_PRIVATE);
        settingsPrefs.edit().putString("My_Lang", langCode).apply();

        Intent intent = requireActivity().getBaseContext().getPackageManager().getLaunchIntentForPackage(requireActivity().getBaseContext().getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            requireActivity().finish();
        }
    }

    private void loadFromCache() {
        int cachedSummaries = prefs.getInt("cached_summaries", 0);
        int cachedTime = prefs.getInt("cached_time", 0);
        binding.summaries.setText(String.valueOf(cachedSummaries));
        binding.savedTime.setText(formatSavedTime(cachedTime));

        // 🔥 YENİ: Plan tipini de hafızadan yükle (Flickering/Göz kırpma sorununu çözer)
        String cachedPlan = prefs.getString("cached_plan_type", "free");
        if (cachedPlan.equalsIgnoreCase("premium")) {
            binding.txtPlanStatus.setText(getString(R.string.label_plan_premium));
        } else {
            binding.txtPlanStatus.setText(getString(R.string.label_plan_free));
        }
    }

    private void fetchUserStats() {
        if (auth.getCurrentUser() == null) return;

        BillingManager.getInstance(requireContext()).checkSubscriptionStatus();

        db.collection("users").document(auth.getCurrentUser().getUid()).get().addOnSuccessListener(doc -> {
            if (isAdded() && doc.exists()) {

                String planType = doc.getString("plan_type");
                if (planType == null) planType = "free";

                // 🔥 YENİ: Plan tipini hem ekrana bas hem de hafızaya kaydet
                if (planType.equalsIgnoreCase("premium")) {
                    binding.txtPlanStatus.setText(getString(R.string.label_plan_premium));
                } else {
                    binding.txtPlanStatus.setText(getString(R.string.label_plan_free));
                }

                prefs.edit().putString("cached_plan_type", planType).apply();

                FirebaseDB user = doc.toObject(FirebaseDB.class);
                if (user != null) {
                    binding.summaries.setText(String.valueOf(user.getTotal_summaries()));
                    binding.savedTime.setText(formatSavedTime(user.getTotal_saved_time()));

                    prefs.edit()
                            .putInt("cached_summaries", user.getTotal_summaries())
                            .putInt("cached_time", user.getTotal_saved_time())
                            .apply();
                }
            }
        });
    }

    private String formatSavedTime(int totalMinutes) {
        if (totalMinutes < 60) return totalMinutes + "m";
        int h = totalMinutes / 60;
        int m = totalMinutes % 60;
        return (m == 0) ? h + "h" : h + "h " + m + "m";
    }

    public void goToPlans(View view) {
        Navigation.findNavController(view).navigate(ProfileDirections.actionProfileToPlans());
    }

    public void btnWebSite(View view) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://cruxai.netlify.app")));
    }

    public void btnLogout(View view) {
        prefs.edit().clear().apply();

        auth.signOut();

        // 3. GOOGLE'DAN DA ÇIKIŞ YAP (YENİ EKLENEN KISIM)
        // Bunu eklemezsen Google arkada "ben hala girişliyim" der, hesap seçtirmez.
        try {
            GoogleSignInOptions gso =
                    new GoogleSignInOptions.Builder(
                            GoogleSignInOptions.DEFAULT_SIGN_IN).build();

            GoogleSignInClient googleSignInClient =
                    GoogleSignIn.getClient(requireActivity(), gso);

            googleSignInClient.signOut();
        } catch (Exception e) {
            e.printStackTrace();
        }

        startActivity(new Intent(getActivity(), com.furkanfidanoglu.cruxaisummarize.view.MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));

        Toast.makeText(getContext(), getString(R.string.msg_logged_out), Toast.LENGTH_SHORT).show();
    }

    public void btnChangePassword(View view) {
        if (auth.getCurrentUser() != null) {
            auth.sendPasswordResetEmail(auth.getCurrentUser().getEmail())
                    .addOnSuccessListener(u -> Toast.makeText(getContext(), getString(R.string.msg_reset_email_sent), Toast.LENGTH_LONG).show())
                    .addOnFailureListener(e -> Toast.makeText(getContext(), e.getLocalizedMessage(), Toast.LENGTH_LONG).show());
        }
    }

    public void btnDeleteAccount(View view) {
        new android.app.AlertDialog.Builder(getContext())
                .setTitle(getString(R.string.action_delete_account))
                .setMessage(getString(R.string.dialog_delete_account_msg))
                .setPositiveButton(getString(R.string.action_delete_forever), (d, w) -> deleteFunc())
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void deleteFunc() {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) return;

        user.delete().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                String uid = user.getUid();

                db.collection("users").document(uid).delete();

                // B) Sohbet Geçmişini Sil
                FirebaseDBManager.getInstance().deleteAllHistory(new FirebaseDBManager.DeleteCallback() {
                    @Override
                    public void onSuccess() { }
                    @Override
                    public void onError(String error) { }
                });

                // C) 🔥 STORAGE SİLME (Yeni Yapı: uploads/userID/)
                StorageReference userFolderRef =
                        FirebaseStorage.getInstance().getReference().child("uploads/" + uid);

                deleteFolderRecursively(userFolderRef);

                // D) Kullanıcıya bilgi ver ve ÇIKIŞ yap
                Toast.makeText(getContext(), getString(R.string.msg_account_deleted), Toast.LENGTH_SHORT).show();
                btnLogout(null);

            } else {
                try {
                    throw task.getException();
                } catch (Exception e) {
                    Toast.makeText(getContext(), getString(R.string.error_reauth_needed), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void deleteFolderRecursively(StorageReference folderRef) {
        folderRef.listAll().addOnSuccessListener(listResult -> {
            // 1. Klasördeki tüm dosyaları sil
            for (StorageReference item : listResult.getItems()) {
                item.delete();
            }

            // 2. Alt klasörler için aynı işlemi tekrar et
            for (StorageReference prefix : listResult.getPrefixes()) {
                deleteFolderRecursively(prefix);
            }

        }).addOnFailureListener(e -> {
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}