package dev.mwkov.spoilershield;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Full-screen, click-through layer that paints solid covers over matched spots. */
final class OverlayView extends View {

    private final List<Rect> covers = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] loc = new int[2];
    private final RectF rf = new RectF();
    private final float radius;

    OverlayView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        radius = 12 * d;
        fill.setColor(0xFF17121F);
        border.setColor(0xFF9B6BFF);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * d);
        label.setColor(0xFFB79CFF);
        label.setTextSize(14 * d);
        label.setTextAlign(Paint.Align.CENTER);
        label.setFakeBoldText(true);
    }

    void setCovers(List<Rect> next) {
        if (next.equals(covers)) return;
        covers.clear();
        covers.addAll(next);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (covers.isEmpty()) return;
        getLocationOnScreen(loc);
        for (Rect r : covers) {
            rf.set(r.left - loc[0], r.top - loc[1], r.right - loc[0], r.bottom - loc[1]);
            canvas.drawRoundRect(rf, radius, radius, fill);
            canvas.drawRoundRect(rf, radius, radius, border);
            if (rf.height() > label.getTextSize() * 1.6f && rf.width() > label.getTextSize() * 6) {
                float y = rf.centerY() - (label.descent() + label.ascent()) / 2;
                canvas.drawText("SPOILER SHIELD", rf.centerX(), y, label);
            }
        }
    }
}
