package com.furkanfidanoglu.cruxaisummarize.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.data.model.MessageModel;
import com.google.firebase.Timestamp;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

    private List<MessageModel> list = new ArrayList<>();
    private final OnItemClickListener listener;
    private final OnItemLongClickListener longListener;

    public interface OnItemClickListener {
        void onItemClick(MessageModel item);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(MessageModel item);
    }

    public HistoryAdapter(OnItemClickListener listener, OnItemLongClickListener longListener) {
        this.listener = listener;
        this.longListener = longListener;
    }

    public void setList(List<MessageModel> list) {
        this.list = list;
        notifyDataSetChanged();
    }

    public void removeItem(MessageModel item) {
        int position = list.indexOf(item);
        if (position != -1) {
            list.remove(position);
            notifyItemRemoved(position);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(list.get(position), listener, longListener);
    }

    @Override
    public int getItemCount() {
        return list != null ? list.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvDate, tvSubtitle;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvHistoryTitle);
            tvDate = itemView.findViewById(R.id.tvHistoryDate);
            tvSubtitle = itemView.findViewById(R.id.tvHistorySnippet);
        }

        public void bind(MessageModel item, OnItemClickListener listener, OnItemLongClickListener longListener) {

            String fullContent = item.getContent() != null ? item.getContent() : "";
            // "New Chat" -> Strings
            tvTitle.setText(fullContent.isEmpty() ? itemView.getContext().getString(R.string.new_chat_title) : fullContent);

            if (item.getTimestamp() != null) {
                tvDate.setText(formatTimestamp(item.getTimestamp()));
            } else {
                tvDate.setText("");
            }

            String type = item.getType() != null ? item.getType() : "TEXT";

            // 🔥 Tipler artık String Resource'dan çekiliyor (Çeviriye uyumlu)
            switch (type) {
                case "IMAGE":
                    tvSubtitle.setText(itemView.getContext().getString(R.string.type_image_analysis));
                    break;
                case "AUDIO":
                    tvSubtitle.setText(itemView.getContext().getString(R.string.type_audio_summary));
                    break;
                case "DOC":
                    tvSubtitle.setText(itemView.getContext().getString(R.string.type_doc_analysis));
                    break;
                default:
                    tvSubtitle.setText(itemView.getContext().getString(R.string.type_tap_details));
                    break;
            }

            itemView.setOnClickListener(v -> listener.onItemClick(item));
            itemView.setOnLongClickListener(v -> {
                longListener.onItemLongClick(item);
                return true;
            });
        }

        private String formatTimestamp(Timestamp timestamp) {
            Date date = timestamp.toDate();
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMM", Locale.getDefault());
            return sdf.format(date);
        }
    }
}