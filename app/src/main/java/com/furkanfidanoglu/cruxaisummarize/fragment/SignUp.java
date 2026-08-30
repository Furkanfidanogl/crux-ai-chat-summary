package com.furkanfidanoglu.cruxaisummarize.fragment;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.databinding.FragmentSignUpBinding;
import com.furkanfidanoglu.cruxaisummarize.util.managers.GoogleAuthManager;
import com.furkanfidanoglu.cruxaisummarize.view.HomeActivity;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.GoogleAuthProvider;

public class SignUp extends Fragment {

    private FragmentSignUpBinding binding;
    private FirebaseAuth auth;
    private CancellationSignal googleSignInCancellation;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        // XML Bağlantısı
        binding = FragmentSignUpBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        auth = FirebaseAuth.getInstance();

        binding.btnGoogleSignUp.setOnClickListener(v -> signInWithGoogle());

        // Linkleri ayarla
        setupWebLinks();

        // Buton: Kayıt Ol
        binding.btnSignUp.setOnClickListener(v -> signUpFunc());

        // Link: Zaten hesabım var (Geri Dön)
        binding.tvGoToLogin.setOnClickListener(v -> {
            Navigation.findNavController(v).popBackStack();
        });
    }

    private void signInWithGoogle() {
        if (!binding.cbPrivacy.isChecked() || !binding.cbTerms.isChecked()) {
            Toast.makeText(requireContext(), R.string.msg_accept_terms, Toast.LENGTH_LONG).show();
            return;
        }

        setLoadingState(true);
        if (googleSignInCancellation != null) googleSignInCancellation.cancel();
        googleSignInCancellation = GoogleAuthManager.signIn(requireActivity(),
                new GoogleAuthManager.SignInCallback() {
                    @Override
                    public void onIdToken(@NonNull String idToken) {
                        googleSignInCancellation = null;
                        firebaseAuthWithGoogle(idToken);
                    }

                    @Override
                    public void onCancelled() {
                        googleSignInCancellation = null;
                        if (binding != null) setLoadingState(false);
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        googleSignInCancellation = null;
                        if (binding != null) {
                            setLoadingState(false);
                            Toast.makeText(requireContext(), R.string.google_error, Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    // --- FIREBASE İLE KAYIT/GİRİŞ YAPAR ---
    private void firebaseAuthWithGoogle(String idToken) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        auth.signInWithCredential(credential)
                .addOnSuccessListener(authResult -> {
                    // Kayıt Ol sayfasındayız.
                    // Adam yeni de olsa, eski de olsa Google ile doğrulandıysa içeri alıyoruz.
                    // "Hesap zaten var" hatası vermez, direkt giriş yapar.

                    boolean isNewUser = authResult.getAdditionalUserInfo() != null
                            && authResult.getAdditionalUserInfo().isNewUser();

                    if (isNewUser) {
                        Toast.makeText(requireContext(), getString(R.string.msg_account_created), Toast.LENGTH_SHORT)
                                .show();
                    }
                    changeScreen();
                })
                .addOnFailureListener(e -> {
                    setLoadingState(false);
                    Toast.makeText(requireContext(), R.string.google_error, Toast.LENGTH_LONG).show();
                });
    }

    private void signUpFunc() {
        String email = binding.emailInput.getText().toString().trim();
        String password = binding.passwordInput.getText().toString();
        String confirmPassword = binding.passwordInput2.getText().toString();

        // 1. Boşluk Kontrolü
        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.msg_fill_all_fields), Toast.LENGTH_SHORT).show();
            return;
        }

        // 2. Sözleşme Kontrolü
        if (!binding.cbPrivacy.isChecked() || !binding.cbTerms.isChecked()) {
            Toast.makeText(requireContext(), getString(R.string.msg_accept_terms), Toast.LENGTH_LONG).show();
            return;
        }

        // 3. Şifre Tekrar Kontrolü
        if (confirmPassword.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.msg_confirm_password), Toast.LENGTH_SHORT).show();
            return;
        }

        if (!password.equals(confirmPassword)) {
            Toast.makeText(requireContext(), getString(R.string.msg_password_mismatch), Toast.LENGTH_SHORT).show();
            return;
        }

        setLoadingState(true);

        // 4. Firebase Kayıt İşlemi
        auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> {
                    Toast.makeText(requireContext(), getString(R.string.msg_account_created), Toast.LENGTH_SHORT)
                            .show();
                    changeScreen();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(requireContext(), getString(R.string.error_signup_failed), Toast.LENGTH_LONG).show();
                    setLoadingState(false);
                });
    }

    private void setupWebLinks() {
        View.OnClickListener webListener = v -> {
            String url = "https://cruxai.netlify.app";
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        };

        binding.tvPrivacyPolicy.setOnClickListener(webListener);
        binding.tvTerms.setOnClickListener(webListener);
    }

    private void setLoadingState(boolean isLoading) {
        if (isLoading) {
            binding.btnSignUp.setEnabled(false);
            binding.btnSignUp.setText("");
            binding.progressBar.setVisibility(View.VISIBLE);
            // Google butonu da kilitlensin
            binding.btnGoogleSignUp.setEnabled(false);
        } else {
            binding.btnSignUp.setEnabled(true);
            binding.btnSignUp.setText(getString(R.string.btn_create_account));
            binding.progressBar.setVisibility(View.GONE);
            binding.btnGoogleSignUp.setEnabled(true);
        }
    }

    private void changeScreen() {
        if (getActivity() != null) {
            Intent intent = new Intent(getActivity(), HomeActivity.class);
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            requireActivity().finish();
        }
    }

    @Override
    public void onDestroyView() {
        if (googleSignInCancellation != null) {
            googleSignInCancellation.cancel();
            googleSignInCancellation = null;
        }
        super.onDestroyView();
        binding = null;
    }
}
