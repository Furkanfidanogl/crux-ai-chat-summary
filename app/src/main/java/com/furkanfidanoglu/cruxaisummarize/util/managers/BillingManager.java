package com.furkanfidanoglu.cruxaisummarize.util.managers;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.google.common.collect.ImmutableList;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BillingManager implements PurchasesUpdatedListener {

    private static BillingManager instance;
    private BillingClient billingClient;
    private BillingCallback callback;
    private boolean isConnected = false;

    public static final String PRODUCT_ID_MONTHLY = "premium_monthly";

    public interface BillingCallback {
        void onPriceLoaded(String priceText);
        void onPurchaseSuccess();
        void onError(String error);
    }

    private BillingManager(Context context) {
        billingClient = BillingClient.newBuilder(context)
                .setListener(this)
                .enablePendingPurchases()
                .build();
        startConnection();
    }

    public static synchronized BillingManager getInstance(Context context) {
        if (instance == null) {
            instance = new BillingManager(context.getApplicationContext());
        }
        return instance;
    }

    public void setCallback(BillingCallback callback) {
        this.callback = callback;
        if (isConnected) {
            queryProducts();
        } else {
            startConnection();
        }
    }

    private void startConnection() {
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    isConnected = true;
                    if (callback != null) {
                        queryProducts();
                    }
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                isConnected = false;
            }
        });
    }

    private void queryProducts() {
        QueryProductDetailsParams queryProductDetailsParams =
                QueryProductDetailsParams.newBuilder()
                        .setProductList(
                                ImmutableList.of(
                                        QueryProductDetailsParams.Product.newBuilder()
                                                .setProductId(PRODUCT_ID_MONTHLY)
                                                .setProductType(BillingClient.ProductType.SUBS)
                                                .build()))
                        .build();

        billingClient.queryProductDetailsAsync(
                queryProductDetailsParams,
                (billingResult, productDetailsList) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK &&
                            productDetailsList != null && !productDetailsList.isEmpty()) {

                        ProductDetails product = productDetailsList.get(0);
                        String price = product.getSubscriptionOfferDetails()
                                .get(0).getPricingPhases().getPricingPhaseList()
                                .get(0).getFormattedPrice();

                        if (callback != null) {
                            callback.onPriceLoaded(price);
                        }
                    }
                }
        );
    }

    public void launchPurchaseFlow(Activity activity) {
        if (!isConnected) {
            if (callback != null) callback.onError("Google Play Store connection failed.");
            startConnection();
            return;
        }

        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(ImmutableList.of(QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID_MONTHLY)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()))
                .build();

        billingClient.queryProductDetailsAsync(params, (billingResult, list) -> {
            if (list != null && !list.isEmpty()) {
                ProductDetails productDetails = list.get(0);
                String offerToken = productDetails.getSubscriptionOfferDetails().get(0).getOfferToken();

                ImmutableList<BillingFlowParams.ProductDetailsParams> productDetailsParamsList =
                        ImmutableList.of(
                                BillingFlowParams.ProductDetailsParams.newBuilder()
                                        .setProductDetails(productDetails)
                                        .setOfferToken(offerToken)
                                        .build()
                        );

                BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(productDetailsParamsList)
                        .build();

                billingClient.launchBillingFlow(activity, billingFlowParams);
            } else {
                if (callback != null) callback.onError("Product not found.");
            }
        });
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult, List<Purchase> purchases) {
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase);
            }
        } else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            // İptal
        } else {
            if (callback != null) callback.onError(billingResult.getDebugMessage());
        }
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged()) {
                AcknowledgePurchaseParams acknowledgePurchaseParams =
                        AcknowledgePurchaseParams.newBuilder()
                                .setPurchaseToken(purchase.getPurchaseToken())
                                .build();
                billingClient.acknowledgePurchase(acknowledgePurchaseParams, billingResult -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        activatePremium();
                    }
                });
            } else {
                activatePremium();
            }
        }
    }

    private void activatePremium() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        FirebaseAuth auth = FirebaseAuth.getInstance();

        if (auth.getCurrentUser() != null) {
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DAY_OF_YEAR, 30);
            Date expiryDate = calendar.getTime();

            Map<String, Object> updates = new HashMap<>();
            updates.put("plan_type", "premium");
            updates.put("premium_expiry_date", new Timestamp(expiryDate));
            updates.put("daily_credits", 0);

            db.collection("users").document(auth.getCurrentUser().getUid())
                    .set(updates, SetOptions.merge())
                    .addOnSuccessListener(aVoid -> {
                        // UI'ı tetikle
                        if (callback != null) callback.onPurchaseSuccess();
                    })
                    .addOnFailureListener(e -> {
                        if (callback != null) callback.onError("DB Error: " + e.getMessage());
                    });
        }
    }

    public void checkSubscriptionStatus() {
        if (!isConnected) {
            startConnection();
            return;
        }

        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                (billingResult, purchases) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        boolean isPremiumActive = false;
                        if (purchases != null) {
                            for (Purchase purchase : purchases) {
                                if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                    isPremiumActive = true;
                                    break;
                                }
                            }
                        }
                        if (!isPremiumActive) downgradeToFree();
                    }
                }
        );
    }

    private void downgradeToFree() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        FirebaseAuth auth = FirebaseAuth.getInstance();

        if (auth.getCurrentUser() != null) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("plan_type", "free");
            updates.put("premium_expiry_date", null);

            db.collection("users").document(auth.getCurrentUser().getUid())
                    .set(updates, SetOptions.merge())
                    .addOnSuccessListener(aVoid -> Log.d("Billing", "Downgraded"))
                    .addOnFailureListener(e -> Log.e("Billing", "Downgrade failed", e));
        }
    }
}