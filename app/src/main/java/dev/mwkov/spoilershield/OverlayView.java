package dev.mwkov.spoilershield;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Full-screen, click-through layer that paints the covers. */
final class OverlayView extends View {

    private List<Rect> covers = new ArrayList<>();
    private List<Rect> plain = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dbgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dbgBg = new Paint();
    private final int[] loc = new int[2];
    private final RectF rf = new RectF();
    private final float radius, density;
    private String debugText;

    OverlayView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        radius = 12 * density;
        fill.setColor(0xFF17121F);
        border.setColor(0xFF9B6BFF);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * density);
        label.setColor(0xFFB79CFF);
        label.setTextSize(14 * density);
        label.setTextAlign(Paint.Align.CENTER);
        label.setFakeBoldText(true);
        dbgPaint.setColor(0xFF7CFFB2);
        dbgPaint.setTextSize(11 * density);
        dbgPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        dbgBg.setColor(0xCC000000);
    }

    /** @param next spoiler covers; @param nextPlain edge curtains (no border or label) */
    void setFrame(List<Rect> next, List<Rect> nextPlain) {
        if (next.equals(covers) && nextPlain.equals(plain)) return;
        covers = next;
        plain = nextPlain;
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
        if (covers.isEmpty() && plain.isEmpty()) return;
        getLocationOnScreen(loc);
        for (Rect r : plain) {
            canvas.drawRect(r.left - loc[0], r.top - loc[1], r.right - loc[0], r.bottom - loc[1],
                    fill);
        }
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
