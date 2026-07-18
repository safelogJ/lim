package com.safelogj.lim.fragments;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.os.Handler;
import android.os.Looper;
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
import com.safelogj.lim.viewmodels.ChatListViewModel;

public class ChatListFragment extends Fragment {

    private AppController controller;
    private FragmentChatListBinding mBinding;
    private ChatListAdapter adapter;
    private ChatListViewModel viewModel;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiRunnable = new Runnable() {
        @Override
        public void run() {
            viewModel.loadDbChatList();
            uiHandler.postDelayed(this, 4000);
        }
    };

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
        viewModel = new ViewModelProvider(this).get(ChatListViewModel.class);
        setObservers();
        adapter = new ChatListAdapter(new ChatListAdapter.OnChatClickListener() {
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
    }

    private void setObservers() {
        viewModel.getChatList().observe(getViewLifecycleOwner(), chatList -> adapter.submitList(chatList));

        viewModel.isChatHidden().observe(getViewLifecycleOwner(), isHidden -> {
            if ( isHidden != null && isHidden) {
                viewModel.loadDbChatList();
            }
        });

        viewModel.isChatBlocked().observe(getViewLifecycleOwner(), isBlocked -> {
            if (isBlocked != null && isBlocked) {
                viewModel.loadDbChatList();
            }
        });

        viewModel.getChatName().observe(getViewLifecycleOwner(), name -> {
            if (name != null) {
                viewModel.loadDbChatList();
            }
        });
    }

    private void showChatOptionsDialog(Chat chat) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_chat_options, null);
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextInputEditText editText = dialogView.findViewById(R.id.renameEditText);
        editText.setText(chat.name);

        dialogView.findViewById(R.id.btnRename).setOnClickListener(v -> {
            Editable newName = editText.getText();
            String newNameStr = newName == null ? AppController.EMPTY_STRING : newName.toString().trim();
            if (!newNameStr.isEmpty()) {
                viewModel.renameChat(chat, newNameStr);
                dialog.dismiss();
            }
        });

        dialogView.findViewById(R.id.btnHide).setOnClickListener(v -> {
            viewModel.hideChat(chat);
            dialog.dismiss();
        });

        dialogView.findViewById(R.id.btnBlock).setOnClickListener(v -> {
            viewModel.setChatBlockedState(chat);
            dialog.dismiss();
        });

        dialog.show();
    }

    @Override
    public void onStart() {
        super.onStart();
        uiHandler.post(uiRunnable);
        NotificationHelper.clearNotification(controller);
    }

    @Override
    public void onStop() {
        super.onStop();
        uiHandler.removeCallbacks(uiRunnable);
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mBinding = null;
    }

}
