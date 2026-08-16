package com.safelogj.lim;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.safelogj.lim.databinding.ActivityMainBinding;
import com.safelogj.lim.fragments.CallFragment;
import com.safelogj.lim.fragments.ChatFragment;
import com.safelogj.lim.fragments.ChatListFragment;
import com.safelogj.lim.model.Chat;
import com.safelogj.lim.viewmodels.ResultCallback;

public class MainActivity extends AppCompatActivity {

    private AppController controller;
    private ActivityMainBinding mBinding;
    private long incomingInterlocutorId = Chat.INVALID_ID;
    private String incomingChatName;
    private int chatColor;

    private static final String STATE_INTERLOCUTOR_ID = "incoming_id";
    private static final String STATE_CHAT_NAME = "incoming_name";
    private static final String STATE_CHAT_COLOR = "incoming_color";



    public void showFragment(Fragment fragment) {
       getSupportFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(R.id.main_container, fragment)
                .addToBackStack(null)
                .commit();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        mBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> {
            Insets systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets gestureInsets = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures());
            int leftPadding = Math.max(gestureInsets.left, systemInsets.left);
            int rightPadding = Math.max(gestureInsets.right, systemInsets.right);
            int bottomPadding = Math.max(gestureInsets.bottom, systemInsets.bottom);
            int leftPaddingLand = Math.max(leftPadding, systemInsets.top);
            int rightPaddingLand = Math.max(rightPadding, systemInsets.top);

            if (v.getContext().getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                v.setPadding(leftPaddingLand, systemInsets.top, rightPaddingLand, bottomPadding);
            } else {
                v.setPadding(leftPadding, systemInsets.top, rightPadding, bottomPadding);
            }
            return insets;
        });

        controller = (AppController) getApplication();
        if (controller.isInitAppError()) {
            showCriticalErrorAndExit();
            return;
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .setReorderingAllowed(true) // Оптимизация анимаций
                    .add(R.id.main_container, new ChatListFragment())
                    .commit();
            handleIntent(getIntent());
        } else {
            incomingInterlocutorId = savedInstanceState.getLong(STATE_INTERLOCUTOR_ID, Chat.INVALID_ID);
            incomingChatName = savedInstanceState.getString(STATE_CHAT_NAME);
            chatColor = savedInstanceState.getInt(STATE_CHAT_COLOR);
            if (incomingInterlocutorId != Chat.INVALID_ID) {
                showIncomingCallBanner(chatColor);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
        }

        setObserveIncomingCall();
        setDarkStatusBar();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(STATE_INTERLOCUTOR_ID, incomingInterlocutorId);
        outState.putString(STATE_CHAT_NAME, incomingChatName);
        outState.putInt(STATE_CHAT_COLOR, chatColor);
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;

        long chatId = intent.getLongExtra(NotificationHelper.EXTRA_CHAT_ID, Chat.INVALID_ID);
        if (intent.hasExtra(NotificationHelper.EXTRA_OPEN_CHAT_LIST) || chatId != Chat.INVALID_ID) {
            getSupportFragmentManager().popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        if (chatId != Chat.INVALID_ID) {
            showFragment(ChatFragment.newInstance(chatId, intent.getLongExtra(NotificationHelper.EXTRA_CHAT_LOCAL_ID, Chat.INVALID_ID),
                    intent.getStringExtra(NotificationHelper.EXTRA_CHAT_NAME)));
        }
        intent.removeExtra(NotificationHelper.EXTRA_CHAT_ID);
        intent.removeExtra(NotificationHelper.EXTRA_CHAT_LOCAL_ID);
        intent.removeExtra(NotificationHelper.EXTRA_CHAT_NAME);
        intent.removeExtra(NotificationHelper.EXTRA_OPEN_CHAT_LIST);
    }

    private void setDarkStatusBar() {
        WindowInsetsControllerCompat controllerCompat = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controllerCompat.setAppearanceLightStatusBars(false);
        controllerCompat.setAppearanceLightNavigationBars(false);
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.main_background));
    }

    private void showCriticalErrorAndExit() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_critical_error, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView messageView = dialogView.findViewById(R.id.errorMessage);
        String message = getString(R.string.system_critical_error_text) + "\n" + controller.getInitAppErrStr();
        messageView.setText(message);

        dialogView.findViewById(R.id.btnExit).setOnClickListener(v -> {
            finish();
            System.exit(0);
        });
        dialog.show();
    }

    private void setObserveIncomingCall() {
        controller.getIncomingCallTrigger().observe(this, interlocutorId -> {
            if (interlocutorId == Chat.INVALID_ID) {
                hideIncomingCallBanner();
                return;
            }
            // Если ID тот же, что мы уже показываем (после поворота), ничего не делаем
            if (interlocutorId == incomingInterlocutorId) return;

            controller.getDbHelper().getChatName(interlocutorId, new ResultCallback<>() {

                @Override
                public void onError(String errorMsg) {
                    Log.d(AppController.LOG_TAG, errorMsg);
                }

                @Override
                public void onSuccess(Chat chat) {
                    runOnUiThread(() -> {
                        incomingChatName = chat.name;
                        incomingInterlocutorId = interlocutorId;
                        showIncomingCallBanner(chat.color);
                    });
                }
            });
        });
    }

    private void showIncomingCallBanner(int color) {
        controller.getCallService().startRinging();
        mBinding.tvIncomingCallerName.setText(incomingChatName);
        switch (color) {
            case 1 -> mBinding.incomingCallBanner.setBackground(AppCompatResources.getDrawable(this, R.drawable.call_banner_pink));
            case 2 -> mBinding.incomingCallBanner.setBackground(AppCompatResources.getDrawable(this, R.drawable.call_banner_yellow));
            case 3 -> mBinding.incomingCallBanner.setBackground(AppCompatResources.getDrawable(this, R.drawable.call_banner_blue));
            default -> mBinding.incomingCallBanner.setBackground(AppCompatResources.getDrawable(this, R.drawable.call_banner_green));
        }
        mBinding.incomingCallBanner.setVisibility(View.VISIBLE);

        mBinding.btnAcceptCall.setOnClickListener(v -> {
            if (getSupportFragmentManager().findFragmentById(R.id.main_container) instanceof ChatFragment fragment) {
                fragment.stopRecordAndPlay();
            }
            mBinding.incomingCallBanner.setVisibility(View.GONE);
            showFragment(CallFragment.newInstance(incomingInterlocutorId, incomingChatName, false));
        });

        mBinding.btnRejectCall.setOnClickListener(v -> {
            hideIncomingCallBanner();
            controller.getCallService().rejectCall();
        });
    }

    private void hideIncomingCallBanner() {
        incomingInterlocutorId = Chat.INVALID_ID;
        incomingChatName = null;
        mBinding.incomingCallBanner.setVisibility(View.GONE);
    }
}
