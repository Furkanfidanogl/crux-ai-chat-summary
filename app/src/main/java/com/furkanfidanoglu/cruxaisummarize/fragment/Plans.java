package com.furkanfidanoglu.cruxaisummarize.fragment;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.databinding.FragmentPlansBinding;
import com.furkanfidanoglu.cruxaisummarize.util.managers.BillingManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration; // 🔥 BUNU EKLE

public class Plans extends Fragment {

    private FragmentPlansBinding binding;
    private BillingManager billingManager;
    private ListenerRegistration planListener; // 🔥 Canlı takipçi değişkenimiz

    public Plans() {
        // Boş constructor
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentPlansBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        startUserPlanListener();

        billingManager = BillingManager.getInstance(requireContext());

        billingManager.setCallback(new BillingManager.BillingCallback() {
            @Override
            public void onPriceLoaded(String priceText) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (binding != null) {
                            binding.tvPrice.setText(priceText);
                            binding.loadingProgressBar.setVisibility(View.GONE);
                            binding.plansContentLayout.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }

            @Override
            public void onPurchaseSuccess() {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(), getString(R.string.premium_user), Toast.LENGTH_LONG).show();
                        try {
                            androidx.navigation.Navigation.findNavController(requireView())
                                    .navigate(R.id.action_plans_to_nav_text);
                        } catch (Exception e) {
                        }
                    });
                }
            }

            @Override
            public void onError(String error) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (binding != null) {
                            binding.loadingProgressBar.setVisibility(View.GONE);
                            binding.plansContentLayout.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        });

        binding.btnUpgrade.setOnClickListener(v -> {
            if (getActivity() != null) {
                billingManager.launchPurchaseFlow(getActivity());
            }
        });

        binding.tvCancel.setOnClickListener(v -> {
            try {
                String url = "https://play.google.com/store/account/subscriptions?sku=" + BillingManager.PRODUCT_ID_MONTHLY + "&package=" + requireContext().getPackageName();
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions"));
                startActivity(intent);
            }
        });
    }

    // 🔥 ESKİ TEK SEFERLİK METOT YERİNE BU CANLI TAKİP METODU GELDİ 🔥
    private void startUserPlanListener() {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) {
            // Eğer daha önce bir dinleyici varsa kaldır (çift çalışmasın)
            if (planListener != null) {
                planListener.remove();
            }

            planListener = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(auth.getCurrentUser().getUid())
                    .addSnapshotListener((documentSnapshot, e) -> { // get() yerine addSnapshotListener

                        if (e != null) {
                            if (isAdded() && binding != null) {
                                binding.btnUpgrade.setEnabled(true);
                                binding.btnUpgrade.setAlpha(1.0f);
                            }
                            return;
                        }

                        if (isAdded() && binding != null && documentSnapshot != null && documentSnapshot.exists()) {
                            String planType = documentSnapshot.getString("plan_type");

                            // Canlı olarak veritabanı değiştiği an burası çalışır!
                            if ("premium".equals(planType)) {
                                binding.btnUpgrade.setEnabled(false);
                                binding.btnUpgrade.setAlpha(0.5f);
                            } else {
                                binding.btnUpgrade.setEnabled(true);
                                binding.btnUpgrade.setAlpha(1.0f);
                                binding.btnUpgrade.setText(R.string.btn_upgrade_now);
                            }
                        }
                    });
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (planListener != null) {
            planListener.remove();
            planListener = null;
        }
        binding = null;
    }
}