package com.furkanfidanoglu.cruxaisummarize.fragment;

import android.content.Intent;
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
import com.furkanfidanoglu.cruxaisummarize.databinding.FragmentLoginBinding;
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

public class Login extends Fragment {

    private FragmentLoginBinding binding;
    private FirebaseAuth auth;
    private GoogleSignInClient mGoogleSignInClient;

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

        // --- 1. GOOGLE AYARLARI ---
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(requireActivity(), gso);

        // --- 2. GOOGLE BUTONU TIKLAMA (İsmi düzelttim: btnGoogleSign) ---
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

    // --- FIREBASE İLE GİRİŞ KONTROLÜ (İşte istediğin mantık burada) ---
    private void firebaseAuthWithGoogle(String idToken) {
        setLoadingState(true);
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);

        auth.signInWithCredential(credential)
                .addOnSuccessListener(authResult -> {

                    // YENİ KULLANICI MI KONTROLÜ
                    boolean isNewUser = authResult.getAdditionalUserInfo().isNewUser();

                    if (isNewUser) {
                        // ADIM 1: Yeni kullanıcı Login sayfasında Google ile giriş yaptı, ama kayıt olmamış.
                        // ADIM 2: Hesabı silmek yerine, SignUp'a yönlendir.
                        auth.getCurrentUser().delete().addOnCompleteListener(task -> {
                            // ADIM 3: Google ve Firebase'den çıkış yapıp temizliyoruz
                            FirebaseAuth.getInstance().signOut();
                            mGoogleSignInClient.signOut();

                            setLoadingState(false);
                            // Kullanıcıya kayıt olmasını söyle
                            Toast.makeText(requireContext(), getString(R.string.error_login_new_user), Toast.LENGTH_LONG).show();
                            // SignUp'a yönlendir
                            Navigation.findNavController(requireView()).navigate(R.id.action_login_to_signUp);
                        });
                    } else {
                        changeScreen();
                    }
                })
                .addOnFailureListener(e -> {
                    setLoadingState(false);
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
        super.onDestroyView();
        binding = null;
    }
}