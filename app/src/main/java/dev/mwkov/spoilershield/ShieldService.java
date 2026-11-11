package dev.mwkov.spoilershield;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Reads the text on screen and draws opaque covers over anything matching the user's regex.
 * Nothing is stored or sent anywhere.
 *
 * v2 design — detect rarely, track every frame (like motion tracking in a video editor):
 *  1. DETECT (background thread, ~5-10x/s): read the screen's text, including content that is
 *     scrolled just off-screen where the app exposes it, so covers exist before it scrolls in.
 *  2. TRACK (every display frame): scroll events give distance travelled; from them we keep a
 *     velocity per scrolling area and glide the covers at that speed between events, then
 *     correct to the true position whenever a new event or a new read arrives.
 *  3. EDGE CURTAIN: content that has scrolled into view since the last completed read is
 *     unverified. Exactly that strip, at the edge it enters from, stays covered until read.
 */
public class ShieldService extends AccessibilityService {

    private static final long THROTTLE_MS = 30;      // min gap between reads while things change
    private static final long RECHECK_MS = 500;      // re-verify while covers are showing
    private static final long STOP_MS = 120;         // no scroll event for this long = stopped
    private static final int MAX_DEPTH = 150;
    private static final int MAX_CLIMB = 10;
    private static final float ITEM_MAX_HEIGHT = 0.4f;  // a covered "item" is at most 40% of screen
    private static final int MAX_SHIFTS = 4000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread scanThread;
    private Handler scanHandler;
    private WindowManager wm;
    private OverlayView overlay;
    private SharedPreferences prefs;

    // Settings (read on both threads)
    private volatile Pattern pattern;
    private volatile boolean enabled;
    private volatile boolean coverCard;
    private volatile boolean edgeCurtain;
    private volatile boolean debug;
    private volatile Set<String> ignored = new HashSet<>();

    // Background read state
    private final AtomicBoolean scanPending = new AtomicBoolean();
    private final AtomicLong shiftSeq = new AtomicLong();
    private final Rect scanScreen = new Rect();   // scan thread only
    private final Rect tmp = new Rect();          // scan thread only
    private long scanStartMs;                     // scan thread only
    private int scanNodes;                        // scan thread only
    private int scanErrors;                       // scan thread only: parts we failed to read
    private long scanStartSeq;                    // scan thread only
    private int incompleteStreak;                 // main thread
    private static final long DEEP_BUDGET_MS = 2500;   // whole-page read, only while idle
    private static final int DEEP_MAX_NODES = 8000;
    private volatile boolean scrolling;           // set on main, read by the scan thread
    private boolean deepAllowed;                  // scan thread only
    private volatile boolean forceDeep = true;
    private long lastDeepMs;                      // scan thread only
    private boolean deepCut;                      // scan thread only: whole-page read cut short
    private List<Rect> scanDeepAreas;             // scan thread only: areas exposing off-screen
    private List<Rect> deepAreas = new ArrayList<>();   // main: areas whose whole page is read

    // Debug numbers (main thread)
    private String dbgScan = "scan: -";
    private String dbgScroll = "scroll: no events yet";
    private String lastPkg = "";

    // Main-thread state
    private List<Cover> covers = new ArrayList<>();
    private final List<Shift> shifts = new ArrayList<>();
    private final List<Tracker> trackers = new ArrayList<>();
    private final Rect screenMain = new Rect();
    private boolean armed;          // a spoiler was seen on this screen -> curtain allowed
    private boolean framesRunning;
    private long lastFrameNanos;

    /** One covered spot. Position is a float so sub-pixel glide adds up correctly. */
    private static final class Cover {
        final RectF r;
        final Rect clip;   // bounds of the scrolling area it lives in, or null if fixed
        final long seq;    // movement counter when this position was read from the app

        Cover(RectF r, Rect clip, long seq) {
            this.r = r;
            this.clip = clip;
            this.seq = seq;
        }
    }

    /** Movement applied to a scrolling area, kept so late read results can be corrected. */
    private static final class Shift {
        final long seq;
        final Tracker t;
        final float dx, dy;

        Shift(long seq, Tracker t, float dx, float dy) {
            this.seq = seq;
            this.t = t;
            this.dx = dx;
            this.dy = dy;
        }
    }

    /** Per scrolling area: velocity estimate and how far content moved since the last read. */
    private static final class Tracker {
        final Rect c;
        float vx, vy;          // content velocity, px per ms (negative y = moving up)
        float predX, predY;    // glide applied since the last real scroll event
        float unX, unY;        // content movement not yet covered by a completed read
        long lastEvent;
        int lastSX = -1, lastSY = -1;
        boolean active;

        Tracker(Rect c) {
            this.c = c;
        }
    }

    private final Runnable scanRunnable = this::backgroundScan;
    private final Runnable recheckRunnable = () -> scheduleScan(0);
    private final Choreographer.FrameCallback frameCallback = this::onFrame;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (p, key) -> {
                loadPrefs();
                if (!enabled) resetTracking();
                scheduleScan(0);
            };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = getSystemService(WindowManager.class);
        prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        loadPrefs();
        scanThread = new HandlerThread("shield-scan");
        scanThread.start();
        scanHandler = new Handler(scanThread.getLooper());
        addOverlay();
        scheduleScan(0);
    }

    private void loadPrefs() {
        enabled = Prefs.enabled(this);
        coverCard = Prefs.coverCard(this);
        edgeCurtain = Prefs.scrollBlackout(this);
        debug = Prefs.debug(this);
        pattern = Prefs.compile(Prefs.pattern(this));
        ignored = Prefs.ignoredApps(this);
    }

    private void addOverlay() {
        if (overlay != null) return;
        overlay = new OverlayView(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            wm.addView(overlay, lp);
        } catch (RuntimeException e) {
            overlay = null;
        }
    }

    // ------------------------------------------------------------------ events (main thread)

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!enabled) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            onScrolled(event);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // New screen / app: tracking state from the old one no longer applies.
            trackers.clear();
            shifts.clear();
            deepAreas = new ArrayList<>();
            forceDeep = true;
            armed = false;
            CharSequence pk = event.getPackageName();
            String pkg = pk == null ? "" : pk.toString();
            if (!pkg.equals(lastPkg)) {
                lastPkg = pkg;
                covers = new ArrayList<>();   // different app: old covers mean nothing
            }
            render();
        }
        scheduleScan(THROTTLE_MS);
    }

    private void onScrolled(AccessibilityEvent event) {
        AccessibilityNodeInfo src;
        try {
            src = event.getSource();
        } catch (RuntimeException e) {
            src = null;
        }
        if (src == null) return;
        Rect container = new Rect();
        src.getBoundsInScreen(container);
        if (container.isEmpty()) return;
        Tracker t = trackerFor(container);

        // Distance scrolled since the previous event: use reported deltas, else the change
        // in absolute scroll position (web pages and some apps only report that).
        int dx = 0, dy = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            dx = event.getScrollDeltaX();
            dy = event.getScrollDeltaY();
            if (dx == -1) dx = 0;
            if (dy == -1) dy = 0;
        }
        int sx = event.getScrollX(), sy = event.getScrollY();
        if (dx == 0 && dy == 0 && t.lastSY != -1 && sy != -1) {
            dx = sx - t.lastSX;
            dy = sy - t.lastSY;
        }
        t.lastSX = sx;
        t.lastSY = sy;
        if (debug) {
            CharSequence cn = src.getClassName();
            String cls = cn == null ? "?" : cn.toString();
            cls = cls.substring(cls.lastIndexOf('.') + 1);
            dbgScroll = "scroll: d=(" + dx + "," + dy + ") pos=(" + sx + "," + sy + ") "
                    + cls + " h=" + container.height();
        }
        if (dx == 0 && dy == 0) return;

        long now = SystemClock.uptimeMillis();
        if (Math.abs(dy) > container.height() * 3 || Math.abs(dx) > container.width() * 3) {
            // A jump (e.g. "scroll to top"), not a scroll: drop tracking, let the read fix it.
            t.active = false;
            t.vx = t.vy = t.predX = t.predY = 0;
            t.lastEvent = now;
            return;
        }

        float dt = now - t.lastEvent;
        if (dt < 16 || dt > 300) dt = 100;   // first event of a gesture: assume the usual gap
        float mx = -dx, my = -dy;            // content moves opposite to the scroll offset

        // We already glided by pred; apply only the difference to land on the true position.
        applyMove(t, mx - t.predX, my - t.predY);
        t.predX = t.predY = 0;

        float nvx = mx / dt, nvy = my / dt;
        if (t.active) {
            t.vx = 0.5f * t.vx + 0.5f * nvx;
            t.vy = 0.5f * t.vy + 0.5f * nvy;
        } else {
            t.vx = nvx;
            t.vy = nvy;
        }
        t.lastEvent = now;
        t.active = true;
        scrolling = true;
        startFrames();
        render();
    }

    private Tracker trackerFor(Rect container) {
        for (Tracker t : trackers) if (t.c.equals(container)) return t;
        // Same scrolling area whose edges shifted a little (toolbars collapsing): keep its
        // velocity and history instead of starting a second tracker next to it.
        for (Tracker t : trackers) {
            if (overlapShare(t.c, container) >= 0.75f && overlapShare(container, t.c) >= 0.75f) {
                t.c.set(container);
                return t;
            }
        }
        Tracker t = new Tracker(new Rect(container));
        trackers.add(t);
        return t;
    }

    /** True if a cover living in scroll area `clip` moves when area `c` scrolls. */
    private static boolean movesWith(Rect clip, Rect c) {
        if (clip == null) return false;
        return overlapShare(clip, c) >= 0.6f;
    }

    /** How much of `a` lies inside `b`, 0..1. */
    private static float overlapShare(Rect a, Rect b) {
        int w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        int h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (w <= 0 || h <= 0) return 0;
        long area = (long) a.width() * a.height();
        return area <= 0 ? 0 : (float) ((double) w * h / area);
    }

    private void applyMove(Tracker t, float dx, float dy) {
        if (dx == 0 && dy == 0) return;
        for (Cover c : covers) if (movesWith(c.clip, t.c)) c.r.offset(dx, dy);
        t.unX += dx;
        t.unY += dy;
        if (shifts.size() < MAX_SHIFTS) {
            shifts.add(new Shift(shiftSeq.incrementAndGet(), t, dx, dy));
        }
    }

    // ------------------------------------------------------------------ per-frame tracking

    private void startFrames() {
        if (framesRunning) return;
        framesRunning = true;
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void onFrame(long frameNanos) {
        float dt = lastFrameNanos == 0 ? 0 : (frameNanos - lastFrameNanos) / 1_000_000f;
        if (dt > 34) dt = 34;
        lastFrameNanos = frameNanos;
        long now = SystemClock.uptimeMillis();
        boolean any = false;
        for (Tracker t : trackers) {
            if (!t.active) continue;
            if (now - t.lastEvent > STOP_MS) {
                // Scrolling stopped: freeze, and get a fresh read to settle exact positions.
                t.active = false;
                t.vx = t.vy = 0;
                scheduleScan(0);
                continue;
            }
            float sx = t.vx * dt, sy = t.vy * dt;
            t.predX += sx;
            t.predY += sy;
            applyMove(t, sx, sy);
            any = true;
        }
        render();
        if (any && enabled) {
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else {
            framesRunning = false;
            scrolling = false;
            forceDeep = true;
            scheduleScan(0);   // idle again: go read the whole page
        }
    }

    private void scheduleScan(long delay) {
        if (scanHandler == null) return;
        if (!scanPending.compareAndSet(false, true)) return;
        scanHandler.postDelayed(scanRunnable, delay);
    }

    // ------------------------------------------------------------------ reading (scan thread)

    private void backgroundScan() {
        scanPending.set(false);
        final long startSeq = shiftSeq.get();
        scanStartMs = SystemClock.uptimeMillis();
        scanNodes = 0;
        scanErrors = 0;
        scanStartSeq = startSeq;
        // While idle the app answers fast: read the whole page. Not more than every few
        // seconds though, unless scrolling just stopped or the screen changed.
        deepAllowed = !scrolling && (forceDeep || scanStartMs - lastDeepMs > 3000);
        if (deepAllowed) {
            forceDeep = false;
            lastDeepMs = scanStartMs;
        }
        deepCut = false;
        scanDeepAreas = new ArrayList<>();
        final List<Cover> found = new ArrayList<>();
        Pattern p = pattern;
        if (enabled && p != null) {
            readScreenBounds(scanScreen);
            List<AccessibilityWindowInfo> windows;
            try {
                windows = getWindows();
            } catch (RuntimeException e) {
                windows = null;
            }
            if (windows != null && !windows.isEmpty()) {
                for (AccessibilityWindowInfo w : windows) {
                    int type = w.getType();
                    if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                            || type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                        continue;
                    }
                    scanRoot(rootOf(w), p, found);
                }
            } else {
                try {
                    scanRoot(getRootInActiveWindow(), p, found);
                } catch (RuntimeException ignored) {
                }
            }
        }
        final long took = SystemClock.uptimeMillis() - scanStartMs;
        final int nodes = scanNodes;
        final int errors = scanErrors;
        final boolean deepDone = deepAllowed && !deepCut && errors == 0;
        final List<Rect> areas = scanDeepAreas;
        main.post(() -> {
            dbgScan = "scan: " + took + "ms, " + nodes + " nodes, " + found.size() + " hits, "
                    + errors + " err, " + (deepDone ? "FULL x" + areas.size() : "visible");
            applyScan(found, startSeq, errors, deepDone, areas);
        });
    }

    private static AccessibilityNodeInfo rootOf(AccessibilityWindowInfo w) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // Fetch the tree in big depth-first batches = far fewer round trips to the app.
                return w.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST);
            }
            return w.getRoot();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void scanRoot(AccessibilityNodeInfo root, Pattern p, List<Cover> out) {
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        if (pkg != null) {
            String name = pkg.toString();
            // Never cover our own settings screen, so the keyword list stays editable.
            if (name.equals(getPackageName()) || ignored.contains(name)) return;
        }
        walk(root, p, out, 0, null);
    }

    /**
     * @param container bounds of the nearest scrolling ancestor, or null
     */
    private void walk(AccessibilityNodeInfo node, Pattern p, List<Cover> out, int depth,
            Rect container) {
        if (node == null || depth > MAX_DEPTH) return;
        scanNodes++;
        try {
            boolean visible = node.isVisibleToUser();
            if (!visible) {
                // Content scrolled off the top/bottom of its scrolling area (web pages expose
                // the whole page). Read it only while idle, when the app answers quickly;
                // its covers are then ready long before it scrolls into view.
                if (container == null) return;
                if (!deepAllowed || scrolling || scanNodes > DEEP_MAX_NODES
                        || SystemClock.uptimeMillis() - scanStartMs > DEEP_BUDGET_MS) {
                    deepCut = true;
                    return;
                }
                node.getBoundsInScreen(tmp);
                if (tmp.isEmpty()) return;
                if (Rect.intersects(tmp, container)) return;          // hidden, not scrolled off
                if (tmp.right <= container.left || tmp.left >= container.right) return;
                boolean known = false;
                for (Rect a : scanDeepAreas) {
                    if (a.equals(container)) {
                        known = true;
                        break;
                    }
                }
                if (!known) scanDeepAreas.add(new Rect(container));
            }

            if (!node.isEditable() && (matches(p, node.getText())
                    || matches(p, node.getContentDescription())
                    || (Build.VERSION.SDK_INT >= 30 && matches(p, node.getStateDescription())))) {
                Cover c = coverFor(node, container);
                if (c != null) out.add(c);
                return; // everything inside is already covered
            }

            Rect childContainer = container;
            if (visible && node.isScrollable()) {
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                if (!b.isEmpty()) childContainer = b;
            }

            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo child = Build.VERSION.SDK_INT >= 33
                        ? node.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST)
                        : node.getChild(i);
                walk(child, p, out, depth + 1, childContainer);
            }
        } catch (RuntimeException ignored) {
            // Node went stale mid-read (screen changed): this read is incomplete.
            scanErrors++;
        }
    }

    private static boolean matches(Pattern p, CharSequence s) {
        return s != null && s.length() > 0 && p.matcher(s).find();
    }

    /**
     * The matching text's bounds. With "cover whole item" on and the text inside a scrolling
     * area, grow to the enclosing item (video card, post, message, search result) so the
     * thumbnail goes too. Text outside scrolling areas (toolbars, headers) is covered alone.
     */
    private Cover coverFor(AccessibilityNodeInfo node, Rect container) {
        // Positions we walked past may be a batch old. Re-read this one now and remember the
        // movement counter, so later only movement AFTER this instant is added on top.
        long seq = scanStartSeq;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return null;
        if (container == null) {
            if (!scanScreen.isEmpty() && !r.intersect(scanScreen)) return null;
            return new Cover(new RectF(r), null, seq);
        }
        final int oldLeft = r.left, oldTop = r.top;
        if (coverCard && !scanScreen.isEmpty()) {
            int maxH = (int) (scanScreen.height() * ITEM_MAX_HEIGHT);
            AccessibilityNodeInfo cur = node;
            for (int i = 0; i < MAX_CLIMB; i++) {
                AccessibilityNodeInfo parent = cur.getParent();
                if (parent == null || parent.isScrollable()
                        || parent.getCollectionInfo() != null) {
                    break;   // `cur` is a direct item of the list / scrolling area
                }
                parent.getBoundsInScreen(tmp);
                if (tmp.isEmpty() || tmp.height() > maxH) break;
                r.union(tmp);
                cur = parent;
            }
        }
        // The positions above may be a batch old. While scrolling, re-read just this one node
        // and slide the whole cover by however far it has moved since (one request per hit).
        if (scrolling) {
            long now = shiftSeq.get();
            if (node.refresh()) {
                node.getBoundsInScreen(tmp);
                if (!tmp.isEmpty()) {
                    r.offset(tmp.left - oldLeft, tmp.top - oldTop);
                    seq = now;
                }
            } else {
                scanErrors++;
            }
        }
        return new Cover(new RectF(r), new Rect(container), seq);
    }

    private void readScreenBounds(Rect out) {
        if (wm == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            out.set(wm.getMaximumWindowMetrics().getBounds());
        } else {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            out.set(0, 0, dm.widthPixels, dm.heightPixels);
        }
    }

    // ------------------------------------------------------------------ results (main thread)

    private void applyScan(List<Cover> found, long startSeq, int errors, boolean deepDone,
            List<Rect> areas) {
        // A read that hit errors may have missed spoilers. Don't uncover anything on its
        // word: keep existing covers it didn't re-find, keep the curtain, and read again.
        boolean incomplete = errors > 0 && incompleteStreak < 6;
        incompleteStreak = errors > 0 ? incompleteStreak + 1 : 0;
        List<Cover> keep = new ArrayList<>();
        if (incomplete) {
            for (Cover old : covers) {
                boolean refound = false;
                for (Cover c : found) {
                    if (RectF.intersects(old.r, c.r)) {
                        refound = true;
                        break;
                    }
                }
                if (!refound) keep.add(old);
            }
        } else if (!deepDone) {
            // This read only looked at what's on screen. Covers placed earlier for content
            // that is still scrolled out of view stay where they are.
            RectF clipF = new RectF();
            for (Cover old : covers) {
                if (old.clip == null) continue;
                clipF.set(old.clip);
                if (!RectF.intersects(old.r, clipF)) keep.add(old);
            }
        }
        if (deepDone) deepAreas = areas;
        // Content may have moved while we were reading: bring the results up to date.
        for (Shift s : shifts) {
            if (s.seq <= startSeq) continue;
            for (Cover c : found) {
                if (s.seq > c.seq && movesWith(c.clip, s.t.c)) c.r.offset(s.dx, s.dy);
            }
        }
        for (Iterator<Shift> it = shifts.iterator(); it.hasNext(); ) {
            if (it.next().seq <= startSeq) it.remove();
        }
        // Whatever moved after this read began is still unverified; everything before is read.
        if (!incomplete) {
            for (Tracker t : trackers) t.unX = t.unY = 0;
            for (Shift s : shifts) {
                s.t.unX += s.dx;
                s.t.unY += s.dy;
            }
        }

        found.addAll(keep);   // already at current positions
        covers = found;
        if (!found.isEmpty()) armed = true;
        if (!enabled) armed = false;

        long now = SystemClock.uptimeMillis();
        boolean anyActive = false;
        for (Iterator<Tracker> it = trackers.iterator(); it.hasNext(); ) {
            Tracker t = it.next();
            if (t.active) {
                anyActive = true;
            } else if (now - t.lastEvent > 3000 && t.unX == 0 && t.unY == 0) {
                it.remove();
            }
        }
        readScreenBounds(screenMain);
        render();

        main.removeCallbacks(recheckRunnable);
        if (anyActive || incomplete) {
            scheduleScan(incomplete ? 40 : 0);     // keep reading while scrolling / until clean
        } else if (!covers.isEmpty()) {
            main.postDelayed(recheckRunnable, RECHECK_MS);
        }
    }

    private void resetTracking() {
        trackers.clear();
        shifts.clear();
        covers = new ArrayList<>();
        armed = false;
        render();
    }

    private void render() {
        if (overlay == null) return;
        List<Rect> labeled = new ArrayList<>(covers.size());
        List<Rect> plain = new ArrayList<>(2);
        if (enabled) {
            for (Cover c : covers) {
                Rect r = new Rect(Math.round(c.r.left), Math.round(c.r.top),
                        Math.round(c.r.right), Math.round(c.r.bottom));
                if (c.clip != null && !r.intersect(c.clip)) continue;
                if (!screenMain.isEmpty() && !r.intersect(screenMain)) continue;
                labeled.add(r);
            }
            if (edgeCurtain && armed) {
                for (Tracker t : trackers) {
                    if (wholePageRead(t.c)) continue;   // everything coming is already known
                    // Strip of content that entered since the last completed read (+ a little
                    // lead for the frames it takes this overlay to reach the display).
                    float h = Math.abs(t.unY) + Math.abs(t.vy) * 30f;
                    if (h < 8) continue;
                    int hh = (int) Math.min(h, t.c.height());
                    boolean fromBottom = t.unY < 0 || (t.unY == 0 && t.vy < 0);
                    plain.add(fromBottom
                            ? new Rect(t.c.left, t.c.bottom - hh, t.c.right, t.c.bottom)
                            : new Rect(t.c.left, t.c.top, t.c.right, t.c.top + hh));
                }
            }
        }
        overlay.setFrame(labeled, plain);
        if (debug && enabled) {
            int active = 0;
            float v = 0;
            for (Tracker t : trackers) {
                if (t.active) {
                    active++;
                    v = t.vy;
                }
            }
            overlay.setDebug(dbgScan + "\n" + dbgScroll + "\ntracking " + active + "/"
                    + trackers.size() + " v=" + Math.round(v * 1000) + "px/s armed="
                    + (armed ? "Y" : "N"));
        } else {
            overlay.setDebug(null);
        }
    }

    /** True if a completed whole-page read covers this scrolling area. */
    private boolean wholePageRead(Rect c) {
        for (Rect a : deepAreas) {
            if (overlapShare(c, a) >= 0.5f) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        resetTracking();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (framesRunning) Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (scanThread != null) scanThread.quitSafely();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        if (overlay != null && wm != null) {
            try {
                wm.removeView(overlay);
            } catch (RuntimeException ignored) {
            }
        }
        overlay = null;
        super.onDestroy();
    }
}
