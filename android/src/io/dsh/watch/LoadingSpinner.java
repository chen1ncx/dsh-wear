package io.dsh.watch;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** A thin arc that keeps turning, drawn by hand so it matches the watch palette. */
final class LoadingSpinner extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float stroke;
    private float angle;
    private ValueAnimator animator;

    LoadingSpinner(Context context, int color, float sizeDp, float strokeDp) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        stroke = Math.max(1.2f, strokeDp * density);
        box.set(stroke, stroke, sizeDp * density - stroke, sizeDp * density - stroke);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == View.VISIBLE) start(); else stop();
    }

    void start() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(800);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                angle = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int size = (int) Math.ceil(box.width() + stroke * 2);
        setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.rotate(angle, getWidth() / 2f, getHeight() / 2f);
        canvas.drawArc(box, -90f, 240f, false, paint);
        canvas.restore();
    }
}
