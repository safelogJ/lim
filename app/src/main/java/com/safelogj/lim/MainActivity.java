package com.safelogj.lim;

import android.content.res.Configuration;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;

import com.safelogj.lim.databinding.ActivityMainBinding;
import com.safelogj.lim.fragments.ChatListFragment;

public class MainActivity extends AppCompatActivity {

    private AppController controller;

    public void showFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .setReorderingAllowed(true)
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(R.id.main_container, fragment)
                .addToBackStack(null) // Вот это позволяет вернуться назад кнопкой!
                .commit();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        ActivityMainBinding mBinding = ActivityMainBinding.inflate(getLayoutInflater());
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
            return WindowInsetsCompat.CONSUMED;
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
        }
        setDarkStatusBar();
    }

    private void setDarkStatusBar() {
        WindowInsetsControllerCompat controllerCompat = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controllerCompat.setAppearanceLightStatusBars(false);
        controllerCompat.setAppearanceLightNavigationBars(false);
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.main_background));
    }

    private void showCriticalErrorAndExit() {
        new AlertDialog.Builder(this)
                .setTitle("Critical error")
                .setMessage(controller.getInitAppErrStr())
                .setCancelable(false)
                .setPositiveButton("Exit", (dialog, which) -> {
                    finish();
                    System.exit(0);
                }).show();
    }
}