package com.furkanfidanoglu.cruxaisummarize.fragment;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.databinding.FragmentSignUpBinding;
import com.furkanfidanoglu.cruxaisummarize.view.HomeActivity;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.GoogleAuthProvider;

public class SignUp extends Fragment {

    private FragmentSignUpBinding binding;
    private FirebaseAuth auth;
    private GoogleSignInClient mGoogleSignInClient;

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

        // --- 1. GOOGLE AYARLARI ---
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(requireActivity(), gso);

        // --- 2. GOOGLE BUTONU TIKLAMA (İsim düzeltildi: btnGoogleSign) ---
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

    // --- GOOGLE PENCERESİNİ AÇAR ---
    private void signInWithGoogle() {
        Intent signInIntent = mGoogleSignInClient.getSignInIntent();
        googleSignInLauncher.launch(signInIntent);
    }

    // --- GOOGLE SONUCUNU YAKALAR ---
    private final ActivityResultLauncher<Intent> googleSignInLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK) {
                    Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                    try {
                        GoogleSignInAccount account = task.getResult(ApiException.class);
                        firebaseAuthWithGoogle(account.getIdToken());
                    } catch (ApiException e) {
                        Toast.makeText(requireContext(), getString(R.string.google_error) + " " + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });

    // --- FIREBASE İLE KAYIT/GİRİŞ YAPAR ---
    private void firebaseAuthWithGoogle(String idToken) {
        setLoadingState(true);

        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        auth.signInWithCredential(credential)
                .addOnSuccessListener(authResult -> {
                    // Kayıt Ol sayfasındayız.
                    // Adam yeni de olsa, eski de olsa Google ile doğrulandıysa içeri alıyoruz.
                    // "Hesap zaten var" hatası vermez, direkt giriş yapar.

                    boolean isNewUser = authResult.getAdditionalUserInfo().isNewUser();

                    if (isNewUser) {
                        Toast.makeText(requireContext(), getString(R.string.msg_account_created), Toast.LENGTH_SHORT)
                                .show();
                    }
                    changeScreen();
                })
                .addOnFailureListener(e -> {
                    setLoadingState(false);
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
        super.onDestroyView();
        binding = null;
    }
}