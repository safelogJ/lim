package com.safelogj.lim.adapters;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
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
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.safelogj.lim.AppController;
import com.safelogj.lim.R;
import com.safelogj.lim.databinding.ItemMessageBinding;
import com.safelogj.lim.model.Message;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class MsgAdapter extends ListAdapter<Message, MsgAdapter.MessageViewHolder> {
    private static final String STATUS = "status";
    private static final String TIME = "time";
    private static final String FILE_PATH = "file_path";

    private final int userId;
    private final int chatColor;
    private MediaPlayer mediaPlayer;
    private long playingMsgId = -1;
    private ItemMessageBinding playingBinding;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && mediaPlayer.isPlaying() && playingBinding != null) {
                int pos = mediaPlayer.getCurrentPosition();
                playingBinding.audioPlayer.audioSeekBar.setProgress(pos);
                playingBinding.audioPlayer.tvAudioTime.setText(formatDuration(pos));
                progressHandler.postDelayed(this, 500);

            }
        }
    };

    public MsgAdapter(int userId, int chatColor) {
        super(new DiffCallback());
        this.userId = userId;
        this.chatColor = chatColor;
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
        holder.bind(getItem(position), userId, chatColor, this);
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
                    if (diff.containsKey(TIME)) holder.updateTime(message.timestamp);
                    if (diff.containsKey(FILE_PATH)) holder.bind(message, userId, chatColor, this);
                }
            }
            holder.setListeners(message, userId);
        }
    }

    @Override
    public void onViewRecycled(@NonNull MessageViewHolder holder) {
        super.onViewRecycled(holder);
        if (playingBinding != null && holder.binding == playingBinding) {
            playingBinding = null;
        }
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        stopPlaying();
        super.onDetachedFromRecyclerView(recyclerView);
    }

    public static class MessageViewHolder extends RecyclerView.ViewHolder {
        private final ItemMessageBinding binding;

        public MessageViewHolder(ItemMessageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        private void bind(Message message, int currentUserId, int color, MsgAdapter adapter) {
            // Сбрасываем видимость перед установкой (важно для RecyclerView)
            binding.messageImage.setVisibility(View.GONE);
            binding.fileContainer.setVisibility(View.GONE);
            binding.audioPlayer.audioPlayerContainer.setVisibility(View.GONE);
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
                        .override(800, 800) // Ограничиваем размер превью, это ОЧЕНЬ ускорит прокрутку
                        .centerInside()
                        .placeholder(R.drawable.fielder_background_tr) // Занимаем место до загрузки
                        //   .diskCacheStrategy(DiskCacheStrategy.ALL) // Кешируем все версии
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .into(binding.messageImage);

            } else if (isAudioFile(message.fileName) && message.isLocalFile()) {
                binding.fileContainer.setVisibility(View.GONE);
                binding.audioPlayer.audioPlayerContainer.setVisibility(View.VISIBLE);
                adapter.setupAudioPlayer(message, binding);
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
                    binding.messageBubbleContent.setGravity(Gravity.CENTER);
                    binding.messageText.setGravity(Gravity.CENTER);
                    break;

                case Message.TYPE_OUTGOING:
                    constraintSet.setHorizontalBias(binding.messageBubble.getId(), 1.0f);
                    binding.messageBubble.setBackgroundResource(R.drawable.fielder_background_tr);
                    binding.messageTime.setVisibility(View.VISIBLE);
                    binding.messageText.setTextColor(itemView.getContext().getColor(R.color.white));
                    binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.light_gray));
                    binding.messageBubbleContent.setGravity(Gravity.END);
                    binding.messageText.setGravity(Gravity.END);
                    LinearLayout.LayoutParams fileParamsOut = (LinearLayout.LayoutParams) binding.fileContainer.getLayoutParams();
                    fileParamsOut.gravity = Gravity.END;
                    binding.fileContainer.setLayoutParams(fileParamsOut);
                    break;

                default: // TYPE_INCOMING
                    constraintSet.setHorizontalBias(binding.messageBubble.getId(), 0.0f);
                    binding.messageBubble.setBackgroundResource(AppController.getInterlocutorBackground(color));
                    binding.messageTime.setVisibility(View.VISIBLE);
                    binding.messageText.setTextColor(itemView.getContext().getColor(R.color.main_background));
                    binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.main_background));
                    binding.messageBubbleContent.setGravity(Gravity.START);
                    binding.messageText.setGravity(Gravity.START);
                    LinearLayout.LayoutParams fileParamsIn = (LinearLayout.LayoutParams) binding.fileContainer.getLayoutParams();
                    fileParamsIn.gravity = Gravity.START;
                    binding.fileContainer.setLayoutParams(fileParamsIn);
            }


            if (message.sendStatus == Message.STATUS_SENT) {
                binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.last_time));
            }

            binding.messageTime.setText(com.safelogj.lim.AppController.formatSmartTime(itemView.getContext(), message.timestamp));
            constraintSet.applyTo((ConstraintLayout) itemView);
            updateContentDescription(message, currentUserId);
            setListeners(message, currentUserId);
        }

        private void updateContentDescription(Message message, int currentUserId) {
            StringBuilder sb = new StringBuilder();
            Context context = itemView.getContext();

            int type = message.getMessageTypeByUserId(currentUserId);
            if (type == Message.TYPE_OUTGOING) {
                sb.append(context.getString(R.string.you)).append(": ");
            } else if (type == Message.TYPE_INCOMING) {
                sb.append(context.getString(R.string.interlocutor)).append(": ");
            }

            if (message.text != null && !message.text.isEmpty()) {
                sb.append(message.text).append(". ");
            }

            if (Message.TYPE_IMAGE.equals(message.type)) {
                sb.append(context.getString(R.string.image)).append(". ");
            } else if (isAudioFile(message.fileName)) {
                sb.append(context.getString(R.string.voice_message)).append(". ");
            } else if (Message.TYPE_FILE.equals(message.type)) {
                sb.append(context.getString(R.string.file_name)).append(": ").append(message.fileName).append(". ");
            }

            sb.append(AppController.formatSmartTime(context, message.timestamp));
            binding.messageBubble.setContentDescription(sb.toString());
        }

        private void setListeners(Message message, int currentUserId) {
            binding.messageImage.setOnClickListener(v -> openFile(message, currentUserId));
            binding.fileContainer.setOnClickListener(v -> openFile(message, currentUserId));
        }

        private void openFile(Message msg, int userId) {
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

        private boolean isAudioFile(String fileName) {
            if (fileName == null) return false;
            String name = fileName.toLowerCase();
            return name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".wav") || name.endsWith(".ogg");
        }

        private void updateStatus(int status) {
            if (status == Message.STATUS_SENT) {
                binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.last_time));
            } else {
                binding.messageTime.setTextColor(itemView.getContext().getColor(R.color.light_gray));
            }
        }

        private void updateTime(long timestamp) {
            binding.messageTime.setText(AppController.formatSmartTime(itemView.getContext(), timestamp));
        }
    }
    /**
     * Класс для сравнения старого и нового списков
     */
    private static class DiffCallback extends DiffUtil.ItemCallback<Message> {
        @Override
        public boolean areItemsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            if (oldItem.senderId == Message.SYSTEM_SENDER_ID && newItem.senderId == Message.SYSTEM_SENDER_ID) {
                return Objects.equals(oldItem.text, newItem.text);
            }
            // Проверяем, что это физически то же самое сообщение (по локальному ID)
            return oldItem.localId == newItem.localId;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Message oldItem, @NonNull Message newItem) {
            return oldItem.sendStatus == newItem.sendStatus &&
                    oldItem.timestamp == newItem.timestamp &&
                    Objects.equals(oldItem.filePath, newItem.filePath);
        }

        @Nullable
        @Override
        public Object getChangePayload(@NonNull Message oldItem, @NonNull Message newItem) {
            Bundle diff = new Bundle();
            if (oldItem.sendStatus != newItem.sendStatus) diff.putBoolean(STATUS, true);
            if (oldItem.timestamp != newItem.timestamp) diff.putBoolean(TIME, true);
            if (!Objects.equals(oldItem.filePath, newItem.filePath)) diff.putBoolean(FILE_PATH, true);
            return diff.isEmpty() ? null : diff;
        }
    }

    // Логика управления плеером
    private void setupAudioPlayer(Message msg, ItemMessageBinding binding) {
        // 1. Состояние при отрисовке (восстанавливаем прогресс если это играющий бабл)
        if (playingMsgId == msg.localId && mediaPlayer != null) {
            playingBinding = binding;
            binding.audioPlayer.btnPlayPause.setImageResource(R.drawable.pause_48px);
            binding.audioPlayer.audioSeekBar.setMax(mediaPlayer.getDuration());
            progressHandler.post(progressRunnable);
        } else {
            binding.audioPlayer.btnPlayPause.setImageResource(R.drawable.play_arrow_48px);
            binding.audioPlayer.audioSeekBar.setProgress(0);
            binding.audioPlayer.tvAudioTime.setText(R.string.zero_time);
        }

        // 2. Клик на Play/Pause
        binding.audioPlayer.btnPlayPause.setOnClickListener(v -> {
            if (playingMsgId == msg.localId && mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    binding.audioPlayer.btnPlayPause.setImageResource(R.drawable.play_arrow_48px);
                } else {
                    mediaPlayer.start();
                    binding.audioPlayer.btnPlayPause.setImageResource(R.drawable.pause_48px);
                    progressHandler.post(progressRunnable);
                }
            } else {
                startPlaying(msg, binding);
            }
        });

        // 3. Перемотка
        binding.audioPlayer.audioSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && playingMsgId == msg.localId && mediaPlayer != null) {
                    mediaPlayer.seekTo(progress);
                    binding.audioPlayer.tvAudioTime.setText(formatDuration(progress));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
               //
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (playingMsgId != msg.localId) {
                    seekBar.setProgress(0);
                    binding.audioPlayer.tvAudioTime.setText(R.string.zero_time);
                }
            }
        });
    }

    private void startPlaying(Message msg, ItemMessageBinding binding) {
        if (msg.filePath == null || msg.filePath.isEmpty()) return;
        Context context = binding.getRoot().getContext();
        try {
            Uri uri = Uri.parse(msg.filePath);
            if (!isFileExist(context, uri)) {
                Toast.makeText(context, context.getString(R.string.file_not_found_on_disk), Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception e) {
            Toast.makeText(context, context.getString(R.string.file_not_found_on_disk), Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            stopPlaying(); // Останавливаем старый трек
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
            mediaPlayer.setDataSource(binding.getRoot().getContext(), Uri.parse(msg.filePath));
            mediaPlayer.prepare();
            mediaPlayer.start();

            playingMsgId = msg.localId;
            playingBinding = binding;
            binding.audioPlayer.audioSeekBar.setMax(mediaPlayer.getDuration());
            binding.audioPlayer.btnPlayPause.setImageResource(R.drawable.pause_48px);
            progressHandler.post(progressRunnable);

            mediaPlayer.setOnCompletionListener(mp -> stopPlaying());
        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, "Playback error", e);
        }
    }

    public void stopPlaying() {
        progressHandler.removeCallbacks(progressRunnable);
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        playingMsgId = -1;
        if (playingBinding != null) {
            playingBinding.audioPlayer.btnPlayPause.setImageResource(R.drawable.play_arrow_48px);
            playingBinding.audioPlayer.audioSeekBar.setProgress(0);
            playingBinding = null;
        }
    }

    public void pausePlaying() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            progressHandler.removeCallbacks(progressRunnable);
            if (playingBinding != null) {
                playingBinding.audioPlayer.btnPlayPause.setImageResource(R.drawable.play_arrow_48px);
            }
        }
    }

    private String formatDuration(int ms) {
        return String.format(Locale.US, "%02d:%02d", ms / (1000 * 60), (ms / 1000) % 60);
    }

    private boolean isFileExist(Context context, Uri uri) {
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null) {
                File file = new File(path);
                return file.exists();
            }
        } else if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        return cursor.getLong(sizeIndex) > 0;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
