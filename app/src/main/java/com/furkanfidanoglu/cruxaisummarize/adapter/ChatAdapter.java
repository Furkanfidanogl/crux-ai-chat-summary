package com.furkanfidanoglu.cruxaisummarize.adapter;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Patterns; // Artık bunu kullanmayacağız ama dursun
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.data.model.MessageModel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern; // 🔥 Pattern sınıfı lazım

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final List<MessageModel> messageList = new ArrayList<>();
    private static final int VIEW_TYPE_USER = 1;
    private static final int VIEW_TYPE_MODEL = 2;

    // 🔥🔥🔥 1. ÖZEL LINK DESENİ (REGEX) - "1.oyuncu" sorununu çözer 🔥🔥🔥
    // Açıklama:
    // - http:// veya https:// ile başlayanlar
    // - www. ile başlayanlar
    // - veya sonunda .com, .net, .org, .edu, .gov, .io, .ai, .tr olanlar
    // Bunun dışındaki (1.oyuncu gibi) şeyleri LİNK SAYMAZ.
    private static final Pattern STRICT_URL_PATTERN = Pattern.compile(
            "(?i)\\b((?:https?://|www\\.)\\S+|[a-z0-9.\\-]+\\.(?:com|net|org|edu|gov|io|ai|tr))\\b"
    );

    // 🔥 2. AKILLI FİLTRE (http ekleme)
    private static final Linkify.TransformFilter SMART_URL_FILTER = new Linkify.TransformFilter() {
        @Override
        public String transformUrl(Matcher match, String url) {
            if (url.toLowerCase().startsWith("http://") || url.toLowerCase().startsWith("https://")) {
                return url;
            } else {
                return "https://" + url;
            }
        }
    };

    public void addMessage(MessageModel message) {
        messageList.add(message);
        notifyItemInserted(messageList.size() - 1);
    }

    public void removeLastItem() {
        if (!messageList.isEmpty()) {
            int index = messageList.size() - 1;
            messageList.remove(index);
            notifyItemRemoved(index);
        }
    }

    public void clearMessages() {
        messageList.clear();
        notifyDataSetChanged();
    }

    public void setMessages(List<MessageModel> newMessages) {
        messageList.clear();
        messageList.addAll(newMessages);
        notifyDataSetChanged();
    }

    public MessageModel getLastItem() {
        if (messageList != null && !messageList.isEmpty()) {
            return messageList.get(messageList.size() - 1);
        }
        return null;
    }

    @Override
    public int getItemViewType(int position) {
        MessageModel message = messageList.get(position);
        return "user".equalsIgnoreCase(message.getRole()) ? VIEW_TYPE_USER : VIEW_TYPE_MODEL;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_USER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_user, parent, false);
            return new UserViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_gemini, parent, false);
            return new ModelViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MessageModel message = messageList.get(position);
        if (holder instanceof UserViewHolder) {
            ((UserViewHolder) holder).bind(message);
        } else if (holder instanceof ModelViewHolder) {
            ((ModelViewHolder) holder).bind(message);
        }
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    // --- USER VIEW HOLDER ---
    static class UserViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage;
        ImageView ivImage;

        UserViewHolder(View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.textUserMessage);
            ivImage = itemView.findViewById(R.id.imgAttached);
        }

        void bind(MessageModel message) {
            String type = message.getType();
            boolean hasText = message.getContent() != null && !message.getContent().trim().isEmpty();
            boolean hasFileName = message.getFileName() != null;
            ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) ivImage.getLayoutParams();

            if (hasText) {
                tvMessage.setText(message.getContent());
                tvMessage.setVisibility(View.VISIBLE);
                layoutParams.bottomMargin = dpToPx(4);

                // 🔥🔥🔥 KULLANICI İÇİN DÜZELTİLMİŞ LINK AYARI 🔥🔥🔥
                tvMessage.setAutoLinkMask(0);

                // Patterns.WEB_URL yerine kendi STRICT_URL_PATTERN'imizi kullanıyoruz.
                Linkify.addLinks(tvMessage, STRICT_URL_PATTERN, "https://", null, SMART_URL_FILTER);
                Linkify.addLinks(tvMessage, Patterns.EMAIL_ADDRESS, "mailto:");

                tvMessage.setMovementMethod(LinkMovementMethod.getInstance());
                tvMessage.setLinkTextColor(Color.parseColor("#FFFFFF"));
                // 🔥🔥🔥 BİTİŞ 🔥🔥🔥

            } else if (hasFileName) {
                String docText = itemView.getContext().getString(R.string.label_doc_icon) + message.getFileName();
                tvMessage.setText(docText);
                tvMessage.setVisibility(View.VISIBLE);
                layoutParams.bottomMargin = dpToPx(4);
            } else {
                tvMessage.setText("");
                tvMessage.setVisibility(View.GONE);
                layoutParams.bottomMargin = 0;
            }

            ivImage.setLayoutParams(layoutParams);

            if ("IMAGE".equals(type)) {
                ivImage.setVisibility(View.VISIBLE);
                RequestOptions options = new RequestOptions()
                        .override(600, 600)
                        .centerCrop()
                        .placeholder(android.R.drawable.ic_menu_gallery);

                if (message.getImage() != null) {
                    Glide.with(itemView.getContext())
                            .asBitmap()
                            .load(message.getImage())
                            .apply(options)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .into(ivImage);
                } else if (message.getImageUrl() != null) {
                    Glide.with(itemView.getContext())
                            .load(message.getImageUrl())
                            .apply(options)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .into(ivImage);
                }
                ivImage.setOnClickListener(v -> showFullScreenImage(itemView.getContext(), message));
            } else {
                ivImage.setVisibility(View.GONE);
                ivImage.setOnClickListener(null);
            }
        }

        private void showFullScreenImage(Context context, MessageModel message) {
            Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#E6000000")));
            }
            ImageView fullScreenImage = new ImageView(context);
            fullScreenImage.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            fullScreenImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (message.getImage() != null) {
                Glide.with(context).load(message.getImage()).into(fullScreenImage);
            } else if (message.getImageUrl() != null) {
                Glide.with(context).load(message.getImageUrl()).into(fullScreenImage);
            }
            fullScreenImage.setOnClickListener(v -> dialog.dismiss());
            dialog.setContentView(fullScreenImage);
            dialog.setCancelable(true);
            dialog.show();
        }

        private int dpToPx(int dp) {
            return (int) (dp * itemView.getContext().getResources().getDisplayMetrics().density);
        }
    }

    // --- MODEL VIEW HOLDER ---
    static class ModelViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage;

        ModelViewHolder(View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.textBotMessage);
        }

        void bind(MessageModel message) {
            if ("loading".equals(message.getId())) {
                tvMessage.setText(itemView.getContext().getString(R.string.status_typing));
            } else {
                String content = message.getContent() != null ? message.getContent() : "";
                String cleanContent = content.replace("**", "");
                tvMessage.setText(cleanContent);

                // 🔥🔥🔥 BOT İÇİN DÜZELTİLMİŞ LINK AYARI 🔥🔥🔥
                tvMessage.setAutoLinkMask(0);

                // Patterns.WEB_URL yerine STRICT_URL_PATTERN
                Linkify.addLinks(tvMessage, STRICT_URL_PATTERN, "https://", null, SMART_URL_FILTER);
                Linkify.addLinks(tvMessage, Patterns.EMAIL_ADDRESS, "mailto:");

                tvMessage.setMovementMethod(LinkMovementMethod.getInstance());
                tvMessage.setLinkTextColor(Color.parseColor("#6C63FF"));
                // 🔥🔥🔥 BİTİŞ 🔥🔥🔥
            }
        }
    }
}