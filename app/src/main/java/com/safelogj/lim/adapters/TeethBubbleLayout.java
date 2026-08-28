package com.safelogj.lim.adapters;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.safelogj.lim.R;

public class TeethBubbleLayout extends FrameLayout {
    private final Paint teethPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path teethPath = new Path();
    private final float density;

    public TeethBubbleLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        teethPaint.setColor(ContextCompat.getColor(context, R.color.main_background));
        teethPaint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    private float dpToPx(float dp) { return dp * density; }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateTeethPath(w, h);
    }

    private void updateTeethPath(int width, int height) {
        teethPath.reset();
        // TOP
        addTopTooth(width * 0.10f, 3, 6);
        addTopTooth(width * 0.15f, 2, 5);
        addTopTooth(width * 0.25f, 2, 4);
        addTopTooth(width * 0.30f, 3, 7);
        addTopTooth(width * 0.40f, 2, 8);
        addTopTooth(width * 0.45f, 3, 4);
        addTopTooth(width * 0.55f, 2, 7);
        addTopTooth(width * 0.60f, 3, 5);
        addTopTooth(width * 0.70f, 2, 6);
        addTopTooth(width * 0.75f, 3, 8);
        addTopTooth(width * 0.85f, 2, 5);
        addTopTooth(width * 0.90f, 3, 4);

        // BOTTOM
        addBottomTooth(width * 0.10f, 2, 4, height);
        addBottomTooth(width * 0.20f, 3, 5, height);
        addBottomTooth(width * 0.25f, 2, 8, height);
        addBottomTooth(width * 0.35f, 3, 4, height);
        addBottomTooth(width * 0.40f, 2, 7, height);
        addBottomTooth(width * 0.45f, 4, 5, height);
        addBottomTooth(width * 0.50f, 3, 8, height);
        addBottomTooth(width * 0.55f, 2, 4, height);
        addBottomTooth(width * 0.65f, 3, 7, height);
        addBottomTooth(width * 0.70f, 2, 5, height);
        addBottomTooth(width * 0.80f, 3, 4, height);
        addBottomTooth(width * 0.85f, 2, 6, height);
        addBottomTooth(width * 0.90f, 4, 3, height);

        // SIDE LEFT
        teethPath.addRect(0, height * 0.25f, dpToPx(6), height * 0.25f + dpToPx(3), Path.Direction.CW);
        teethPath.addRect(0, height * 0.80f, dpToPx(4), height * 0.80f + dpToPx(3), Path.Direction.CW);

        // SIDE RIGHT
        teethPath.addRect(width - dpToPx(5), height * 0.25f - dpToPx(2), width, height * 0.25f, Path.Direction.CW);
        teethPath.addRect(width - dpToPx(3), height * 0.80f - dpToPx(2), width, height * 0.80f, Path.Direction.CW);
    }

    private void addTopTooth(float x, float w, float h) {
        teethPath.addRect(x, 0, x + dpToPx(w), dpToPx(h), Path.Direction.CW);
    }

    private void addBottomTooth(float x, float w, float h, int height) {
        teethPath.addRect(x - dpToPx(w), height - dpToPx(h), x, height, Path.Direction.CW);
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);
        canvas.drawPath(teethPath, teethPaint);
    }
}