package com.safelogj.lim;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
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
import com.safelogj.lim.model.Caller;
import com.safelogj.lim.model.Chat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String STATE_INTERLOCUTOR_ID = "incoming_id";
    private static final String STATE_CHAT_NAME = "incoming_name";
    private static final String STATE_CHAT_COLOR = "incoming_color";

    private AppController controller;
    private ActivityMainBinding mBinding;
    private int incomingInterlocutorId = CallService.INVALID_ID;
    private String incomingChatName;
    private int chatColor;
    private float density;

    private final List<View> teethList = new ArrayList<>();
    private final Handler shakeHandler = new Handler(Looper.getMainLooper());
    private boolean isShaking = false;

    private final Runnable shakeRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isShaking) return;
            for (View tooth : teethList) {
                float dx = (float) (Math.random() * 3 - 1) * density;
                float dy = (float) (Math.random() * 3 - 1) * density;
                tooth.setTranslationX(dx);
                tooth.setTranslationY(dy);
            }
            shakeHandler.postDelayed(this, 110); // Скорость дрожания
        }
    };


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
            incomingInterlocutorId = savedInstanceState.getInt(STATE_INTERLOCUTOR_ID, CallService.INVALID_ID);
            incomingChatName = savedInstanceState.getString(STATE_CHAT_NAME);
            chatColor = savedInstanceState.getInt(STATE_CHAT_COLOR);
            if (incomingInterlocutorId != CallService.INVALID_ID) {
                showIncomingCallBanner(chatColor);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
        setAcceptListener();
        setRejectListener();
        setObserveIncomingCall();
        setDarkStatusBar();
        density = getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onStart() {
        super.onStart();
        NotificationHelper.clearNotification(this, NotificationHelper.CALL_NOTIFICATION_ID);
        if (incomingInterlocutorId != CallService.INVALID_ID && !isShaking) {
            startTeethShake();
        }
    }

    @Override
    protected void onStop() {
        stopTeethShake();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_INTERLOCUTOR_ID, incomingInterlocutorId);
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
        if (intent.hasExtra(NotificationHelper.EXTRA_CALL_CHAT)) {
            NotificationHelper.clearNotification(this, NotificationHelper.CALL_NOTIFICATION_ID);
        }
        if (controller.isLineBusy() && !intent.hasExtra(NotificationHelper.EXTRA_CALL_CHAT)) return; // скипаем уведомления о сообщениях во время звонка или дозвона

        int chatId = intent.getIntExtra(NotificationHelper.EXTRA_CHAT_ID, Chat.INVALID_ID);
        if (intent.hasExtra(NotificationHelper.EXTRA_CALL_CHAT) || intent.hasExtra(NotificationHelper.EXTRA_OPEN_CHAT_LIST) || chatId != Chat.INVALID_ID) {
            getSupportFragmentManager().popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        if (chatId != Chat.INVALID_ID) {
            showFragment(ChatFragment.newInstance(chatId, intent.getIntExtra(NotificationHelper.EXTRA_CHAT_LOCAL_ID, Chat.INVALID_ID),
                    intent.getStringExtra(NotificationHelper.EXTRA_CHAT_NAME)));
        }
        intent.removeExtra(NotificationHelper.EXTRA_CHAT_ID);
        intent.removeExtra(NotificationHelper.EXTRA_CHAT_LOCAL_ID);
        intent.removeExtra(NotificationHelper.EXTRA_CHAT_NAME);
        intent.removeExtra(NotificationHelper.EXTRA_OPEN_CHAT_LIST);
        intent.removeExtra(NotificationHelper.EXTRA_CALL_CHAT);
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

    private void setAcceptListener() {
        mBinding.acceptCall.setOnClickListener(v -> {
            if (getSupportFragmentManager().findFragmentById(R.id.main_container) instanceof ChatFragment fragment) {
                fragment.stopRecordAndPlay();
            }
            showFragment(CallFragment.newInstance(incomingInterlocutorId, incomingChatName, false));
            controller.notifyIncomingCallChanged(CallService.INVALID_ID);
        });
    }

    private void setRejectListener() {
        mBinding.rejectCall.setOnClickListener(v -> {
            controller.notifyIncomingCallChanged(CallService.INVALID_ID);
            CallService service = controller.getCallService();
            if (service != null) {
                service.rejectCall();
            }
        });
    }

    private void setObserveIncomingCall() {
        controller.getIncomingCallTrigger().observe(this, interlocutorId -> {
            if (interlocutorId == CallService.INVALID_ID) {
                hideIncomingCallBanner();
                return;
            }
            // Если ID тот же, что мы уже показываем (после поворота), ничего не делаем
            if (interlocutorId == incomingInterlocutorId) return;

            Caller caller = controller.getDbHelper().getCaller(interlocutorId);
            if (caller != null) {
                incomingChatName = caller.getChatName();
                incomingInterlocutorId = interlocutorId;
                chatColor = caller.getColor();
                showIncomingCallBanner(chatColor);
            } else {
                Log.e(AppController.LOG_TAG, "Error getting chat name from DB");
            }
        });
    }

    private void showIncomingCallBanner(int color) {
        mBinding.tvIncomingCallerName.setText(incomingChatName);
        mBinding.acceptCall.setContentDescription(getString(R.string.accept_call) + " " + incomingChatName);
        mBinding.rejectCall.setContentDescription(getString(R.string.reject_call) + " " + incomingChatName);
        switch (color) {
            case 1 ->
                    mBinding.incomingCallBanner.setBackground(AppCompatResources.getDrawable(this, R.drawable.call_banner_pink));
            case 2 ->
                    mBinding.incomingCallBanner.setBackground(AppCompatResources.getDrawable(this, R.drawable.call_banner_yellow));
            case 3 ->
                    mBinding.incomingCallBanner.setBackground(AppCompatResources.getDrawable(this, R.drawable.call_banner_blue));
            default ->
                    mBinding.incomingCallBanner.setBackground(AppCompatResources.getDrawable(this, R.drawable.call_banner_green));
        }
        mBinding.callBanner.setVisibility(View.VISIBLE);
        startTeethShake();
    }

    private void hideIncomingCallBanner() {
        stopTeethShake();
        incomingInterlocutorId = CallService.INVALID_ID;
        incomingChatName = null;
        mBinding.callBanner.setVisibility(View.GONE);
    }

    private void startTeethShake() {
        if (isShaking) return;
        teethList.clear();
        ViewGroup banner = mBinding.incomingCallBanner;
        for (int i = 0; i < banner.getChildCount(); i++) {
            View child = banner.getChildAt(i);
            // Выбираем только зубы (у них нет ID или они не TextView)
            if (!(child instanceof TextView) && child.getId() == View.NO_ID) {
                teethList.add(child);
            }
        }
        isShaking = true;
        shakeHandler.post(shakeRunnable);
    }

    private void stopTeethShake() {
        isShaking = false;
        shakeHandler.removeCallbacks(shakeRunnable);
        for (View tooth : teethList) {
            tooth.setTranslationX(0);
            tooth.setTranslationY(0);
        }
    }
}
