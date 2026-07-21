package com.safelogj.lim.adapters;

import android.content.ActivityNotFoundException;
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
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.safelogj.lim.AppController;
import com.safelogj.lim.NetworkService;
import com.safelogj.lim.R;
import com.safelogj.lim.databinding.ItemMessageBinding;
import com.safelogj.lim.model.Message;

import java.util.List;
import java.util.Objects;

public class MsgAdapter extends ListAdapter<Message, MsgAdapter.MessageViewHolder> {
    private static final String STATUS = "status";
    private static final String TIME = "time";

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
            // Если правок нет — вызываем стандартную полную отрисовку
            super.onBindViewHolder(holder, position, payloads);
        } else {
            // Если пришли "пайлоады", обновляем только нужные вьюхи
            for (Object payload : payloads) {
                if (payload instanceof Bundle diff) {
                    if (diff.containsKey(STATUS)) {
                        holder.updateStatus(diff.getLong(STATUS));
                    }
                    if (diff.containsKey(TIME)) {
                        holder.updateTime(diff.getString(TIME));
                    }
                }
            }
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
            if (Message.TYPE_IMAGE.equals(message.type)) {
                if (message.filePath != null && !message.filePath.isEmpty()) {
                    binding.messageImage.setVisibility(View.VISIBLE);
                    Glide.with(itemView.getContext())
                            .load(Uri.parse(message.filePath))
                            .centerCrop()
                            .into(binding.messageImage);
                }
            } else if (Message.TYPE_FILE.equals(message.type)) {
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
                    binding.messageBubble.setBackgroundResource(R.drawable.fielder_background_wt);
                    binding.messageTime.setVisibility(View.VISIBLE);
                    binding.messageText.setTextColor(itemView.getContext().getColor(R.color.black2));
                    binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.black3));
                    binding.messageBubble.setGravity(Gravity.START);
                    binding.messageText.setGravity(Gravity.START);
                    LinearLayout.LayoutParams fileParamsIn = (LinearLayout.LayoutParams) binding.fileContainer.getLayoutParams();
                    fileParamsIn.gravity = Gravity.START;
                    binding.fileContainer.setLayoutParams(fileParamsIn);
            }


            if (message.sendStatus == Message.STATUS_SENT) {
                binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.green_600));
            }

            binding.messageTime.setText(message.formattedTime);
            constraintSet.applyTo((ConstraintLayout) itemView);
            // 4. Слушатели нажатий для открытия файлов
            binding.messageImage.setOnClickListener(v -> openFile(message.filePath));
            binding.fileContainer.setOnClickListener(v -> openFile(message.filePath));
        }

        private void openFile(String path) {
            if (path == null || path.isEmpty()) return;
            try {
                Uri uri = Uri.parse(path);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, itemView.getContext().getContentResolver().getType(uri));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                itemView.getContext().startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(itemView.getContext(), itemView.getContext().getString(R.string.no_app_to_open_file), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Error opening file: " + path, e);
            }
        }

        public void updateStatus(long status) {
            if (status == Message.STATUS_SENT) {
                binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.green_600));
            } else {
                binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.light_gray));
            }
        }

        public void updateTime(String time) {
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
            return oldItem.localId == newItem.localId;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            // ВАЖНО: Мы включаем сюда статус и время.
            // Если они изменятся, метод вернет false, и запустится механизм Payloads.
            return oldItem.sendStatus == newItem.sendStatus &&
                    Objects.equals(oldItem.formattedTime, newItem.formattedTime) &&
                    Objects.equals(oldItem.text, newItem.text) &&
                    Objects.equals(oldItem.type, newItem.type) &&
                    Objects.equals(oldItem.filePath, newItem.filePath);
        }

        @Nullable
        @Override
        public Object getChangePayload(@NonNull Message oldItem, @NonNull Message newItem) {
            // А вот здесь мы вычисляем, ЧТО конкретно изменилось
            Bundle diff = new Bundle();
            if (oldItem.sendStatus != newItem.sendStatus) {
                diff.putLong(STATUS, newItem.sendStatus);
            }
            if (!Objects.equals(oldItem.formattedTime, newItem.formattedTime)) {
                diff.putString(TIME, newItem.formattedTime);
            }
            return diff.isEmpty() ? null : diff;
        }
    }
}
