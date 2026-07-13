package com.safelogj.lim.fragments;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.safelogj.lim.AppController;
import com.safelogj.lim.MainActivity;
import com.safelogj.lim.adapters.ChatListAdapter;
import com.safelogj.lim.databinding.FragmentChatListBinding;
import com.safelogj.lim.model.Chat;
import com.safelogj.lim.viewmodels.ChatListViewModel;

import java.util.ArrayList;
import java.util.List;

public class ChatListFragment extends Fragment {

    private AppController controller;
    private FragmentChatListBinding mBinding;
    private ChatListAdapter adapter;
    private final List<Chat> chats = new ArrayList<>();
    private ChatListViewModel viewModel;

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

        viewModel.getDbChatList().observe(getViewLifecycleOwner(), chatList -> {
            chats.clear();
            chats.addAll(chatList);
            adapter.notifyDataSetChanged();
        });

        viewModel.isChatHidden().observe(getViewLifecycleOwner(), isHidden -> {
            if (isHidden) {
                viewModel.loadDbChatList();
            }
        });


        adapter = new ChatListAdapter(chats, new ChatListAdapter.OnChatClickListener() {
            @Override
            public void onChatClick(Chat chat) {
                MainActivity activity = (MainActivity) requireActivity();
                if (chat.id == Chat.INVALID_ID) {
                    if (controller.getUserId() > 0) {
                        activity.showFragment(ChatFragment.newInstance(chat.id));
                    } else {
                        activity.showFragment(new UserFragment());
                    }
                } else {
                    activity.showFragment(ChatFragment.newInstance(chat.id));
                }
            }

            @Override
            public void onAvatarClick(Chat chat) {
                MainActivity activity = (MainActivity) requireActivity();
                if (chat.id == Chat.INVALID_ID) {
                    activity.showFragment(new UserFragment());
                } else {
                    activity.showFragment(ChatFragment.newInstance(chat.id));
                }
            }

            @Override
            public void onChatLongClick(Chat chat) {
                if (chat.id != Chat.INVALID_ID) {
                      showDeleteDialog(chat);
                }
            }
        });
        mBinding.chatsRecyclerView.setAdapter(adapter);
    }


    private void showDeleteDialog(Chat chat) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Удалить чат?")
                .setMessage("Вы хотите скрыть чат с " + chat.name + "?")
                .setPositiveButton("Удалить", (dialog, which) -> viewModel.hideChat(chat.id))
                .setNegativeButton("Отмена", null)
                .show();
    }

    @Override
    public void onResume() {
        super.onResume();
        viewModel.loadDbChatList();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mBinding = null;
    }
}
