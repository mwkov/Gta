package dev.mwkov.spoilershield;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen, click-through layer that paints the covers. Each cover says what it hides
 * ("jason") and has a "Show" button; the service puts a small tap target over that button.
 */
final class OverlayView extends View {

    private List<Rect> covers = new ArrayList<>();
    private List<String> words = new ArrayList<>();
    private List<Rect> plain = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curtain = new Paint();
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint word = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dbgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dbgBg = new Paint();
    private final Path shield = new Path();
    private final int[] loc = new int[2];
    private final RectF rf = new RectF();
    private final float density;
    private String debugText;
    private int confirming = -1;   // cover whose Show was tapped once

    OverlayView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        fill.setColor(0xFF16121D);
        curtain.setColor(0xFF16121D);
        border.setColor(0xFF6D4BD1);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(1.5f * density);
        icon.setColor(0xFF9B7BFF);
        caption.setColor(0xFF8E83A8);
        caption.setTextSize(10 * density);
        caption.setLetterSpacing(0.12f);
        caption.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        word.setColor(0xFFEDE7FF);
        word.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        btnFill.setColor(0xFF2C2342);
        btnText.setColor(0xFFC9B6FF);
        btnText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        btnText.setTextAlign(Paint.Align.CENTER);
        dbgPaint.setColor(0xFF7CFFB2);
        dbgPaint.setTextSize(11 * density);
        dbgPaint.setTypeface(Typeface.MONOSPACE);
        dbgBg.setColor(0xCC000000);
    }

    /**
     * @param next spoiler covers (screen coordinates)
     * @param nextWords what each one hides (may be null)
     * @param nextPlain edge curtains (plain, no label)
     */
    void setFrame(List<Rect> next, List<String> nextWords, List<Rect> nextPlain) {
        if (next.equals(covers) && nextWords.equals(words) && nextPlain.equals(plain)) return;
        covers = next;
        words = nextWords;
        plain = nextPlain;
        invalidate();
    }

    void setConfirming(int index) {
        if (index == confirming) return;
        confirming = index;
        invalidate();
    }

    void setDebug(String text) {
        if (text == null ? debugText == null : text.equals(debugText)) return;
        debugText = text;
        invalidate();
    }

    /**
     * Where a cover's "Show" button goes, in screen coordinates; empty when the cover is too
     * small for one. Shared with the service, which puts the tap target there.
     */
    static RectF showButton(Rect c, float density) {
        float h = c.height(), w = c.width();
        if (w < 64 * density || h < 16 * density) return new RectF();
        float bh = Math.min(28 * density, h - 6 * density);
        float bw = Math.max(bh * 1.9f, 46 * density);
        float right = c.right - Math.min(10 * density, (h - bh) / 2 + 2 * density);
        float cy = c.top + Math.min(h / 2f, 26 * density);
        return new RectF(right - bw, cy - bh / 2, right, cy + bh / 2);
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
                    curtain);
        }
        for (int i = 0; i < covers.size(); i++) {
            Rect r = covers.get(i);
            drawCover(canvas, r, i < words.size() ? words.get(i) : null, i == confirming);
        }
    }

    private void drawCover(Canvas canvas, Rect r, String what, boolean confirm) {
        float ox = -loc[0], oy = -loc[1];
        rf.set(r.left + ox, r.top + oy, r.right + ox, r.bottom + oy);
        float h = rf.height();
        float radius = Math.min(12 * density, h / 2);
        canvas.drawRoundRect(rf, radius, radius, fill);
        float inset = border.getStrokeWidth() / 2;
        rf.inset(inset, inset);
        canvas.drawRoundRect(rf, radius, radius, border);
        rf.inset(-inset, -inset);

        RectF btn = showButton(r, density);
        float textRight = rf.right - 10 * density;
        if (!btn.isEmpty()) {
            btn.offset(ox, oy);
            float br = btn.height() / 2;
            btnFill.setColor(confirm ? 0xFF6D4BD1 : 0xFF2C2342);
            btnText.setColor(confirm ? 0xFFFFFFFF : 0xFFC9B6FF);
            canvas.drawRoundRect(btn, br, br, btnFill);
            btnText.setTextSize(Math.min(12 * density, btn.height() * 0.48f));
            float ty = btn.centerY() - (btnText.descent() + btnText.ascent()) / 2;
            canvas.drawText(confirm ? "Sure?" : "Show", btn.centerX(), ty, btnText);
            textRight = btn.left - 8 * density;
        }

        // Left side: shield mark, then what it hides.
        float cy = btn.isEmpty() ? rf.centerY() : btn.centerY();
        float left = rf.left + Math.min(12 * density, h / 3);
        float iconH = Math.min(16 * density, h - 8 * density);
        if (iconH >= 8 * density && textRight - left > 30 * density) {
            drawShield(canvas, left, cy, iconH);
            left += iconH * 0.85f + 7 * density;
        }
        if (textRight - left < 24 * density) return;
        boolean twoLines = h >= 50 * density;
        float size = Math.max(9 * density, Math.min(15 * density, h * 0.5f));
        word.setTextSize(size);
        String main = what == null || what.isEmpty() ? "Possible spoiler" : what;
        if (twoLines) {
            float gap = 2 * density;
            float capH = caption.descent() - caption.ascent();
            float wordH = word.descent() - word.ascent();
            float top = cy - (capH + gap + wordH) / 2;
            drawFitted(canvas, "HIDDEN BY SPOILER SHIELD", left, top - caption.ascent(),
                    textRight - left, caption);
            drawFitted(canvas, main, left, top + capH + gap - word.ascent(), textRight - left,
                    word);
        } else {
            float y = cy - (word.descent() + word.ascent()) / 2;
            drawFitted(canvas, main, left, y, textRight - left, word);
        }
    }

    private static void drawFitted(Canvas c, String s, float x, float y, float maxW, Paint p) {
        int n = p.breakText(s, true, maxW, null);
        if (n <= 0) return;
        if (n < s.length()) {
            n = p.breakText(s, true, maxW - p.measureText("…"), null);
            if (n <= 0) return;
            s = s.substring(0, n).trim() + "…";
        }
        c.drawText(s, x, y, p);
    }

    /** A small shield outline, centred vertically on cy. */
    private void drawShield(Canvas c, float x, float cy, float h) {
        float w = h * 0.85f, top = cy - h / 2;
        shield.reset();
        shield.moveTo(x + w / 2, top);
        shield.lineTo(x + w, top + h * 0.18f);
        shield.lineTo(x + w, top + h * 0.5f);
        shield.cubicTo(x + w, top + h * 0.78f, x + w * 0.75f, top + h * 0.92f, x + w / 2, top + h);
        shield.cubicTo(x + w * 0.25f, top + h * 0.92f, x, top + h * 0.78f, x, top + h * 0.5f);
        shield.lineTo(x, top + h * 0.18f);
        shield.close();
        c.drawPath(shield, icon);
    }
}
