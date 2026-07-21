package com.safelogj.lim.adapters;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.safelogj.lim.R;
import com.safelogj.lim.databinding.ItemChatBinding;
import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.Message;

import java.util.List;
import java.util.Objects;

public class ChatListAdapter extends ListAdapter<Chat, ChatListAdapter.ChatViewHolder> {

    private static final String HAS_NEW = "has_new";
    private static final String TIME = "time";
    private static final String BLOCK = "block";
    private static final String SEND = "send";
    private static final String NAME = "name";
    private static final String MSG = "msg";
    private final OnChatClickListener listener;

    public interface OnChatClickListener {
        void onChatClick(Chat chat);
        void onAvatarClick(Chat chat);
        void onChatLongClick(Chat chat);
    }

    public ChatListAdapter(OnChatClickListener listener) {
        super(new ChatDiffCallback());
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
        holder.bind(getItem(position), listener);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads);
        } else {
            Chat chat = getItem(position);
            for (Object payload : payloads) {
                if (payload instanceof Bundle diff) {
                    if (diff.containsKey(HAS_NEW)) holder.updateNewMsgStatus(chat.hasNewMsg);
                    if (diff.containsKey(TIME)) holder. binding.timeText.setText(chat.lastTimestampFormatted);
                    if (diff.containsKey(BLOCK)) holder.updateBlockStatus(chat);
                    if (diff.containsKey(SEND)) holder.updateSendStatus(chat);
                    if (diff.containsKey(NAME)) holder.binding.chatName.setText(chat.name);
                    if (diff.containsKey(MSG)) holder.binding.lastMessage.setText(chat.lastMessage);
                }
            }
              holder.setListeners(chat, listener);
        }
    }

    public static class ChatViewHolder extends RecyclerView.ViewHolder {
        private final ItemChatBinding binding;

        public ChatViewHolder(ItemChatBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(Chat chat, OnChatClickListener listener) {
            // Иконка
            if (chat.id == Chat.INVALID_ID) {
                binding.chatIcon.setImageResource(R.drawable.person_48px);
                binding.timeText.setVisibility(View.GONE);
            } else {
                binding.chatIcon.setImageResource(R.drawable.encrypted_48px);
                binding.timeText.setVisibility(View.VISIBLE);
            }

            // Цвет времени (статус отправки последнего)
            if (chat.lastSendStatus == Message.STATUS_SENT) {
                binding.timeText.setTextColor(itemView.getContext().getColor(R.color.green_400));
            } else {
                binding.timeText.setTextColor(itemView.getContext().getColor(R.color.light_gray_aaa));
            }

            // Цвет имени (заблокирован или нет)
            binding.chatName.setTextColor(itemView.getContext().getColor(
                    chat.isBlocked ? R.color.light_gray_aaa : R.color.green_400));

            binding.chatName.setText(chat.name);
            binding.lastMessage.setText(chat.lastMessage);
            binding.timeText.setText(chat.lastTimestampFormatted);

            // Настраиваем фон аватара (новое сообщение)
            updateNewMsgStatus(chat.hasNewMsg);
            setListeners(chat, listener);
        }

        public void setListeners(Chat chat, OnChatClickListener listener) {
            binding.avatarBackground.setOnClickListener(v -> listener.onAvatarClick(chat));
            itemView.setOnClickListener(v -> listener.onChatClick(chat));
            itemView.setOnLongClickListener(v -> {
                listener.onChatLongClick(chat);
                return true;
            });
        }

        public void updateNewMsgStatus(boolean hasNew) {
            binding.avatarBackground.setBackgroundResource(hasNew ?
                    R.drawable.new_msg_chat_background_red : R.drawable.fielder_background);
        }

        public void updateBlockStatus(Chat chat) {
            binding.chatName.setTextColor(itemView.getContext().getColor(
                    chat.isBlocked ? R.color.light_gray_aaa : R.color.green_400));
        }

        public void updateSendStatus(Chat chat) {
            if (chat.lastSendStatus == Message.STATUS_SENT) {
                binding.timeText.setTextColor(itemView.getContext().getColor(R.color.green_400));
            } else {
                binding.timeText.setTextColor(itemView.getContext().getColor(R.color.light_gray_aaa));
            }
        }
    }

    private static class ChatDiffCallback extends DiffUtil.ItemCallback<Chat> {
        @Override
        public boolean areItemsTheSame(@NonNull Chat oldItem, @NonNull Chat newItem) {
            return oldItem.id == newItem.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Chat oldItem, @NonNull Chat newItem) {
            return oldItem.hasNewMsg == newItem.hasNewMsg &&
                    oldItem.lastTimestamp == newItem.lastTimestamp &&
                    oldItem.isBlocked == newItem.isBlocked &&
                    oldItem.lastSendStatus == newItem.lastSendStatus &&
                    Objects.equals(oldItem.name, newItem.name) &&
                    Objects.equals(oldItem.lastMessage, newItem.lastMessage);
        }

        @Nullable
        @Override
        public Object getChangePayload(@NonNull Chat oldItem, @NonNull Chat newItem) {
            Bundle diff = new Bundle();
            if (oldItem.hasNewMsg != newItem.hasNewMsg) diff.putBoolean(HAS_NEW, true);
            if (oldItem.lastTimestamp != newItem.lastTimestamp) diff.putBoolean(TIME, true);
            if (oldItem.isBlocked != newItem.isBlocked) diff.putBoolean(BLOCK, true);
            if (oldItem.lastSendStatus != newItem.lastSendStatus) diff.putBoolean(SEND, true);
            if (!Objects.equals(oldItem.name, newItem.name)) diff.putBoolean(NAME, true);
            if (!Objects.equals(oldItem.lastMessage, newItem.lastMessage)) diff.putBoolean(MSG, true);
            return diff.isEmpty() ? null : diff;
        }
    }
}
