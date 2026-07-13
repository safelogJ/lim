package com.safelogj.lim.adapters;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.safelogj.lim.NetworkService;
import com.safelogj.lim.R;
import com.safelogj.lim.databinding.ItemMessageBinding;
import com.safelogj.lim.model.Message;

import java.util.List;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

    private final List<Message> messages;
    private final long userId;


    public MessageAdapter(List<Message> messages, long userId) {
        this.messages = messages;
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
        holder.bind(messages.get(position), userId);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public static class MessageViewHolder extends RecyclerView.ViewHolder {
        private final ItemMessageBinding binding;

        public MessageViewHolder(ItemMessageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        public void bind(Message message, long msgUserId) {

            // Сбрасываем видимость перед установкой (важно для RecyclerView!)
            binding.messageImage.setVisibility(View.GONE);
            binding.fileContainer.setVisibility(View.GONE);
            binding.messageText.setVisibility(View.VISIBLE);

            // Логика отображения контента
            if (NetworkService.IMAGE.equals(message.type)) {
                // Пытаемся загрузить картинку
                if (message.filePath != null && !message.filePath.isEmpty()) {
                    binding.messageImage.setVisibility(View.VISIBLE);
                    Glide.with(itemView.getContext())
                            .load(Uri.parse(message.filePath)) // Загружаем только из локального файла
                            .centerCrop()
                            .into(binding.messageImage);
                } else {
                    // Если файла еще нет на диске - скрываем картинку (показываем только текст или иконку "Скачать")
                    binding.messageImage.setVisibility(View.GONE);
                }

            } else if (NetworkService.FILE.equals(message.type)) {
                binding.fileContainer.setVisibility(View.VISIBLE);
                binding.messageFileName.setText(message.fileName);
            }

            // Если текста нет (просто файл), можно скрыть messageText
            if (message.text == null || message.text.isEmpty()) {
                binding.messageText.setVisibility(View.GONE);
            } else {
                binding.messageText.setText(message.text);
            }

            int type = message.getMessageTypeByUserId(msgUserId);
            ConstraintSet constraintSet = new ConstraintSet();
            constraintSet.clone((ConstraintLayout) itemView);

            switch (type) {
                case Message.TYPE_SYSTEM: // Системное сообщение по центру
                    constraintSet.centerHorizontally(binding.messageBubble.getId(), ConstraintSet.PARENT_ID);
                    binding.messageBubble.setBackgroundResource(R.drawable.fielder_background_tr); // Прозрачный или серый
                    binding.messageTime.setVisibility(View.GONE);
                    binding.messageText.setTextColor(itemView.getContext().getColor(R.color.light_gray_aaa));
                    break;

                case Message.TYPE_OUTGOING: // Мое сообщение справа
                    constraintSet.clear(binding.messageBubble.getId(), ConstraintSet.START);
                    constraintSet.connect(binding.messageBubble.getId(), ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
                    binding.messageBubble.setBackgroundResource(R.drawable.fielder_background_tr);
                    binding.messageTime.setVisibility(View.VISIBLE);
                    binding.messageText.setTextColor(itemView.getContext().getColor(R.color.white));
                    binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.light_gray));
                    break;
                default: // Чужое сообщение слева
                    constraintSet.clear(binding.messageBubble.getId(), ConstraintSet.END);
                    constraintSet.connect(binding.messageBubble.getId(), ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
                    binding.messageBubble.setBackgroundResource(R.drawable.fielder_background_wt);
                    binding.messageTime.setVisibility(View.VISIBLE);
                    binding.messageText.setTextColor(itemView.getContext().getColor(R.color.black2));
                    binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.black3));

            }

            if (message.sendStatus == Message.STATUS_SENT) {
                binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.green_600));
            }

            binding.messageTime.setText(message.formattedTime);
            constraintSet.applyTo((ConstraintLayout) itemView);
        }
    }

}
