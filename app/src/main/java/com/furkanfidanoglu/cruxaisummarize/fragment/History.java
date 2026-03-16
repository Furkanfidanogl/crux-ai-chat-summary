package com.furkanfidanoglu.cruxaisummarize.fragment;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.adapter.HistoryAdapter;
import com.furkanfidanoglu.cruxaisummarize.data.model.MessageModel;
import com.furkanfidanoglu.cruxaisummarize.databinding.FragmentHistoryBinding;
import com.furkanfidanoglu.cruxaisummarize.util.managers.FirebaseDBManager;
import com.furkanfidanoglu.cruxaisummarize.util.managers.SessionManager; // 🔥 BU IMPORT EKLENDİ
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.List;

public class History extends Fragment {

    private FragmentHistoryBinding binding;
    private HistoryAdapter adapter;
    private String currentUid;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentHistoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        }

        setupRecyclerView();
        binding.btnDeleteAll.setVisibility(View.GONE);

        binding.btnDeleteAll.setOnClickListener(v -> showDeleteAllConfirmationDialog());

        loadHistoryFromFirebase();
    }

    private void setupRecyclerView() {
        adapter = new HistoryAdapter(
                item -> {
                    // Tıklanınca o sohbete git
                    Bundle bundle = new Bundle();
                    bundle.putString("chatId", item.getId());
                    Navigation.findNavController(requireView()).navigate(R.id.action_nav_history_to_nav_text, bundle);
                },
                item -> showSingleDeleteDialog(item) // Uzun basınca sil
        );

        binding.rvHistory.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.rvHistory.setAdapter(adapter);
    }

    private void showSingleDeleteDialog(MessageModel item) {
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.dialog_delete_chat_title))
                .setMessage(getString(R.string.dialog_delete_chat_msg))
                .setPositiveButton(getString(R.string.action_delete), (dialog, which) -> performSingleDelete(item))
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void performSingleDelete(MessageModel item) {
        FirebaseDBManager.getInstance().deleteChat(item.getId(), new FirebaseDBManager.DeleteCallback() {
            @Override
            public void onSuccess() {
                if (!isAdded()) return;

                // 🔥 KRİTİK DÜZELTME: Sildiğimiz sohbet, şu an açık olan mı?
                String activeChatId = SessionManager.getInstance().getActiveChatId();
                if (activeChatId != null && activeChatId.equals(item.getId())) {
                    // Evet, aktif sohbeti sildik. O zaman hafızayı temizle ki "New Chat" açılsın.
                    SessionManager.getInstance().clearSession();
                }

                adapter.removeItem(item);
                Toast.makeText(getContext(), getString(R.string.msg_chat_deleted), Toast.LENGTH_SHORT).show();

                if (adapter.getItemCount() == 0) {
                    updateEmptyState(true);
                }
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                Toast.makeText(getContext(), getString(R.string.error_prefix) + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showDeleteAllConfirmationDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.dialog_delete_all_title))
                .setMessage(getString(R.string.dialog_delete_all_msg))
                .setPositiveButton(getString(R.string.action_delete_all), (dialog, which) -> performDeleteAll())
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void performDeleteAll() {
        Toast.makeText(getContext(), getString(R.string.msg_deleting_all), Toast.LENGTH_SHORT).show();

        FirebaseDBManager.getInstance().deleteAllHistory(new FirebaseDBManager.DeleteCallback() {
            @Override
            public void onSuccess() {
                if (!isAdded()) return;

                // 🔥 HER ŞEY SİLİNDİĞİ İÇİN OTURUMU DA SIFIRLIYORUZ
                SessionManager.getInstance().clearSession();

                adapter.setList(new ArrayList<>());
                updateEmptyState(true);
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                Toast.makeText(getContext(), getString(R.string.error_prefix) + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadHistoryFromFirebase() {
        if (currentUid == null) return;

        FirebaseDBManager.getInstance().getUserChats(new FirebaseDBManager.ChatListCallback() {
            @Override
            public void onSuccess(List<MessageModel> chatList) {
                if (!isAdded()) return;

                if (chatList.isEmpty()) {
                    updateEmptyState(true);
                } else {
                    updateEmptyState(false);
                    adapter.setList(chatList);
                }
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                updateEmptyState(true);
            }
        });
    }

    private void updateEmptyState(boolean isEmpty) {
        if (isEmpty) {
            // Liste BOŞ ise:
            binding.layoutEmptyHistory.setVisibility(View.VISIBLE); // Boş resmi göster
            binding.rvHistory.setVisibility(View.GONE);             // Listeyi gizle

            binding.btnDeleteAll.setVisibility(View.GONE);

        } else {
            // Liste DOLU ise:
            binding.layoutEmptyHistory.setVisibility(View.GONE);    // Boş resmi gizle
            binding.rvHistory.setVisibility(View.VISIBLE);          // Listeyi göster

            binding.btnDeleteAll.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}