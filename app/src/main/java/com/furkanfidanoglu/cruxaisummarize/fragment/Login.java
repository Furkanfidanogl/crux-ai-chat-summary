package com.furkanfidanoglu.cruxaisummarize.fragment;

import android.content.Intent;
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
import com.furkanfidanoglu.cruxaisummarize.databinding.FragmentLoginBinding;
import com.furkanfidanoglu.cruxaisummarize.util.managers.GoogleAuthManager;
import com.furkanfidanoglu.cruxaisummarize.view.HomeActivity;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.GoogleAuthProvider;

public class Login extends Fragment {

    private FragmentLoginBinding binding;
    private FirebaseAuth auth;
    private CancellationSignal googleSignInCancellation;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        // XML Bağlantısı
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        auth = FirebaseAuth.getInstance();

        binding.btnGoogleLogin.setOnClickListener(v -> signInWithGoogle());

        // Normal Giriş
        binding.btnLogin.setOnClickListener(v -> loginFunc());

        // Link: Kayıt Ol Ekranına Git
        binding.tvGoToSignUp.setOnClickListener(v -> {
            Navigation.findNavController(v).navigate(R.id.action_login_to_signUp);
        });

        // Link: Şifremi Unuttum
        binding.tvForgotPassword.setOnClickListener(this::forgotPasswordFunc);
    }

    private void signInWithGoogle() {
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

    // --- FIREBASE İLE GİRİŞ KONTROLÜ (İşte istediğin mantık burada) ---
    private void firebaseAuthWithGoogle(String idToken) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);

        auth.signInWithCredential(credential)
                .addOnSuccessListener(authResult -> {

                    // YENİ KULLANICI MI KONTROLÜ
                    boolean isNewUser = authResult.getAdditionalUserInfo() != null
                            && authResult.getAdditionalUserInfo().isNewUser();

                    if (isNewUser) {
                        // ADIM 1: Yeni kullanıcı Login sayfasında Google ile giriş yaptı, ama kayıt olmamış.
                        // ADIM 2: Hesabı silmek yerine, SignUp'a yönlendir.
                        if (auth.getCurrentUser() == null) {
                            handleNewGoogleUser();
                        } else {
                            auth.getCurrentUser().delete().addOnCompleteListener(task -> handleNewGoogleUser());
                        }
                    } else {
                        changeScreen();
                    }
                })
                .addOnFailureListener(e -> {
                    setLoadingState(false);
                    Toast.makeText(requireContext(), R.string.google_error, Toast.LENGTH_LONG).show();
                });
    }

    private void handleNewGoogleUser() {
        auth.signOut();
        GoogleAuthManager.clearCredentialState(requireContext(), () -> {
            if (!isAdded() || binding == null) return;
            setLoadingState(false);
            Toast.makeText(requireContext(), R.string.error_login_new_user, Toast.LENGTH_LONG).show();
            Navigation.findNavController(requireView()).navigate(R.id.action_login_to_signUp);
        });
    }

    // --- DİĞER FONKSİYONLAR (AYNEN KORUNDU) ---

    private void loginFunc() {
        String email = binding.emailInput.getText().toString().trim();
        String password = binding.passwordInput.getText().toString();

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.msg_fill_all_fields), Toast.LENGTH_SHORT).show();
            return;
        }

        setLoadingState(true);

        auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(authResult -> {
                    changeScreen();
                })
                .addOnFailureListener(e -> {
                    setLoadingState(false);
                    Toast.makeText(requireContext(), getString(R.string.error_login_failed), Toast.LENGTH_LONG).show();
                });
    }

    private void forgotPasswordFunc(View view) {
        String email = binding.emailInput.getText().toString().trim();
        if (email.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.msg_enter_email), Toast.LENGTH_SHORT).show();
            return;
        }

        auth.sendPasswordResetEmail(email)
                .addOnSuccessListener(unused -> {
                    Toast.makeText(requireContext(), getString(R.string.msg_reset_email_sent), Toast.LENGTH_SHORT)
                            .show();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(requireContext(), getString(R.string.error_reset_failed), Toast.LENGTH_LONG).show();
                });
    }

    private void setLoadingState(boolean isLoading) {
        if (isLoading) {
            binding.btnLogin.setEnabled(false);
            binding.btnLogin.setText("");
            binding.progressBar.setVisibility(View.VISIBLE);
            binding.btnGoogleLogin.setEnabled(false); // Google'ı da kilitle
        } else {
            binding.btnLogin.setEnabled(true);
            binding.btnLogin.setText(getString(R.string.btn_login));
            binding.progressBar.setVisibility(View.GONE);
            binding.btnGoogleLogin.setEnabled(true);
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
