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
import android.view.MotionEvent;
import android.view.View;
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
 * Reads the text other apps show (through accessibility) and draws covers over anything that
 * matches the keywords. Nothing is stored or sent anywhere.
 *
 * Threads:
 *   main         owns covers and trackers, moves covers every display frame, draws.
 *   shield-scan  walks the whole screen's text to find matches (slow: many requests).
 *   shield-poll  re-reads the position of ONE covered item per scrolling list (fast: one
 *                request), so covers follow the content. Two of these, so a slow answer
 *                never holds up the next question.
 * Covers and trackers are only touched on main; other threads hand results back.
 *
 * Following the content while it scrolls:
 *   - Android reports scrolling only every 100 ms, in batches. Too coarse on its own.
 *   - So while a list moves, one covered item's real position is re-read about every frame.
 *   - An invisible 1-pixel window hears the moment a finger touches the screen anywhere
 *     (Android tells it "a touch happened outside you", without where). Re-reads start right
 *     then, so by the time the page starts moving the covers already know.
 *   - TrackMath turns the readings into smooth per-frame movement (predicts slowdown,
 *     blends corrections in instead of snapping).
 */
public class ShieldService extends AccessibilityService {

    // ---- reading the screen
    private static final long THROTTLE_MS = 30;
    private static final long RECHECK_MS = 500;
    private static final int MAX_DEPTH = 150;
    private static final int MAX_CLIMB = 10;
    private static final float ITEM_MAX_HEIGHT = 0.3f;
    private static final int MAX_SHIFTS = 4000;
    private static final long DEEP_BUDGET_MS = 2500;
    private static final int DEEP_MAX_NODES = 8000;
    // Every read is answered on the other app's main thread. While it scrolls, reading
    // back-to-back steals time from its drawing and from our position re-reads.
    private static final long SCAN_GAP_MOVING_MS = 150;
    private static final long REGEX_BUDGET_MS = 20;

    // ---- following the content
    private static final int POLLERS = 2;
    private static final long POLL_MIN_GAP_MS = 8;        // at most ~one re-read per frame
    private static final long HOT_AFTER_TOUCH_MS = 1500;  // finger down: re-read every frame
    private static final long HOT_AFTER_MOVE_MS = 300;
    private static final long POLL_IDLE_MS = 300;         // still, finger watcher works
    private static final long POLL_IDLE_BLIND_MS = 50;    // still, finger watcher never fired
    private static final long POLL_LIVE_MS = 150;
    private static final long STOP_POLL_MS = 90;
    private static final long STOP_EVENT_MS = 220;        // > 2 event batches
    private static final float LEAD_MS = 45f;             // stretch covers by speed x this

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread scanThread;
    private Handler scanHandler;
    private final HandlerThread[] pollThreads = new HandlerThread[POLLERS];
    private final Handler[] pollHandlers = new Handler[POLLERS];
    private int nextPoller;
    private WindowManager wm;
    private OverlayView overlay;
    private View fingerWatcher;
    private SharedPreferences prefs;

    // settings (read on several threads)
    private volatile Pattern pattern;
    private volatile boolean enabled;
    private volatile boolean coverCard;
    private volatile boolean edgeCurtain;
    private volatile boolean debug;
    private volatile boolean trace;
    private volatile Set<String> ignored = new HashSet<>();

    // scan thread state (plus the volatiles)
    private final AtomicBoolean scanPending = new AtomicBoolean();
    private final AtomicLong shiftSeq = new AtomicLong();
    private final Rect scanScreen = new Rect();
    private final Rect tmp = new Rect();
    private long scanStartMs;
    private int scanNodes;
    private int scanErrors;
    private long scanStartSeq;
    private boolean deepAllowed;
    private long lastDeepMs;
    private boolean deepCut;
    private List<Rect> scanDeepAreas;
    private volatile boolean scrolling;
    private volatile boolean forceDeep = true;
    private volatile long lastScanStart;
    private volatile int regexTimeouts;

    // main thread state
    private int incompleteStreak;
    private List<Rect> deepAreas = new ArrayList<>();
    private List<Cover> covers = new ArrayList<>();
    private final List<Shift> shifts = new ArrayList<>();
    private final List<Tracker> trackers = new ArrayList<>();
    private final Rect screenMain = new Rect();
    private boolean armed;
    private boolean framesRunning;
    private String lastPkg = "";
    private int fingerDowns;
    private String dbgScan = "scan: -";
    private String dbgScroll = "scroll: no events yet";
    private String dbgPoll = "poll: -";

    /** One covered spot. */
    private static final class Cover {
        final RectF r;           // where it is drawn now
        final Rect clip;         // the scrolling area it lives in; null = not in a list
        final long seq;          // movement counter when r was measured
        AccessibilityNodeInfo node;   // the matched item (never refreshed itself: copies are)
        float offX, offY;        // the item's own corner relative to r's corner

        Cover(RectF r, Rect clip, long seq) {
            this.r = r;
            this.clip = clip;
            this.seq = seq;
        }
    }

    /** Movement applied to covers, kept so late read results can be brought up to date. */
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

    /** One scrolling area (list, page, carousel). */
    private static final class Tracker {
        final Rect c;
        final TrackMath tx = new TrackMath(), ty = new TrackMath();
        float unX, unY;          // moved since the last complete read (edge curtain)
        long lastEventArr, lastEventSent, lastPollLive, lastMovePoll, lastSeen, hotUntil;
        long lastSend, newestPollAt;
        int lastSX = -1, lastSY = -1;
        int inFlight;
        boolean moving;
        Cover anchor;
        float lastPollX = Float.NaN, lastPollY = Float.NaN;

        Tracker(Rect c) {
            this.c = c;
        }

        boolean busy(long now) {
            return moving || tx.busy(now) || ty.busy(now);
        }

        boolean hot(long now) {
            return moving || now < hotUntil;
        }
    }

    private final Runnable scanRunnable = this::backgroundScan;
    private final Runnable recheckRunnable = () -> scheduleScan(0);
    private final Runnable idlePoll = this::idlePollTick;
    private final Choreographer.FrameCallback frameCallback = this::onVsync;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (p, key) -> {
                loadPrefs();
                if (!enabled) resetTracking();
                scheduleScan(0);
            };

    // =====================================================================
    // Lifecycle
    // =====================================================================

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
        for (int i = 0; i < POLLERS; i++) {
            pollThreads[i] = new HandlerThread("shield-poll-" + i);
            pollThreads[i].start();
            pollHandlers[i] = new Handler(pollThreads[i].getLooper());
        }
        addOverlay();
        addFingerWatcher();
        scheduleScan(0);
    }

    private void loadPrefs() {
        enabled = Prefs.enabled(this);
        coverCard = Prefs.coverCard(this);
        edgeCurtain = Prefs.scrollBlackout(this);
        debug = Prefs.debug(this);
        trace = Prefs.trace(this);
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
        cutout(lp);
        try {
            wm.addView(overlay, lp);
        } catch (RuntimeException e) {
            overlay = null;
        }
    }

    /**
     * An invisible 1x1 window in the top corner that asks to hear about touches outside it.
     * For another app's touch Android says only "a finger went down", once per gesture, with
     * no position: exactly the early warning we need, and nothing more.
     */
    private void addFingerWatcher() {
        if (fingerWatcher != null) return;
        fingerWatcher = new View(this) {
            @Override
            public boolean onTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) onFingerDown();
                return false;
            }
        };
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        cutout(lp);
        try {
            wm.addView(fingerWatcher, lp);
        } catch (RuntimeException e) {
            fingerWatcher = null;
        }
    }

    private static void cutout(WindowManager.LayoutParams lp) {
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
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
        for (HandlerThread t : pollThreads) if (t != null) t.quitSafely();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        for (View v : new View[]{overlay, fingerWatcher}) {
            if (v == null || wm == null) continue;
            try {
                wm.removeView(v);
            } catch (RuntimeException ignored) {
            }
        }
        overlay = null;
        fingerWatcher = null;
        super.onDestroy();
    }

    // =====================================================================
    // Events
    // =====================================================================

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!enabled) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            onScrolled(event);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // New screen: tracking from the old one no longer applies.
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

    private void onFingerDown() {
        if (!enabled) return;
        fingerDowns++;
        if (trace) android.util.Log.i("ShieldTrace", "F " + SystemClock.uptimeMillis());
        long now = SystemClock.uptimeMillis();
        boolean any = false;
        for (Tracker t : trackers) {
            t.hotUntil = now + HOT_AFTER_TOUCH_MS;
            if (pickAnchor(t) != null) any = true;
        }
        if (any) startFrames();   // the frame loop re-reads hot lists every frame
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

        int dx = 0, dy = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            dx = event.getScrollDeltaX();
            dy = event.getScrollDeltaY();
            if (dx == -1) dx = 0;   // -1 = not reported
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
            dbgScroll = "scroll: d=(" + dx + "," + dy + ") " + cls + " h=" + container.height();
        }
        if (dx == 0 && dy == 0) return;

        long now = SystemClock.uptimeMillis();
        long at = event.getEventTime();   // when the app sent it: no delivery jitter
        if (at <= 0 || at > now) at = now;
        if (Math.abs(dy) > container.height() * 3 || Math.abs(dx) > container.width() * 3) {
            t.tx.stop(now);                // a jump ("scroll to top"), not a scroll
            t.ty.stop(now);
            t.moving = false;
            t.lastEventSent = at;
            return;
        }
        // While re-reads are live they are exact and fresher: the event then only means
        // "still moving". Adding its distance as well would count the movement twice.
        if (now - t.lastPollLive > POLL_LIVE_MS) {
            float window = at - t.lastEventSent;
            if (window < 16 || window > 300) window = 100;   // first batch of a gesture
            t.tx.addDelta(-dx, at, window);
            t.ty.addDelta(-dy, at, window);
        }
        t.lastEventSent = at;
        t.lastEventArr = now;
        t.lastSeen = now;
        t.hotUntil = Math.max(t.hotUntil, now + HOT_AFTER_MOVE_MS);
        t.moving = true;
        scrolling = true;
        startFrames();
    }

    // =====================================================================
    // Following the content
    // =====================================================================

    private Tracker trackerFor(Rect container) {
        for (Tracker t : trackers) if (t.c.equals(container)) return t;
        // Same area with edges shifted a little (a toolbar collapsing): keep its history.
        for (Tracker t : trackers) {
            if (overlapShare(t.c, container) >= 0.75f && overlapShare(container, t.c) >= 0.75f) {
                t.c.set(container);
                return t;
            }
        }
        Tracker t = new Tracker(new Rect(container));
        t.lastSeen = SystemClock.uptimeMillis();
        trackers.add(t);
        return t;
    }

    private static boolean movesWith(Rect clip, Rect c) {
        return clip != null && overlapShare(clip, c) >= 0.6f;
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
        shifts.add(new Shift(shiftSeq.incrementAndGet(), t, dx, dy));
        if (shifts.size() > MAX_SHIFTS) shifts.subList(0, MAX_SHIFTS / 4).clear();
    }

    private void startFrames() {
        if (framesRunning) return;
        framesRunning = true;
        main.removeCallbacks(idlePoll);
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    /** Every display frame while anything moves or a finger is down. */
    private void onVsync(long frameNanos) {
        long now = SystemClock.uptimeMillis();
        boolean any = false;
        for (Tracker t : trackers) {
            if (t.moving) {
                boolean pollsStill = now - t.lastPollLive < 100
                        && now - t.lastMovePoll > STOP_POLL_MS;
                boolean eventsStill = now - t.lastEventArr > STOP_EVENT_MS
                        && now - t.lastPollLive > 100;
                if (pollsStill || eventsStill) {
                    t.moving = false;
                    t.tx.stop(now);
                    t.ty.stop(now);
                    scheduleScan(0);   // settled: a fresh read confirms exact positions
                }
            }
            boolean hot = t.hot(now);
            if (hot) requestPoll(t, now);
            if (t.busy(now)) {
                applyMove(t, t.tx.frame(now), t.ty.frame(now));
                any = true;
            } else if (hot && t.anchor != null) {
                any = true;            // finger is down: keep watching
            }
        }
        render();
        if (any && enabled) {
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else {
            framesRunning = false;
            if (scrolling) {
                scrolling = false;
                forceDeep = true;
                scheduleScan(0);       // a scroll just ended: read the whole page
            }
            scheduleIdlePoll();
        }
    }

    /** The covered item to re-read in this list: the one nearest the middle. */
    private Cover pickAnchor(Tracker t) {
        if (t.anchor != null && covers.contains(t.anchor)) return t.anchor;
        Cover best = null;
        float bestD = Float.MAX_VALUE;
        for (Cover c : covers) {
            if (c.node == null || !movesWith(c.clip, t.c)) continue;
            if (c.r.bottom < t.c.top || c.r.top > t.c.bottom) continue;
            float d = Math.abs(c.r.centerY() - t.c.exactCenterY());
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best != t.anchor) {
            t.anchor = best;
            t.lastPollX = t.lastPollY = Float.NaN;
        }
        return best;
    }

    private void requestPoll(Tracker t, long now) {
        if (t.inFlight >= POLLERS || now - t.lastSend < POLL_MIN_GAP_MS) return;
        final Cover a = pickAnchor(t);
        if (a == null) return;
        Handler h = pollHandlers[nextPoller];
        nextPoller = (nextPoller + 1) % POLLERS;
        if (h == null) return;
        t.inFlight++;
        t.lastSend = now;
        final float shownX = t.tx.shown(), shownY = t.ty.shown();
        final float drawnX = a.r.left + a.offX, drawnY = a.r.top + a.offY;
        final Pattern p = pattern;
        final AccessibilityNodeInfo original = a.node;
        h.post(() -> {
            long t0 = SystemClock.uptimeMillis();
            boolean ok = false, still = false;
            Rect b = new Rect();
            try {
                // A private copy, so two re-reads can run at once without sharing state.
                AccessibilityNodeInfo n = Build.VERSION.SDK_INT >= 30
                        ? new AccessibilityNodeInfo(original)
                        : AccessibilityNodeInfo.obtain(original);
                ok = n.refresh();
                if (ok) {
                    n.getBoundsInScreen(b);
                    // RecyclerView reuses rows: if the text no longer matches, this row now
                    // shows something else and its position means nothing.
                    still = n.isVisibleToUser() && p != null
                            && (matches(p, n.getText()) || matches(p, n.getContentDescription()));
                }
            } catch (RuntimeException e) {
                ok = false;
            }
            long t1 = SystemClock.uptimeMillis();
            final boolean fine = ok && still && !b.isEmpty();
            main.post(() -> onPoll(t, a, fine, b, t0, t1, shownX, shownY, drawnX, drawnY));
        });
    }

    private void onPoll(Tracker t, Cover a, boolean ok, Rect b, long t0, long t1,
            float shownX, float shownY, float drawnX, float drawnY) {
        t.inFlight = Math.max(0, t.inFlight - 1);
        if (!trackers.contains(t)) return;
        long now = SystemClock.uptimeMillis();
        if (trace) android.util.Log.i("ShieldTrace", "Q " + now + " " + (t1 - t0) + " " + ok);
        if (debug) {
            dbgPoll = "poll: " + (t1 - t0) + "ms " + (ok ? "ok" : "lost item")
                    + (t.moving ? " moving" : t.hot(now) ? " finger" : " still")
                    + "  finger-downs=" + fingerDowns;
        }
        if (t.anchor != a) return;
        if (!ok) {
            t.anchor = null;
            return;
        }
        // The position is from the app's last drawn frame, a few ms before it answered.
        long at = (t0 + t1) / 2 - 6;
        if (at <= t.newestPollAt) return;     // an older answer that arrived late
        t.newestPollAt = at;
        // Where covers should be = where they were when we asked + how far the item really
        // was from its cover. (Movement between asking and answering cancels out.)
        float truthX = shownX + (b.left - drawnX), truthY = shownY + (b.top - drawnY);
        float jump = Math.abs(truthY - t.ty.target(at)) + Math.abs(truthX - t.tx.target(at));
        float plausible = 200 + (Math.abs(t.ty.velocity()) + Math.abs(t.tx.velocity())) * 120;
        if (jump > plausible) {   // the row was reused for other content: pick another
            t.anchor = null;
            return;
        }
        boolean moved = !Float.isNaN(t.lastPollY)
                && (Math.abs(b.top - t.lastPollY) >= 1 || Math.abs(b.left - t.lastPollX) >= 1);
        t.lastPollX = b.left;
        t.lastPollY = b.top;
        t.lastPollLive = now;
        t.lastSeen = now;
        t.tx.setPosition(truthX, at);
        t.ty.setPosition(truthY, at);
        if (moved) {
            t.lastMovePoll = now;
            t.hotUntil = Math.max(t.hotUntil, now + HOT_AFTER_MOVE_MS);
            if (!t.moving) {
                t.moving = true;      // saw it move before Android's first report
                scrolling = true;
            }
        }
        if (t.busy(now) || t.hot(now)) startFrames();
    }

    /** While still, re-read now and then to notice a scroll that started without a touch. */
    private void scheduleIdlePoll() {
        main.removeCallbacks(idlePoll);
        if (!enabled || framesRunning) return;
        for (Tracker t : trackers) {
            if (pickAnchor(t) != null) {
                main.postDelayed(idlePoll, fingerDowns > 0 ? POLL_IDLE_MS : POLL_IDLE_BLIND_MS);
                return;
            }
        }
    }

    private void idlePollTick() {
        if (framesRunning) return;
        long now = SystemClock.uptimeMillis();
        for (Tracker t : trackers) requestPoll(t, now);
        scheduleIdlePoll();
    }

    // =====================================================================
    // Reading the screen (scan thread)
    // =====================================================================

    private void scheduleScan(long delay) {
        if (scanHandler == null) return;
        if (!scanPending.compareAndSet(false, true)) return;
        if (scrolling) {
            long since = SystemClock.uptimeMillis() - lastScanStart;
            delay = Math.max(delay, SCAN_GAP_MOVING_MS - since);
        }
        scanHandler.postDelayed(scanRunnable, Math.max(0, delay));
    }

    private void backgroundScan() {
        scanPending.set(false);
        final long startSeq = shiftSeq.get();
        scanStartMs = SystemClock.uptimeMillis();
        lastScanStart = scanStartMs;
        scanNodes = 0;
        scanErrors = 0;
        scanStartSeq = startSeq;
        // While still, the app answers fast: read the whole page (also what's scrolled out
        // of view), so covers are ready before it scrolls in. Not more than every 3 s
        // unless a scroll just ended or the screen changed.
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
        if (trace) android.util.Log.i("ShieldTrace", "S " + SystemClock.uptimeMillis() + " " + took
                + " " + nodes + " " + found.size() + " " + errors);
        main.post(() -> {
            dbgScan = "scan: " + took + "ms, " + nodes + " nodes, " + found.size() + " hits, "
                    + errors + " err, " + (deepDone ? "FULL x" + areas.size() : "visible");
            applyScan(found, startSeq, errors, deepDone, areas);
        });
    }

    private static AccessibilityNodeInfo rootOf(AccessibilityWindowInfo w) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // Fetch in big depth-first batches = far fewer round trips to the app.
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

    /** @param container bounds of the nearest scrolling ancestor, or null */
    private void walk(AccessibilityNodeInfo node, Pattern p, List<Cover> out, int depth,
            Rect container) {
        if (node == null || depth > MAX_DEPTH) return;
        scanNodes++;
        try {
            boolean visible = node.isVisibleToUser();
            if (!visible) {
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
                return; // everything inside is covered already
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
            scanErrors++;   // node went stale mid-read: this read is incomplete
        }
    }

    private boolean matches(Pattern p, CharSequence s) {
        if (s == null || s.length() == 0) return false;
        try {
            return p.matcher(new TimedText(s, REGEX_BUDGET_MS)).find();
        } catch (TimedText.TimeUp e) {
            regexTimeouts++;
            return false;
        }
    }

    /**
     * The matching text's bounds. With "cover whole item" on and the text in a list, grow to
     * the item around it (text plus its picture). Text outside lists is covered alone.
     */
    private Cover coverFor(AccessibilityNodeInfo node, Rect container) {
        long seq = scanStartSeq;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return null;
        if (container == null) {
            if (!scanScreen.isEmpty() && !r.intersect(scanScreen)) return null;
            return new Cover(new RectF(r), null, seq);
        }
        final int oldLeft = r.left, oldTop = r.top;
        final long textArea = Math.max(1L, (long) r.width() * r.height());
        if (coverCard && !scanScreen.isEmpty()) {
            int maxH = (int) (scanScreen.height() * ITEM_MAX_HEIGHT);
            AccessibilityNodeInfo cur = node;
            for (int i = 0; i < MAX_CLIMB; i++) {
                AccessibilityNodeInfo parent = cur.getParent();
                if (parent == null || parent.isScrollable()
                        || parent.getCollectionInfo() != null) {
                    break;   // `cur` is a direct item of the list
                }
                parent.getBoundsInScreen(tmp);
                if (tmp.isEmpty() || tmp.height() > maxH) break;
                if ((long) tmp.width() * tmp.height() > 8L * textArea) break;
                r.union(tmp);
                cur = parent;
            }
        }
        // Positions above may be a batch old. While scrolling, re-read this one item and
        // slide the cover by however far it moved since (one request per hit).
        int nodeLeft = oldLeft, nodeTop = oldTop;
        if (scrolling) {
            long now = shiftSeq.get();
            if (node.refresh()) {
                node.getBoundsInScreen(tmp);
                if (!tmp.isEmpty()) {
                    r.offset(tmp.left - oldLeft, tmp.top - oldTop);
                    nodeLeft = tmp.left;
                    nodeTop = tmp.top;
                    seq = now;
                }
            } else {
                scanErrors++;
            }
        }
        Cover c = new Cover(new RectF(r), new Rect(container), seq);
        c.node = node;
        c.offX = nodeLeft - r.left;
        c.offY = nodeTop - r.top;
        return c;
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

    // =====================================================================
    // Results (main thread)
    // =====================================================================

    private void applyScan(List<Cover> found, long startSeq, int errors, boolean deepDone,
            List<Rect> areas) {
        // A read that hit errors may have missed spoilers: never uncover on its word.
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
            // This read only looked at what's on screen: covers placed earlier for content
            // still scrolled out of view stay.
            RectF clipF = new RectF();
            for (Cover old : covers) {
                if (old.clip == null) continue;
                clipF.set(old.clip);
                if (!RectF.intersects(old.r, clipF)) keep.add(old);
            }
        }
        if (deepDone) deepAreas = areas;

        // Bring results measured during movement up to date.
        for (Shift s : shifts) {
            if (s.seq <= startSeq) continue;
            for (Cover c : found) {
                if (s.seq > c.seq && movesWith(c.clip, s.t.c)) c.r.offset(s.dx, s.dy);
            }
        }
        for (Iterator<Shift> it = shifts.iterator(); it.hasNext(); ) {
            if (it.next().seq <= startSeq) it.remove();
        }
        // Whatever moved after this read began is still unread (edge curtain).
        if (!incomplete) {
            for (Tracker t : trackers) t.unX = t.unY = 0;
            for (Shift s : shifts) {
                s.t.unX += s.dx;
                s.t.unY += s.dy;
            }
        }

        // During movement, keep covers that are already being tracked smoothly instead of
        // swapping in copies measured at a slightly different moment (no jitter), unless the
        // read shows one is clearly off.
        boolean anyMoving = false;
        for (Tracker t : trackers) if (t.moving) anyMoving = true;
        if (anyMoving) {
            for (int i = 0; i < found.size(); i++) {
                Cover f = found.get(i);
                if (f.node == null) continue;
                for (Cover old : covers) {
                    if (old.node == null || !old.node.equals(f.node)) continue;
                    if (Math.abs(old.r.top - f.r.top) <= 48 && Math.abs(old.r.left - f.r.left) <= 48
                            && old.r.width() >= f.r.width() * 0.8f) {
                        found.set(i, old);
                    }
                    break;
                }
            }
        }
        found.addAll(keep);
        covers = found;
        // Every list with covers gets a tracker, so its re-reads can start right away.
        for (Cover c : covers) {
            if (c.node != null && c.clip != null && !c.clip.isEmpty()) trackerFor(c.clip);
        }
        if (!found.isEmpty()) armed = true;
        if (!enabled) armed = false;

        long now = SystemClock.uptimeMillis();
        boolean anyActive = false;
        for (Iterator<Tracker> it = trackers.iterator(); it.hasNext(); ) {
            Tracker t = it.next();
            if (t.moving) {
                anyActive = true;
            } else if (now - t.lastSeen > 3000 && pickAnchor(t) == null
                    && t.unX == 0 && t.unY == 0) {
                it.remove();
            }
        }
        readScreenBounds(screenMain);
        render();

        main.removeCallbacks(recheckRunnable);
        if (anyActive || incomplete) {
            scheduleScan(incomplete ? 40 : 0);   // keep reading while it scrolls / until clean
        } else if (!covers.isEmpty()) {
            main.postDelayed(recheckRunnable, RECHECK_MS);
        }
        if (!framesRunning) scheduleIdlePoll();
    }

    private void resetTracking() {
        main.removeCallbacks(idlePoll);
        trackers.clear();
        shifts.clear();
        covers = new ArrayList<>();
        armed = false;
        render();
    }

    /** True if a completed whole-page read covers this scrolling area. */
    private boolean wholePageRead(Rect c) {
        for (Rect a : deepAreas) if (overlapShare(c, a) >= 0.5f) return true;
        return false;
    }

    private void render() {
        if (overlay == null) return;
        List<Rect> drawn = new ArrayList<>(covers.size());
        List<Rect> plain = new ArrayList<>(2);
        if (enabled) {
            long now = SystemClock.uptimeMillis();
            for (Cover c : covers) {
                float top = c.r.top, bottom = c.r.bottom;
                // Stretch towards where the content is heading, by its speed and by how
                // unsure the tracking is right now, so a small error never shows text.
                for (Tracker t : trackers) {
                    if (!t.busy(now) || !movesWith(c.clip, t.c)) continue;
                    float lead = t.ty.velocity() * LEAD_MS;
                    float unsure = Math.abs(t.ty.error(now)) + 6;
                    if (lead < 0) top += lead; else bottom += lead;
                    top -= unsure;
                    bottom += unsure;
                    break;
                }
                Rect r = new Rect(Math.round(c.r.left), Math.round(top),
                        Math.round(c.r.right), Math.round(bottom));
                if (c.clip != null && !r.intersect(c.clip)) continue;
                if (!screenMain.isEmpty() && !r.intersect(screenMain)) continue;
                drawn.add(r);
            }
            if (edgeCurtain && armed) {
                for (Tracker t : trackers) {
                    if (wholePageRead(t.c)) continue;   // everything coming is known already
                    // The strip of content that came into view since the last complete read.
                    float vy = t.ty.velocity();
                    float h = Math.abs(t.unY) + Math.abs(vy) * LEAD_MS;
                    if (h < 8) continue;
                    int hh = (int) Math.min(h, t.c.height());
                    boolean fromBottom = t.unY < 0 || (t.unY == 0 && vy < 0);
                    plain.add(fromBottom
                            ? new Rect(t.c.left, t.c.bottom - hh, t.c.right, t.c.bottom)
                            : new Rect(t.c.left, t.c.top, t.c.right, t.c.top + hh));
                }
            }
        }
        overlay.setFrame(drawn, plain);
        if (trace) {
            StringBuilder sb = new StringBuilder("C ").append(SystemClock.uptimeMillis()).append(' ');
            for (Rect r : drawn) sb.append(r.left).append(',').append(r.top).append(',')
                    .append(r.right).append(',').append(r.bottom).append(';');
            sb.append(" P ");
            for (Rect r : plain) sb.append(r.left).append(',').append(r.top).append(',')
                    .append(r.right).append(',').append(r.bottom).append(';');
            android.util.Log.i("ShieldTrace", sb.toString());
        }
        if (debug && enabled) {
            int moving = 0;
            float v = 0;
            for (Tracker t : trackers) {
                if (t.moving) {
                    moving++;
                    v = t.ty.velocity();
                }
            }
            overlay.setDebug(dbgScan + "\n" + dbgScroll + "\n" + dbgPoll + "\ntracking "
                    + moving + "/" + trackers.size() + " v=" + Math.round(v * 1000)
                    + "px/s armed=" + (armed ? "Y" : "N")
                    + (regexTimeouts > 0 ? " regex-timeouts=" + regexTimeouts : ""));
        } else {
            overlay.setDebug(null);
        }
    }
}
