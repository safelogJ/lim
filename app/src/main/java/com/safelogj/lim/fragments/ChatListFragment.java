package com.safelogj.lim.fragments;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;

import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.textfield.TextInputEditText;
import com.safelogj.lim.AppController;
import com.safelogj.lim.MainActivity;
import com.safelogj.lim.NotificationHelper;
import com.safelogj.lim.R;
import com.safelogj.lim.adapters.ChatListAdapter;
import com.safelogj.lim.databinding.FragmentChatListBinding;
import com.safelogj.lim.model.Chat;

import java.util.List;
import java.util.Map;

public class ChatListFragment extends Fragment {

    private AppController controller;
    private FragmentChatListBinding mBinding;

    public ChatListFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = (AppController) requireActivity().getApplication();

    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mBinding = FragmentChatListBinding.inflate(getLayoutInflater());
        return mBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ChatListAdapter adapter = new ChatListAdapter(new ChatListAdapter.OnChatClickListener() {
            @Override
            public void onChatClick(Chat chat) {
                MainActivity activity = (MainActivity) requireActivity();
                if (chat.id == Chat.INVALID_ID) {
                    if (controller.getUserId() > 0) {
                        activity.showFragment(ChatFragment.newInstance(chat.id, chat.localId, chat.name));
                    } else {
                        activity.showFragment(new UserFragment());
                    }
                } else {
                    activity.showFragment(ChatFragment.newInstance(chat.id, chat.localId, chat.name));
                }
            }

            @Override
            public void onAvatarClick(Chat chat) {
                MainActivity activity = (MainActivity) requireActivity();
                if (chat.id == Chat.INVALID_ID) {
                    activity.showFragment(new UserFragment());
                } else {
                    activity.showFragment(ChatFragment.newInstance(chat.id, chat.localId, chat.name));
                }
            }

            @Override
            public void onChatLongClick(Chat chat) {
                if (chat.id != Chat.INVALID_ID) {
                    showChatOptionsDialog(chat);
                }
            }
        });
        mBinding.chatsRecyclerView.setAdapter(adapter);
        setObserverUnreadChats();
        setObserverChatList(adapter);
        setObserveOnlineMap(adapter);
    }

    private void setObserveOnlineMap(ChatListAdapter adapter) {
        controller.getOnlineMapTrigger().observe(getViewLifecycleOwner(), onlineMap -> {
            List<Chat> currentList = adapter.getCurrentList();
            for (int i = 0; i < currentList.size(); i++) {
                Chat chat = currentList.get(i);
                if (chat.id == Chat.INVALID_ID) continue;
                // Ищем статус именно для этого чата в пришедшей карте
                Map<Long, Boolean> userChats = onlineMap.get(chat.interlocutorId);
                if (userChats != null && userChats.containsKey(chat.id)) {
                    boolean isOnline = Boolean.TRUE.equals(userChats.get(chat.id));
                    // Обновляем только если статус реально отличается от того, что в адаптере
                    if (chat.isOnline != isOnline) {
                        chat.isOnline = isOnline;
                        Bundle payload = new Bundle();
                        payload.putBoolean("online", true); // Константа из адаптера
                        adapter.notifyItemChanged(i, payload);
                    }
                }
            }
        });
    }

    private void setObserverUnreadChats() {
        controller.getUnreadChatTrigger().observe(getViewLifecycleOwner(), unreadChatList -> unreadChatList.stream()
                .mapToLong(c -> c.lastTimestamp).max().ifPresent(max -> controller.lastNotifiedTimestamp.accumulateAndGet(max, Math::max)));
    }

    private void setObserverChatList(ChatListAdapter adapter) {
        controller.getChatListTrigger().observe(getViewLifecycleOwner(), chatList -> {
            for (Chat chat : chatList) {
                if (chat.id != Chat.INVALID_ID) {
                    Map<Long, Boolean> userChats = controller.getChatStatuses(chat.interlocutorId);
                    if (userChats != null && userChats.containsKey(chat.id)) {
                        Boolean status = userChats.get(chat.id);
                        chat.isOnline = status != null && status;
                    }
                    chat.color = AppController.getChatColor(chat.id, chat.color);
                }
            }
            adapter.submitList(chatList);
        });
    }

    private void showChatOptionsDialog(Chat chat) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_chat_options, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setView(dialogView).create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        dialogView.findViewById(R.id.colorGreen).setOnClickListener(v ->
                setChatColorDialogBackground(dialogView.findViewById(R.id.dialogOuter), AppController.CHAT_COLOR_GREEN, chat));

        dialogView.findViewById(R.id.colorPink).setOnClickListener(v ->
                setChatColorDialogBackground(dialogView.findViewById(R.id.dialogOuter), AppController.CHAT_COLOR_PINK, chat));

        dialogView.findViewById(R.id.colorYellow).setOnClickListener(v ->
                setChatColorDialogBackground(dialogView.findViewById(R.id.dialogOuter), AppController.CHAT_COLOR_YELLOW, chat));

        dialogView.findViewById(R.id.colorBlue).setOnClickListener(v ->
                setChatColorDialogBackground(dialogView.findViewById(R.id.dialogOuter), AppController.CHAT_COLOR_BLUE, chat));

        setChatColorDialogBackground(dialogView.findViewById(R.id.dialogOuter), AppController.getChatColor(chat.id, chat.color), chat);

        TextInputEditText editText = dialogView.findViewById(R.id.renameEditText);
        editText.setText(chat.name);

        dialogView.findViewById(R.id.btnRename).setOnClickListener(v -> {
            Editable newName = editText.getText();
            String newNameStr = newName == null ? AppController.EMPTY_STRING : newName.toString().trim();
            if (!newNameStr.isEmpty()) {
                controller.getDbHelper().renameChat(chat.id, newNameStr);
                dialog.dismiss();
            }
        });

        dialogView.findViewById(R.id.btnHide).setOnClickListener(v -> {
            controller.getDbHelper().hideChatLocally(chat);
            controller.getNetStreams()[Math.abs((int) (chat.localId % 3))]
                    .execute(() -> controller.getNetworkService().hideChat(chat.id));
            dialog.dismiss();
        });

        dialogView.findViewById(R.id.btnBlock).setOnClickListener(v -> {
            controller.getNetStreams()[Math.abs((int) (chat.localId % 3))]
                    .execute(() -> controller.getNetworkService().blockChat(chat.id));
            dialog.dismiss();
        });

        dialog.show();
    }

    private void setChatColorDialogBackground(View view, int color, Chat chat) {
        AppController.updateChatColor(chat.id, color);
        controller.getDbHelper().setChatColor(chat.id, color);
        view.setBackground(ResourcesCompat.getDrawable(getResources(), AppController.getInterlocutorBackground(color), controller.getTheme()));
    }

    @Override
    public void onStart() {
        super.onStart();
        NotificationHelper.clearNotification(controller);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mBinding = null;
    }
}
