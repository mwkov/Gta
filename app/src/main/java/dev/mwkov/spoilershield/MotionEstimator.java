package dev.mwkov.spoilershield;

/**
 * Works out, from two consecutive screen frames, whether the content scrolled vertically and
 * by exactly how many pixels. Pure Java (no Android classes) so it can be tested on a PC.
 *
 * Each frame is reduced to a small "signature": for every pixel row, the average brightness
 * in BINS column strips. Scrolling shifts that signature up or down; we find the shift that
 * makes the new frame line up with the previous one.
 */
final class MotionEstimator {

    static final int BINS = 12;
    static final int STATIC = 0;     // nothing moved
    static final int RIGID = 1;      // content scrolled by dy
    static final int NONRIGID = 2;   // something changed, but not a scroll (new page, video...)
    static final int BLIND = 3;      // too much of the screen is hidden by our own covers

    final int w, h, stride;
    private final int binW, xStep;
    private short[] prev, cur;       // h * BINS averages, -1 = hidden by a cover
    private boolean havePrev;

    /** Per row: bit b set = strip b changed compared to the previous frame (same position). */
    final int[] changedBins;

    /** After a scroll: rows whose content ALSO changed (e.g. a picture finished loading). */
    final boolean[] residual;

    // Result of the last process() call
    int kind;
    int y0, y1;          // first / last changed row
    int dy;              // content moved down by dy rows (negative = up)
    float dyf;           // same, refined to a fraction of a row
    float cost, cost0;   // mismatch at dy / at 0 (average brightness difference)
    int changedRows, validRows;

    private int lastDy;

    MotionEstimator(int w, int h, int stride) {
        this.w = w;
        this.h = h;
        this.stride = stride;
        this.binW = w / BINS;
        this.xStep = Math.max(4, w / 180);
        prev = new short[h * BINS];
        cur = new short[h * BINS];
        changedBins = new int[h];
        residual = new boolean[h];
    }

    void reset() {
        havePrev = false;
        lastDy = 0;
    }

    /**
     * @param f     RGBA frame, `stride` bytes per row
     * @param masks rectangles (l,t,r,b)* in frame pixels where our own overlay is drawn
     */
    void process(java.nio.ByteBuffer f, int[] masks, int nMasks) {
        signature(f, masks, nMasks, cur);
        dy = 0;
        dyf = 0;
        cost = cost0 = 0;
        if (!havePrev) {
            havePrev = true;
            swap();
            kind = NONRIGID;
            y0 = 0;
            y1 = h - 1;
            changedRows = h;
            validRows = h;
            java.util.Arrays.fill(changedBins, (1 << BINS) - 1);
            return;
        }

        // 1. Which rows changed at all?
        changedRows = 0;
        validRows = 0;
        int first = -1, last = -1;
        for (int y = 0; y < h; y++) {
            int o = y * BINS, d = 0, n = 0, bits = 0;
            for (int b = 0; b < BINS; b++) {
                int a = cur[o + b], p = prev[o + b];
                if (a < 0 || p < 0) continue;
                int ad = a > p ? a - p : p - a;
                d += ad;
                n++;
                if (ad > 6) bits |= 1 << b;
            }
            if (n >= BINS / 3) validRows++;
            boolean ch = n > 0 && (d * 2 > 5 * n || bits != 0);
            changedBins[y] = ch ? (bits != 0 ? bits : (1 << BINS) - 1) : 0;
            if (ch) {
                changedRows++;
                if (first < 0) first = y;
                last = y;
            }
        }
        if (validRows < h / 4) {
            kind = BLIND;
            swap();
            return;
        }
        if (changedRows < 3) {
            kind = STATIC;
            lastDy = 0;
            swap();
            return;
        }
        y0 = first;
        y1 = last;
        int span = y1 - y0 + 1;

        // 2. Does a vertical shift explain the change?
        cost0 = costAt(0, y0, y1, 1, 1);
        int maxS = Math.min(h * 2 / 5, Math.max(8, span * 4 / 5));
        int coarseStep = Math.max(4, h / 400);
        int best = 0;
        float bestC = cost0;

        // 2a. Near the previous frame's shift (scrolling is smooth) - cheap and usually enough.
        int lo = Math.max(-maxS, lastDy - 24), hi = Math.min(maxS, lastDy + 24);
        int lb = 0;
        float lc = Float.MAX_VALUE;
        for (int s = lo; s <= hi; s++) {
            if (s == 0) continue;
            float c = costAt(s, y0, y1, 2, 1);
            if (c < lc) {
                lc = c;
                lb = s;
            }
        }
        if (lb != 0) {
            float c = costAt(lb, y0, y1, 1, 1);
            if (c < bestC) {
                bestC = c;
                best = lb;
            }
        }
        // 2b. Otherwise search everything coarsely, then refine.
        if (!(best != 0 && bestC < 1.0f && bestC < 0.35f * cost0)) {
            int cb = 0;
            float cc = Float.MAX_VALUE;
            for (int s = -maxS; s <= maxS; s++) {
                float c = costAt(s, y0, y1, coarseStep, 2);
                if (c < cc) {
                    cc = c;
                    cb = s;
                }
            }
            for (int s = Math.max(-maxS, cb - 3); s <= Math.min(maxS, cb + 3); s++) {
                float c = costAt(s, y0, y1, 1, 1);
                if (c < bestC) {
                    bestC = c;
                    best = s;
                }
            }
        }

        cost = bestC;
        // A real scroll lines up almost perfectly (the capture is lossless). Be a little more
        // lenient when the shift simply continues the previous frame's scroll.
        boolean clear = bestC < 3.0f && bestC < 0.5f * cost0 && cost0 - bestC > 0.2f;
        boolean continues = lastDy != 0 && Math.abs(best - lastDy) <= 8
                && bestC < 8.0f && bestC < 0.65f * cost0;
        if (best != 0 && (clear || continues)) {
            kind = RIGID;
            dy = best;
            lastDy = best;
            // Sub-row precision: fit a parabola through the mismatch at best-1, best, best+1.
            float cm = costAt(best - 1, y0, y1, 1, 1), cp = costAt(best + 1, y0, y1, 1, 1);
            float off = 0;
            if (cm < Float.MAX_VALUE && cp < Float.MAX_VALUE) {
                float den = cm - 2 * bestC + cp;
                if (den > 1e-4f) off = 0.5f * (cm - cp) / den;
                if (off > 0.5f) off = 0.5f;
                if (off < -0.5f) off = -0.5f;
            }
            dyf = best + off;
            java.util.Arrays.fill(residual, false);
            int from = Math.max(y0, y0 + best), to = Math.min(y1, y1 + best);
            for (int y = from; y <= to; y++) {
                int oc = y * BINS, op = (y - best) * BINS, d = 0, n = 0;
                for (int k = 0; k < BINS; k++) {
                    int c = cur[oc + k], p = prev[op + k];
                    if (c < 0 || p < 0) continue;
                    d += c > p ? c - p : p - c;
                    n++;
                }
                residual[y] = n > 0 && d > 12 * n;
            }
        } else {
            kind = NONRIGID;
            lastDy = 0;
        }
        swap();
    }

    /** Mismatch if content moved down by s rows: cur[y] should equal prev[y - s]. */
    private float costAt(int s, int a, int b, int rowStep, int binStep) {
        int from = Math.max(a, a + s), to = Math.min(b, b + s);
        int need = (b - a + 1) * 3 / 10;
        if (to - from + 1 < Math.max(4, need)) return Float.MAX_VALUE;
        long sum = 0;
        int n = 0;
        for (int y = from; y <= to; y += rowStep) {
            int oc = y * BINS, op = (y - s) * BINS;
            for (int k = 0; k < BINS; k += binStep) {
                int c = cur[oc + k], p = prev[op + k];
                if (c < 0 || p < 0) continue;
                sum += c > p ? c - p : p - c;
                n++;
            }
        }
        int rows = (to - from) / rowStep + 1;
        if (n < rows * (BINS / binStep) / 4) return Float.MAX_VALUE;   // mostly hidden
        return (float) sum / n;
    }

    private void signature(java.nio.ByteBuffer f, int[] masks, int nMasks, short[] out) {
        for (int y = 0; y < h; y++) {
            int row = y * stride, o = y * BINS;
            for (int b = 0; b < BINS; b++) {
                int x0 = b * binW, sum = 0, n = 0;
                for (int x = x0; x < x0 + binW; x += xStep) {
                    sum += f.get(row + x * 4 + 1) & 0xFF;   // green ~ brightness
                    n++;
                }
                out[o + b] = (short) (sum / n);
            }
        }
        for (int m = 0; m < nMasks; m++) {
            int l = masks[m * 4], t = masks[m * 4 + 1], r = masks[m * 4 + 2], bt = masks[m * 4 + 3];
            int b0 = Math.max(0, l / binW), b1 = Math.min(BINS - 1, (r - 1) / binW);
            int ya = Math.max(0, t), yb = Math.min(h, bt);
            for (int y = ya; y < yb; y++) {
                int o = y * BINS;
                for (int b = b0; b <= b1; b++) out[o + b] = -1;
            }
        }
    }

    private void swap() {
        short[] t = prev;
        prev = cur;
        cur = t;
    }
}
