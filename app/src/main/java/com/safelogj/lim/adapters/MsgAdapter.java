package com.safelogj.lim.adapters;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.safelogj.lim.NetworkService;
import com.safelogj.lim.R;
import com.safelogj.lim.databinding.ItemMessageBinding;
import com.safelogj.lim.model.Message;

import java.util.List;
import java.util.Objects;

public class MsgAdapter extends ListAdapter<Message, MsgAdapter.MessageViewHolder> {



    private final long userId;

    public MsgAdapter(long userId) {
        super(new DiffCallback());
        this.userId = userId;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemMessageBinding binding = ItemMessageBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new MessageViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        holder.bind(getItem(position), userId);
    }

    public static class MessageViewHolder extends RecyclerView.ViewHolder {
        private final ItemMessageBinding binding;

        public MessageViewHolder(ItemMessageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(Message message, long currentUserId) {
            // Сбрасываем видимость перед установкой (важно для RecyclerView)
            binding.messageImage.setVisibility(View.GONE);
            binding.fileContainer.setVisibility(View.GONE);
            binding.messageText.setVisibility(View.VISIBLE);

            // 1. Контент: Текст
            if (message.text == null || message.text.isEmpty()) {
                binding.messageText.setVisibility(View.GONE);
            } else {
                binding.messageText.setText(message.text);
            }

            // 2. Контент: Картинка или Файл
            if (NetworkService.IMAGE.equals(message.type)) {
                if (message.filePath != null && !message.filePath.isEmpty()) {
                    binding.messageImage.setVisibility(View.VISIBLE);
                    Glide.with(itemView.getContext())
                            .load(Uri.parse(message.filePath))
                            .centerCrop()
                            .into(binding.messageImage);
                }
            } else if (NetworkService.FILE.equals(message.type)) {
                binding.fileContainer.setVisibility(View.VISIBLE);
                binding.messageFileName.setText(message.fileName);
            }

            // 3. Позиционирование и стили через ConstraintSet
            int type = message.getMessageTypeByUserId(currentUserId);
            ConstraintSet constraintSet = new ConstraintSet();
            constraintSet.clone((ConstraintLayout) itemView);

            switch (type) {
                case Message.TYPE_SYSTEM:
                    constraintSet.centerHorizontally(binding.messageBubble.getId(), ConstraintSet.PARENT_ID);
                    binding.messageBubble.setBackgroundResource(R.drawable.fielder_background_tr);
                    binding.messageTime.setVisibility(View.GONE);
                    binding.messageText.setTextColor(itemView.getContext().getColor(R.color.light_gray_aaa));
                    break;

                case Message.TYPE_OUTGOING:
                    constraintSet.clear(binding.messageBubble.getId(), ConstraintSet.START);
                    constraintSet.connect(binding.messageBubble.getId(), ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
                    binding.messageBubble.setBackgroundResource(R.drawable.fielder_background_tr);
                    binding.messageTime.setVisibility(View.VISIBLE);
                    binding.messageText.setTextColor(itemView.getContext().getColor(R.color.white));
                    binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.light_gray));
                    break;

                default: // TYPE_INCOMING
                    constraintSet.clear(binding.messageBubble.getId(), ConstraintSet.END);
                    constraintSet.connect(binding.messageBubble.getId(), ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
                    binding.messageBubble.setBackgroundResource(R.drawable.fielder_background_wt);
                    binding.messageTime.setVisibility(View.VISIBLE);
                    binding.messageText.setTextColor(itemView.getContext().getColor(R.color.black2));
                    binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.black3));
            }

            // Статус отправки (зеленое время если отправлено)
            if (message.sendStatus == Message.STATUS_SENT) {
                binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.green_600));
            }

            binding.messageTime.setText(message.formattedTime);
            constraintSet.applyTo((ConstraintLayout) itemView);
        }
    }

    /**
     * Класс для сравнения старого и нового списков
     */
    private static class DiffCallback extends DiffUtil.ItemCallback<Message> {
        @Override
        public boolean areItemsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            // Если localId одинаковый — это то же самое сообщение
            return oldItem.localId == newItem.localId;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            // Проверяем все визуально важные поля на изменение
            return oldItem.sendStatus == newItem.sendStatus &&
                    Objects.equals(oldItem.text, newItem.text) &&
                    Objects.equals(oldItem.filePath, newItem.filePath) &&
                    oldItem.serverId == newItem.serverId;
        }
    }
}
