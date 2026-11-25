package io.dsh.watch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Small pill switch; the platform one is far too large for this screen. */
final class Toggle extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final float inset;
    private boolean on;
    private int onColor = 0xFF9BD6E8;
    private int offColor = 0xFF2A3444;
    private int knobOn = 0xFFFFFFFF;
    private int knobOff = 0xFF8B98AB;

    Toggle(Context context) {
        super(context);
        inset = 1.5f * context.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    void setColors(int onColor, int offColor, int knobOn, int knobOff) {
        this.onColor = onColor;
        this.offColor = offColor;
        this.knobOn = knobOn;
        this.knobOff = knobOff;
        invalidate();
    }

    void setOn(boolean value) {
        on = value;
        invalidate();
    }

    boolean isOn() {
        return on;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize((int) (44 * getResources().getDisplayMetrics().density)
                        , widthSpec),
                resolveSize((int) (22 * getResources().getDisplayMetrics().density), heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = getHeight();
        float w = getWidth();
        float r = h / 2f;
        track.set(inset, inset, w - inset, h - inset);
        paint.setColor(on ? onColor : offColor);
        canvas.drawRoundRect(track, r, r, paint);

        float knobR = r - inset * 1.6f;
        float cx = on ? (w - inset - knobR - inset) : (inset + knobR + inset);
        paint.setColor(on ? knobOn : knobOff);
        canvas.drawCircle(cx, h / 2f, knobR, paint);
    }
}
