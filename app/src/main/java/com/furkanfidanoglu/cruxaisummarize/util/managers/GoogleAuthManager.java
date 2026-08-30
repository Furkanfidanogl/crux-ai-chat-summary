package com.furkanfidanoglu.cruxaisummarize.util.managers;

import static com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialException;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

/** Modern Sign in with Google bridge built on Android Credential Manager. */
public final class GoogleAuthManager {
    private GoogleAuthManager() {}

    public interface SignInCallback {
        void onIdToken(@NonNull String idToken);
        void onCancelled();
        void onError(@NonNull Exception error);
    }

    @NonNull
    public static CancellationSignal signIn(@NonNull Activity activity, @NonNull SignInCallback callback) {
        CredentialManager credentialManager = CredentialManager.create(activity);
        GetSignInWithGoogleOption googleOption = new GetSignInWithGoogleOption.Builder(
                activity.getString(R.string.default_web_client_id))
                .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(googleOption)
                .build();
        CancellationSignal cancellationSignal = new CancellationSignal();

        credentialManager.getCredentialAsync(
                activity,
                request,
                cancellationSignal,
                ContextCompat.getMainExecutor(activity),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        Credential credential = result.getCredential();
                        if (!(credential instanceof CustomCredential)
                                || !TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
                            callback.onError(new IllegalStateException("Unexpected credential type"));
                            return;
                        }

                        try {
                            CustomCredential customCredential = (CustomCredential) credential;
                            GoogleIdTokenCredential googleCredential =
                                    GoogleIdTokenCredential.createFrom(customCredential.getData());
                            callback.onIdToken(googleCredential.getIdToken());
                        } catch (RuntimeException error) {
                            callback.onError(error);
                        }
                    }

                    @Override
                    public void onError(@NonNull GetCredentialException error) {
                        if ("GetCredentialCancellationException".equals(error.getClass().getSimpleName())) {
                            callback.onCancelled();
                        } else {
                            callback.onError(error);
                        }
                    }
                });
        return cancellationSignal;
    }

    /** Clears the active provider session so the next sign-in can choose another account. */
    public static void clearCredentialState(@NonNull Context context, @NonNull Runnable completion) {
        CredentialManager credentialManager = CredentialManager.create(context);
        credentialManager.clearCredentialStateAsync(
                new ClearCredentialStateRequest(),
                new CancellationSignal(),
                ContextCompat.getMainExecutor(context),
                new CredentialManagerCallback<Void, ClearCredentialException>() {
                    @Override
                    public void onResult(Void result) {
                        completion.run();
                    }

                    @Override
                    public void onError(@NonNull ClearCredentialException error) {
                        // Firebase is already signed out; provider cleanup must not trap the user.
                        completion.run();
                    }
                });
    }
}
