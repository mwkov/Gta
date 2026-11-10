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

    private final List<Rect> labeled = new ArrayList<>();
    private final List<Rect> plain = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] loc = new int[2];
    private final RectF rf = new RectF();
    private final float radius;
    private final Paint dbgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dbgBg = new Paint();
    private String debugText;
    private final float density;

    OverlayView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        radius = 12 * d;
        density = d;
        dbgPaint.setColor(0xFF7CFFB2);
        dbgPaint.setTextSize(11 * d);
        dbgPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        dbgBg.setColor(0xCC000000);
        fill.setColor(0xFF17121F);
        border.setColor(0xFF9B6BFF);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * d);
        label.setColor(0xFFB79CFF);
        label.setTextSize(14 * d);
        label.setTextAlign(Paint.Align.CENTER);
        label.setFakeBoldText(true);
    }

    /** @param nextLabeled spoiler covers; @param nextPlain edge curtains (no border/label) */
    void setFrame(List<Rect> nextLabeled, List<Rect> nextPlain) {
        if (nextLabeled.equals(labeled) && nextPlain.equals(plain)) return;
        labeled.clear();
        labeled.addAll(nextLabeled);
        plain.clear();
        plain.addAll(nextPlain);
        invalidate();
    }

    void setDebug(String text) {
        if (text == null ? debugText == null : text.equals(debugText)) return;
        debugText = text;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (debugText != null) {
            String[] lines = debugText.split("\n");
            float lh = dbgPaint.getTextSize() * 1.3f;
            float top = 34 * density;
            canvas.drawRect(0, top, getWidth(), top + lh * lines.length + 6 * density, dbgBg);
            for (int i = 0; i < lines.length; i++) {
                canvas.drawText(lines[i], 6 * density, top + lh * (i + 1), dbgPaint);
            }
        }
        if (labeled.isEmpty() && plain.isEmpty()) return;
        getLocationOnScreen(loc);
        for (Rect r : plain) {
            canvas.drawRect(r.left - loc[0], r.top - loc[1], r.right - loc[0], r.bottom - loc[1],
                    fill);
        }
        for (Rect r : labeled) {
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
