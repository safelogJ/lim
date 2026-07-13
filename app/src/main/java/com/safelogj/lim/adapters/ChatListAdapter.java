package com.safelogj.lim.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.safelogj.lim.R;
import com.safelogj.lim.databinding.ItemChatBinding;
import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.Message;

import java.util.List;

public class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.ChatViewHolder> {
    private final List<Chat> chatList;
    private final OnChatClickListener listener;

    public interface OnChatClickListener {
        void onChatClick(Chat chat);

        void onAvatarClick(Chat chat);

        void onChatLongClick(Chat chat);
    }

    public ChatListAdapter(List<Chat> chatList, OnChatClickListener listener) {
        this.chatList = chatList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemChatBinding binding = ItemChatBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ChatViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        holder.bind(chatList.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return chatList.size();
    }

    public static class ChatViewHolder extends RecyclerView.ViewHolder {
        private final ItemChatBinding binding;

        public ChatViewHolder(ItemChatBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(Chat chat, OnChatClickListener listener) {

            if (chat.id == Chat.INVALID_ID) {
                // Логика для элемента "Новый чат"
                binding.chatIcon.setImageResource(R.drawable.person_48px);
                binding.timeText.setVisibility(View.GONE);
            } else {
                // Логика для обычного чата
                binding.chatIcon.setImageResource(R.drawable.encrypted_48px);
                binding.timeText.setVisibility(View.VISIBLE);
            }

            if (chat.lastSendStatus == Message.STATUS_SENT) {
                binding.timeText.setTextColor(binding.getRoot().getResources().getColor(R.color.green_400,
                        binding.getRoot().getContext().getTheme()));
            } else  {
                binding.timeText.setTextColor(binding.getRoot().getResources().getColor(R.color.light_gray_aaa,
                        binding.getRoot().getContext().getTheme()));
            }
            if (chat.isBlocked) {
                binding.chatName.setTextColor(binding.getRoot().getResources().getColor(R.color.light_gray_aaa,
                        binding.getRoot().getContext().getTheme()));
            } else {
                binding.chatName.setTextColor(binding.getRoot().getResources().getColor(R.color.green_400,
                        binding.getRoot().getContext().getTheme()));
            }

            binding.chatName.setText(chat.name);
            binding.lastMessage.setText(chat.lastMessage);
            binding.timeText.setText(chat.lastTimestampFormatted);

            binding.avatarBackground.setOnClickListener(v -> listener.onAvatarClick(chat));
            itemView.setOnClickListener(v -> listener.onChatClick(chat));
            itemView.setOnLongClickListener(v -> {
                listener.onChatLongClick(chat);
                return true;
            });
        }
    }
}
