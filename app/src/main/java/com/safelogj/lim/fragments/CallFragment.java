package com.safelogj.lim.fragments;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.safelogj.lim.AppController;
import com.safelogj.lim.CallService;
import com.safelogj.lim.R;
import com.safelogj.lim.databinding.FragmentCallBinding;

import java.util.Map;

public class CallFragment extends Fragment {
    private static final String ARG_INTERLOCUTOR_ID = "arg_interlocutor_id";
    private static final String ARG_CHAT_NAME = "arg_chat_name";
    private static final String ARG_IS_OUTGOING = "arg_is_outgoing";


    private final ActivityResultCallback<Boolean> callbackCallPermit = result -> {
        if (Boolean.TRUE == result) {
            call();
        }
    };

    private final ActivityResultLauncher<String> requestCallPermit =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), callbackCallPermit);

    private AppController controller;
    private CallService callService;
    private FragmentCallBinding mBinding;
    private int interlocutorId;
    private String chatName;
    private boolean isOutgoing;
    private Boolean lastOnlineState = null;
    private PowerManager.WakeLock proximityWakeLock;


    public static CallFragment newInstance(int interlocutorId, String chatName, boolean isOutgoing) {
        CallFragment fragment = new CallFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_INTERLOCUTOR_ID, interlocutorId);
        args.putString(ARG_CHAT_NAME, chatName);
        args.putBoolean(ARG_IS_OUTGOING, isOutgoing);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = (AppController) requireActivity().getApplication();
        callService = controller.getCallService();
        if (getArguments() != null) {
            interlocutorId = getArguments().getInt(ARG_INTERLOCUTOR_ID);
            chatName = getArguments().getString(ARG_CHAT_NAME);
            isOutgoing = getArguments().getBoolean(ARG_IS_OUTGOING);
        }

        requireActivity().getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (callService != null) {
                    callService.killCall();
                }
            }
        });
        PowerManager powerManager = (PowerManager) requireActivity().getSystemService(Context.POWER_SERVICE);
        if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Lim:ProximityLock");
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mBinding = FragmentCallBinding.inflate(inflater, container, false);
        return mBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mBinding.tvCallName.setText(chatName);
        setBtnText();
        setSpeakerIcon();
        setBtnListener();
        setSpeakerBtnListener();
        setObserveEndCall();
        setObserveStartCall();
        setObserveOnlineMap();
        setObserveCallDuration();
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestCallPermit.launch(Manifest.permission.RECORD_AUDIO);
        } else {
            call();
        }
    }

    private void setBtnText() {
        if (isOutgoing) {
            if (callService != null) {
                if (callService.isTalking.get()) {
                    mBinding.btnEndCall.setText(R.string.end_call);
                } else {
                    mBinding.btnEndCall.setText(R.string.cancel_call);
                }
            }
        } else {
            mBinding.btnEndCall.setText(R.string.end_call);
        }
    }

    private void setSpeakerIcon() {
        if (callService != null && controller.hasEarpiece() && controller.hasSpeaker()) {
            mBinding.btnSpeaker.setVisibility(View.VISIBLE);
            mBinding.btnSpeaker.setBackground(AppCompatResources.getDrawable(requireContext(),
                    callService.isSpeakerphoneOn() ? R.drawable.speaker_phone_48px : R.drawable.phone_in_talk_48px));
        }
    }

    private void setBtnListener() {
        mBinding.btnEndCall.setOnClickListener(v -> {
            if (callService != null) {
                if (callService.isTalking.get()) {
                    callService.endCall(); // заканчиваем разговор
                } else {
                    callService.cancelCall(); // прекращаем дозваниваться
                }
            }
        });
    }

    private void setSpeakerBtnListener() {
        if (controller.hasEarpiece() && controller.hasSpeaker()) {
            mBinding.btnSpeaker.setOnClickListener(v -> {
                if (callService != null) {
                    boolean isSpeaker = callService.isSpeakerphoneOn();
                    isSpeaker = !isSpeaker;
                    callService.toggleSpeakerphone(isSpeaker);
                    mBinding.btnSpeaker.setBackground(AppCompatResources.getDrawable(requireContext(),
                            isSpeaker ? R.drawable.speaker_phone_48px : R.drawable.phone_in_talk_48px));
                }
            });
        }
    }

    private void call() {
        if (callService != null) {
            if (isOutgoing && !callService.lineBusy.get()) { // исходящий
                Log.d(AppController.LOG_TAG, "исходящий звонок");
                callService.startOutgoingCall(interlocutorId);
            } else if (!isOutgoing && !callService.isTalking.get()) { // входящий
                Log.d(AppController.LOG_TAG, "входящий звонок");
                callService.acceptCall();
            }
        }
    }

    private void setObserveEndCall() {
        controller.getEndCallTrigger().observe(getViewLifecycleOwner(), id -> {
                if (id == CallService.INVALID_ID) {
                    requireActivity().getSupportFragmentManager().popBackStack();
                    controller.notifyEndCallChanged(CallService.LINE_FREE);
                } else if (!id.equals(CallService.LINE_FREE)) {
                    Toast.makeText(requireContext(), "Ошибка доступа к микрофону", Toast.LENGTH_LONG).show();
                }
        });
    }

    private void setObserveStartCall() {
        controller.getStartCallTrigger().observe(getViewLifecycleOwner(), id -> {
            if (callService != null && id == interlocutorId && mBinding != null) {
                mBinding.btnEndCall.setText(R.string.end_call);
            }
        });
    }

    private void setObserveOnlineMap() {
        controller.getOnlineMapTrigger().observe(getViewLifecycleOwner(), onlineMap -> {
            boolean isOnline = false;
            Map<Integer, Boolean> chatStatus = onlineMap.get(interlocutorId);
            if (chatStatus != null) {
                for (Boolean status : chatStatus.values()) {
                    if (Boolean.TRUE.equals(status)) {
                        isOnline = true;
                        break;
                    }
                }
            }

            if (lastOnlineState != null && lastOnlineState.equals(isOnline)) return;
            lastOnlineState = isOnline;
            if (mBinding != null && mBinding.onlineStatus.getBackground() != null) {
                mBinding.onlineStatus.getBackground().mutate().setTint(ContextCompat.getColor(
                        controller, (!isOnline) ? R.color.light_gray_aaa : R.color.last_time));
            }
        });
    }

    private void setObserveCallDuration() {
        controller.getCallDurationTrigger().observe(getViewLifecycleOwner(), duration -> {
            if (duration != null && callService.isTalking.get()) {
                mBinding.tvCallStatus.setText(duration);
            } else {
                mBinding.tvCallStatus.setText(R.string.calling);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (proximityWakeLock != null && !proximityWakeLock.isHeld()) {
            proximityWakeLock.acquire(1_800_000L); // 30мин
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
            proximityWakeLock.release();
        }
    }

    @Override
    public void onDestroyView() {
        lastOnlineState = null;
        super.onDestroyView();
        mBinding = null;
    }
}
