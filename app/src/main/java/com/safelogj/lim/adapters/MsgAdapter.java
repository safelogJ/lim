package com.safelogj.lim.adapters;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.safelogj.lim.AppController;
import com.safelogj.lim.R;
import com.safelogj.lim.databinding.ItemMessageBinding;
import com.safelogj.lim.model.Message;

import java.io.File;
import java.util.List;
import java.util.Objects;

public class MsgAdapter extends ListAdapter<Message, MsgAdapter.MessageViewHolder> {
    private static final String STATUS = "status";
    private static final String TIME = "time";
    private static final String FILE_PATH = "file_path";

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

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads);
        } else {
            Message message = getItem(position);
            for (Object payload : payloads) {
                if (payload instanceof Bundle diff) {
                    if (diff.containsKey(STATUS)) holder.updateStatus(message.sendStatus);
                    if (diff.containsKey(TIME)) holder.updateTime(message.formattedTime);
                    if (diff.containsKey(FILE_PATH)) holder.bind(message, userId);
                }
            }
            holder.setListeners(message, userId);
        }
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
            if (Message.TYPE_IMAGE.equals(message.type) && message.isLocalFile()) {
                binding.messageImage.setVisibility(View.VISIBLE);
                Glide.with(itemView.getContext())
                        .load(Uri.parse(message.filePath))
                        .centerCrop()
                        .into(binding.messageImage);

            } else if (Message.TYPE_FILE.equals(message.type) && (message.isLocalFile())) {
                binding.fileContainer.setVisibility(View.VISIBLE);
                binding.messageFileName.setText(message.fileName);
            }

            // 3. Позиционирование и стили через ConstraintSet
            int type = message.getMessageTypeByUserId(currentUserId);
            ConstraintSet constraintSet = new ConstraintSet();
            constraintSet.clone((ConstraintLayout) itemView);

            switch (type) {
                case Message.TYPE_SYSTEM:
                    constraintSet.setHorizontalBias(binding.messageBubble.getId(), 0.5f);
                    binding.messageBubble.setBackgroundResource(R.drawable.fielder_background_tr);
                    binding.messageTime.setVisibility(View.GONE);
                    binding.messageText.setTextColor(itemView.getContext().getColor(R.color.light_gray_aaa));
                    binding.messageBubble.setGravity(Gravity.CENTER);
                    binding.messageText.setGravity(Gravity.CENTER);
                    break;

                case Message.TYPE_OUTGOING:
                    constraintSet.setHorizontalBias(binding.messageBubble.getId(), 1.0f);
                    binding.messageBubble.setBackgroundResource(R.drawable.fielder_background_tr);
                    binding.messageTime.setVisibility(View.VISIBLE);
                    binding.messageText.setTextColor(itemView.getContext().getColor(R.color.white));
                    binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.light_gray));
                    binding.messageBubble.setGravity(Gravity.END);
                    binding.messageText.setGravity(Gravity.END);
                    LinearLayout.LayoutParams fileParamsOut = (LinearLayout.LayoutParams) binding.fileContainer.getLayoutParams();
                    fileParamsOut.gravity = Gravity.END;
                    binding.fileContainer.setLayoutParams(fileParamsOut);
                    break;

                default: // TYPE_INCOMING
                    constraintSet.setHorizontalBias(binding.messageBubble.getId(), 0.0f);
                    binding.messageBubble.setBackgroundResource(R.drawable.fielder_background_green);
                    binding.messageTime.setVisibility(View.VISIBLE);
                    binding.messageText.setTextColor(itemView.getContext().getColor(R.color.black2));
                    binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.black4));
                    binding.messageBubble.setGravity(Gravity.START);
                    binding.messageText.setGravity(Gravity.START);
                    LinearLayout.LayoutParams fileParamsIn = (LinearLayout.LayoutParams) binding.fileContainer.getLayoutParams();
                    fileParamsIn.gravity = Gravity.START;
                    binding.fileContainer.setLayoutParams(fileParamsIn);
            }


            if (message.sendStatus == Message.STATUS_SENT) {
                binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.green_400));
            }

            binding.messageTime.setText(message.formattedTime);
            constraintSet.applyTo((ConstraintLayout) itemView);
            setListeners(message, currentUserId);
        }

        public void setListeners(Message message, long currentUserId) {
            binding.messageImage.setOnClickListener(v -> openFile(message, currentUserId));
            binding.fileContainer.setOnClickListener(v -> openFile(message, currentUserId));
        }

        private void openFile(Message msg, long userId) {
            if (msg.filePath == null || msg.filePath.isEmpty()) {
                return;
            }
            Context context = itemView.getContext();
            Uri contentUri;
            try {
                if (msg.getMessageTypeByUserId(userId) == Message.TYPE_OUTGOING) {
                    contentUri = Uri.parse(msg.filePath);
                } else if (msg.getMessageTypeByUserId(userId) == Message.TYPE_INCOMING) {
                    Uri rawUri = Uri.parse(msg.filePath);
                    File file = new File(Objects.requireNonNull(rawUri.getPath()));
                    if (!file.exists()) {
                        Toast.makeText(context, context.getString(R.string.file_not_found_on_disk), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    contentUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
                } else {
                    return;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(contentUri, context.getContentResolver().getType(contentUri));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(context, context.getString(R.string.no_app_to_open_file), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Error opening file: " + msg.filePath, e);
            }
        }

        public void updateStatus(long status) {
            if (status == Message.STATUS_SENT) {
                binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.green_400));
            } else {
                binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.light_gray));
            }
        }

        public void updateTime(String time) {
            Log.d(AppController.EMPTY_STRING, "отображаемое время " + time);
            binding.messageTime.setText(time);
        }
    }

    /**
     * Класс для сравнения старого и нового списков
     */
    private static class DiffCallback extends DiffUtil.ItemCallback<Message> {
        @Override
        public boolean areItemsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            // Проверяем, что это физически то же самое сообщение (по локальному ID)
            return oldItem.localId == newItem.localId
                    //  && Objects.equals(oldItem.filePath, newItem.filePath)
                    ;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            // ВАЖНО: Мы включаем сюда статус и время.
            // Если они изменятся, метод вернет false, и запустится механизм Payloads.
            return oldItem.sendStatus == newItem.sendStatus &&
                    Objects.equals(oldItem.formattedTime, newItem.formattedTime) &&
                    Objects.equals(oldItem.filePath, newItem.filePath);
        }

        @Nullable
        @Override
        public Object getChangePayload(@NonNull Message oldItem, @NonNull Message newItem) {
            Bundle diff = new Bundle();
            if (oldItem.sendStatus != newItem.sendStatus) diff.putBoolean(STATUS, true);
            if (!Objects.equals(oldItem.formattedTime, newItem.formattedTime)) diff.putBoolean(TIME, true);
            if (!Objects.equals(oldItem.filePath, newItem.filePath)) diff.putBoolean(FILE_PATH, true);
            return diff.isEmpty() ? null : diff;
        }
    }
}
