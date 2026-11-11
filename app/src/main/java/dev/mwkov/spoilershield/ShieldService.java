package dev.mwkov.spoilershield;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
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

import java.nio.ByteBuffer;
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
    // Scroll tracking. Android sends scroll events at most every 100 ms (batched), so any
    // event-only timing must stay well above that. See TrackMath for the prediction model.
    private static final long POLL_MS = 16;          // re-read the anchor item ~every frame
    private static final long POLL_IDLE_MS = 50;     // ...and every 50 ms while still, to
                                                     //    catch the start of a scroll early
    private static final long POLL_LIVE_MS = 150;    // re-reads count as live this long
    private static final long STOP_POLL_MS = 90;     // re-reads show no movement this long
    private static final long STOP_EVENT_MS = 220;   // no event for > 2 batches = stopped
    private static final float LEAD_EVENT_MS = 45f;  // stretch covers by speed x this
    private static final long SCAN_GAP_MOVING_MS = 150;   // full reads while scrolling: spaced
    private static final long REGEX_BUDGET_MS = 20;       // per text, so a bad pattern can't hang
    private static final int MAX_DEPTH = 150;
    private static final int MAX_CLIMB = 10;
    private static final float ITEM_MAX_HEIGHT = 0.3f;  // a covered "item" is at most 40% of screen
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
    private volatile boolean blur;
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
    private boolean scanSawIgnored;               // scan thread only
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
    private HandlerThread pollThread;
    private Handler pollHandler;
    private volatile long lastScanStart;
    private volatile int regexTimeouts;
    private String dbgPoll = "poll: -";
    private final Runnable idlePoll = this::idlePollTick;

    /** One covered spot. Position is a float so sub-pixel glide adds up correctly. */
    private static final class Cover {
        final RectF r;
        final Rect clip;   // bounds of the scrolling area it lives in, or null if fixed
        final long seq;    // movement counter when this position was read from the app
        boolean ocr;       // found by reading the screen image (not the app's text)
        boolean inRegion;  // ocr cover that lives inside the scrolling part of the screen
        long stickyUntil;  // ocr cover: don't re-check before this time
        Bitmap patch;      // blurred picture of what's underneath (fast mode), or null
        float pl, pt, pr, pb;   // where the picture sits, relative to r's top-left corner
        int avg;           // its average colour
        // The matched item, so its live position can be re-read while the list scrolls.
        // After the cover is published only the poll thread touches it (synchronized).
        AccessibilityNodeInfo node;
        float offX, offY;  // the item's own top-left, relative to r's top-left

        Cover(RectF r, Rect clip, long seq) {
            this.r = r;
            this.clip = clip;
            this.seq = seq;
        }
    }

    /** Movement applied to a scrolling area, kept so late read results can be corrected. */
    private static final class Shift {
        final long seq;
        final Tracker t;          // scroll-event source, or null for screen-image tracking
        final float top, bottom;  // image tracking: the band of the screen that moved
        final float dx, dy;

        Shift(long seq, Tracker t, float dx, float dy) {
            this(seq, t, 0, 0, dx, dy);
        }

        Shift(long seq, Tracker t, float top, float bottom, float dx, float dy) {
            this.seq = seq;
            this.t = t;
            this.top = top;
            this.bottom = bottom;
            this.dx = dx;
            this.dy = dy;
        }

        boolean moves(Cover c) {
            if (t != null) return eventMoves(c, t.c);
            return inBand(c, top, bottom);
        }
    }

    /** One scrolling area (list, page, carousel) and the tracking of its content. */
    private static final class Tracker {
        final Rect c;
        final TrackMath tx = new TrackMath(), ty = new TrackMath();
        float unX, unY;        // content movement not yet covered by a completed read
        long lastEventArr, lastEventSent, lastPollLive, lastMovePoll, lastSeen;
        int lastSX = -1, lastSY = -1;
        boolean moving;        // content is moving (from events or re-reads)
        boolean pollBusy;
        Cover anchor;          // the covered item whose position is re-read
        float lastPollX = Float.NaN, lastPollY = Float.NaN;

        Tracker(Rect c) {
            this.c = c;
        }

        boolean busy(long now) {
            return moving || tx.busy(now) || ty.busy(now);
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
        instance = this;
        wm = getSystemService(WindowManager.class);
        prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        loadPrefs();
        scanThread = new HandlerThread("shield-scan");
        scanThread.start();
        scanHandler = new Handler(scanThread.getLooper());
        // Separate thread so a slow full read never delays the position re-reads.
        pollThread = new HandlerThread("shield-poll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        addOverlay();
        scheduleScan(0);
    }

    private void loadPrefs() {
        enabled = Prefs.enabled(this);
        coverCard = Prefs.coverCard(this);
        edgeCurtain = Prefs.scrollBlackout(this);
        debug = Prefs.debug(this);
        blur = Prefs.blur(this);
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
            scrollArea = null;
            deepAreas = new ArrayList<>();
            forceDeep = true;
            armed = false;
            CharSequence pk = event.getPackageName();
            String pkg = pk == null ? "" : pk.toString();
            if (!pkg.equals(lastPkg)) {
                lastPkg = pkg;
                covers = new ArrayList<>();   // different app: old covers mean nothing
                markAllDirty();
                regTop = regBot = -1;
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

        if (dy != 0 && Math.abs(dy) >= Math.abs(dx) && !screenMain.isEmpty()
                && container.height() > screenMain.height() / 4) {
            scrollArea = new Rect(container);
            if (!scrollArea.intersect(screenMain)) scrollArea = null;
        }
        long now = SystemClock.uptimeMillis();
        // When the app sent it (same clock across apps); removes delivery jitter from speeds.
        long at = event.getEventTime();
        if (at <= 0 || at > now) at = now;
        if (Math.abs(dy) > container.height() * 3 || Math.abs(dx) > container.width() * 3) {
            // A jump (e.g. "scroll to top"), not a scroll: drop tracking, let the read fix it.
            t.tx.stop(now);
            t.ty.stop(now);
            t.moving = false;
            t.lastEventSent = at;
            return;
        }
        // While re-reads are live they are exact and fresher; the event then only says
        // "still moving". Adding its distance too would count the same movement twice.
        if (eventsDrive() && now - t.lastPollLive > POLL_LIVE_MS) {
            float window = at - t.lastEventSent;
            if (window < 16 || window > 300) window = 100;   // first batch of a gesture
            t.tx.addDelta(-dx, at, window);
            t.ty.addDelta(-dy, at, window);
        }
        t.lastEventSent = at;
        t.lastEventArr = now;
        t.lastSeen = now;
        t.moving = true;
        scrolling = true;
        startFrames();
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
        t.lastSeen = SystemClock.uptimeMillis();
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

    /** Image tracking: does a cover ride along when the band top..bottom scrolls? */
    private static boolean inBand(Cover c, float top, float bottom) {
        if (c.ocr) {
            // Once it is part of the scrolling content it stays so, even half off the edge.
            float cy = c.r.centerY();
            return c.inRegion || (cy >= top && cy <= bottom);
        }
        return c.clip != null && c.r.bottom > top && c.r.top < bottom;
    }

    /** Scroll-event tracking: does a cover belong to the scrolling area `area`? */
    private static boolean eventMoves(Cover c, Rect area) {
        if (c.ocr) return area.contains((int) c.r.centerX(), (int) c.r.centerY());
        return movesWith(c.clip, area);
    }

    private void applyMove(Tracker t, float dx, float dy) {
        if (dx == 0 && dy == 0) return;
        if (!eventsDrive()) return;   // the screen image is tracking; it is more exact
        for (Cover c : covers) if (eventMoves(c, t.c)) c.r.offset(dx, dy);
        t.unX += dx;
        t.unY += dy;
        logShift(new Shift(shiftSeq.incrementAndGet(), t, dx, dy));
    }

    // ------------------------------------------------------------------ per-frame tracking

    private void startFrames() {
        if (framesRunning) return;
        framesRunning = true;
        main.removeCallbacks(idlePoll);
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void onFrame(long frameNanos) {
        lastFrameNanos = frameNanos;
        long now = SystemClock.uptimeMillis();
        boolean any = false;
        boolean drive = eventsDrive();
        for (Tracker t : trackers) {
            if (t.moving) {
                boolean pollsStill = now - t.lastPollLive < 100 && now - t.lastMovePoll > STOP_POLL_MS;
                boolean eventsStill = now - t.lastEventArr > STOP_EVENT_MS
                        && now - t.lastPollLive > 100;
                if (pollsStill || eventsStill) {
                    t.moving = false;
                    t.tx.stop(now);
                    t.ty.stop(now);
                    scheduleScan(0);   // settled: a fresh read confirms exact positions
                }
            }
            if (!drive) continue;      // fast mode: the screen image moves the covers
            if (t.moving) requestPoll(t, now, POLL_MS);
            if (!t.busy(now)) continue;
            float mx = t.tx.frame(now), my = t.ty.frame(now);
            applyMove(t, mx, my);
            any = true;
        }
        render();
        if (any && enabled) {
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else {
            framesRunning = false;
            if (scrolling) {
                scrolling = false;
                forceDeep = true;
                scheduleScan(0);   // a scroll just ended: go read the whole page
            }
            scheduleIdlePoll();
        }
    }

    // ------------------------------------------------------------------ position re-reads
    //
    // Scroll events only arrive every 100 ms. Re-reading ONE covered item per list gives
    // its real position about once per frame instead. It is one small request to the app,
    // and only while that list has covers.

    /** The covered item to re-read in this list: the one nearest the middle. */
    private Cover pickAnchor(Tracker t) {
        if (t.anchor != null && covers.contains(t.anchor)) return t.anchor;
        Cover best = null;
        float bestD = Float.MAX_VALUE;
        for (Cover c : covers) {
            if (c.ocr || c.node == null || !eventMoves(c, t.c)) continue;
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

    private void requestPoll(Tracker t, long now, long minGap) {
        if (t.pollBusy || pollHandler == null || !eventsDrive()) return;
        if (now - t.lastPollLive < minGap && t.lastPollLive != 0) return;
        final Cover a = pickAnchor(t);
        if (a == null) return;
        t.pollBusy = true;
        final float shownX = t.tx.shown(), shownY = t.ty.shown();
        final float drawnX = a.r.left + a.offX, drawnY = a.r.top + a.offY;
        final Pattern p = pattern;
        pollHandler.post(() -> {
            long t0 = SystemClock.uptimeMillis();
            boolean ok;
            Rect b = new Rect();
            boolean still = false;
            synchronized (a.node) {
                try {
                    ok = a.node.refresh();
                    if (ok) {
                        a.node.getBoundsInScreen(b);
                        // RecyclerView reuses rows: if the text no longer matches, this row
                        // now shows something else and its position means nothing.
                        still = a.node.isVisibleToUser() && p != null
                                && (matches(p, a.node.getText())
                                || matches(p, a.node.getContentDescription()));
                    }
                } catch (RuntimeException e) {
                    ok = false;
                }
            }
            long t1 = SystemClock.uptimeMillis();
            final boolean fine = ok && still && !b.isEmpty();
            main.post(() -> onPoll(t, a, fine, b, t0, t1, shownX, shownY, drawnX, drawnY));
        });
    }

    private void onPoll(Tracker t, Cover a, boolean ok, Rect b, long t0, long t1,
            float shownX, float shownY, float drawnX, float drawnY) {
        if (!trackers.contains(t)) return;
        t.pollBusy = false;
        long now = SystemClock.uptimeMillis();
        if (debug) dbgPoll = "poll: " + (t1 - t0) + "ms " + (ok ? "ok" : "lost anchor")
                + (t.moving ? " moving" : " still");
        if (t.anchor != a) return;
        if (!ok) {
            t.anchor = null;
            return;
        }
        // Where the covers should be = where they were when we asked, plus how far the
        // item really was from its cover. (Movement between asking and answering cancels.)
        float truthX = shownX + (b.left - drawnX), truthY = shownY + (b.top - drawnY);
        // The position is from the app's last drawn frame, a few ms before it answered.
        long at = (t0 + t1) / 2 - 6;
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
            if (!t.moving) {
                t.moving = true;      // caught the start of a scroll before Android said so
                scrolling = true;
            }
        }
        if (t.busy(now)) startFrames();   // still and settled: nothing to do this time
    }

    /** While still, re-read each list's anchor every 50 ms to notice a scroll starting. */
    private void scheduleIdlePoll() {
        main.removeCallbacks(idlePoll);
        if (!enabled || !eventsDrive()) return;
        for (Tracker t : trackers) {
            if (pickAnchor(t) != null) {
                main.postDelayed(idlePoll, POLL_IDLE_MS);
                return;
            }
        }
    }

    private void idlePollTick() {
        if (framesRunning) return;   // the frame loop does the polling while it runs
        long now = SystemClock.uptimeMillis();
        for (Tracker t : trackers) requestPoll(t, now, POLL_IDLE_MS - 5);
        scheduleIdlePoll();
    }

    private void scheduleScan(long delay) {
        if (scanHandler == null) return;
        if (!scanPending.compareAndSet(false, true)) return;
        // Every read is answered on the other app's main thread. While it scrolls, reading
        // back-to-back steals time from its drawing (and from our position re-reads).
        if (scrolling) {
            long since = SystemClock.uptimeMillis() - lastScanStart;
            delay = Math.max(delay, SCAN_GAP_MOVING_MS - since);
        }
        scanHandler.postDelayed(scanRunnable, Math.max(0, delay));
    }

    // ------------------------------------------------------------------ reading (scan thread)

    private void backgroundScan() {
        scanPending.set(false);
        final long startSeq = shiftSeq.get();
        scanStartMs = SystemClock.uptimeMillis();
        lastScanStart = scanStartMs;
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
        scanSawIgnored = false;
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
        final boolean sawIgnored = scanSawIgnored;
        main.post(() -> {
            ocrBlocked = sawIgnored;
            if (sawIgnored) {
                for (Iterator<Cover> it = covers.iterator(); it.hasNext(); ) {
                    if (it.next().ocr) it.remove();
                }
            }
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
            if (name.equals(getPackageName()) || ignored.contains(name)) {
                scanSawIgnored = true;
                return;
            }
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
        final long textArea = Math.max(1L, (long) r.width() * r.height());
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
                // An "item" is the text plus its picture, not a whole section of the page.
                if ((long) tmp.width() * tmp.height() > 8L * textArea) break;
                r.union(tmp);
                cur = parent;
            }
        }
        // The positions above may be a batch old. While scrolling, re-read just this one node
        // and slide the whole cover by however far it has moved since (one request per hit).
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
        // The item's own corner relative to the cover (the cover may include its picture).
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

    // ============================================================== fast mode (screen image)
    //
    // When screen capture is running we get every frame the display shows. From consecutive
    // frames MotionEstimator measures exactly how far the content scrolled, so covers stay
    // glued to it; and the parts of the frame that are new get read (text recognition) on a
    // background thread, which also sees text inside pictures. Frames are never stored.

    static volatile ShieldService instance;
    private static final float LEAD_MS = 26f;
    private static final int OCR_PAD = 96;        // extra rows around a region, to keep lines whole
    private static final int OCR_MIN_ROWS = 240;

    private Image heldImage;                      // latest frame (kept so it can be read later)
    private MotionEstimator est;
    private float fscale = 1f;                    // screen px per frame px
    private boolean captureOn;
    private int lastKind = MotionEstimator.STATIC;
    private long lastImgTsNs, lastRigidMs;
    private float pxV;                            // content speed, screen px per ms (+ = down)
    private int regTop = -1, regBot = -1;         // frame rows that scroll (remembered)
    private Rect scrollArea;                      // the scrolling list's bounds, from the app
    private boolean[] dirty;                      // frame rows not read yet
    private List<Rect> lastDrawn = new ArrayList<>();
    private int[] maskBuf = new int[4 * 80];
    private boolean ocrBusy, readerTried;
    private long ocrSeq;
    private boolean ocrBlocked;                   // an ignored app (or this one) is on screen
    private HandlerThread ocrThread;
    private Handler ocrHandler;
    private TextReader reader;
    private final List<float[]> recentDrops = new ArrayList<>();   // l,t,r,b,timeMs
    private String dbgPx = "fast mode: off", dbgOcr = "ocr: -";
    private int framesSeen;
    private long fpsWindowStart;
    private int fps;
    private final Runnable pxIdle = () -> {
        if (pxV != 0) {
            pxV = 0;
            render();
        }
    };

    private void logShift(Shift s) {
        shifts.add(s);
        if (shifts.size() > MAX_SHIFTS) shifts.subList(0, MAX_SHIFTS / 4).clear();
    }

    /** Scroll events move the covers only when the screen image can't. */
    private boolean eventsDrive() {
        return !captureOn || lastKind == MotionEstimator.BLIND;
    }

    private boolean ocrBlocked() {
        return ocrBlocked || lastPkg.equals(getPackageName()) || ignored.contains(lastPkg);
    }

    private void markAllDirty() {
        if (dirty != null) java.util.Arrays.fill(dirty, true);
    }

    /** Called by CaptureService on the main thread for every new frame. Takes the image. */
    void onFrame(Image img) {
        if (heldImage != null) heldImage.close();
        heldImage = img;
        if (!enabled) {
            captureOn = false;
            return;
        }
        final int w = img.getWidth(), h = img.getHeight();
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int stride = plane.getRowStride();
        readScreenBounds(screenMain);
        if (screenMain.isEmpty()) return;
        fscale = screenMain.height() / (float) h;
        if (Math.abs(screenMain.width() / (float) w - fscale) > 0.03f) {
            captureOn = false;                    // mid-rotation: wait for a matching frame
            dbgPx = "fast mode: waiting (size mismatch)";
            return;
        }
        if (est == null || est.w != w || est.h != h || est.stride != stride) {
            est = new MotionEstimator(w, h, stride);
            dirty = new boolean[h];
            markAllDirty();
            regTop = regBot = -1;
        }
        if (!captureOn) {
            captureOn = true;
            for (Tracker t : trackers) t.unX = t.unY = 0;
        }

        est.process(buf, maskBuf, buildMasks(w, h));
        long now = SystemClock.uptimeMillis();
        long ts = img.getTimestamp();
        float dtMs = lastImgTsNs == 0 ? 0 : (ts - lastImgTsNs) / 1_000_000f;
        lastImgTsNs = ts;
        lastKind = est.kind;

        if (est.kind == MotionEstimator.RIGID) {
            // Which part of the screen scrolls? The app tells us exactly (the scrolling
            // list's bounds, from its scroll events); until it has, use the rows that changed.
            if (scrollArea != null) {
                regTop = Math.max(0, (int) (scrollArea.top / fscale));
                regBot = Math.min(h - 1, (int) (scrollArea.bottom / fscale) - 1);
            } else if (regTop < 0) {
                regTop = est.y0;
                regBot = est.y1;
            } else {
                regTop = Math.min(regTop, est.y0);
                regBot = Math.max(regBot, est.y1);
            }
            float d = est.dyf * fscale;
            float top = regTop * fscale, bottom = (regBot + 1) * fscale;
            for (Cover c : covers) {
                if (c.ocr && !c.inRegion && c.r.centerY() >= top && c.r.centerY() <= bottom) {
                    c.inRegion = true;
                }
                if (inBand(c, top, bottom)) c.r.offset(0, d);
            }
            logShift(new Shift(shiftSeq.incrementAndGet(), null, top, bottom, 0, d));
            if (dtMs > 2 && dtMs < 120) {
                float v = d / dtMs;
                pxV = pxV == 0 ? v : 0.5f * pxV + 0.5f * v;
            }
            lastRigidMs = now;
            shiftDirty(regTop, regBot, est.dy);
            for (int y = est.y0; y <= est.y1; y++) if (est.residual[y]) dirty[y] = true;
            dropScrolledOut();
        } else if (est.kind == MotionEstimator.NONRIGID) {
            pxV = 0;
            for (int y = 0; y < h; y++) {
                if (est.changedBins[y] == 0) continue;
                int a = Math.max(0, y - 8), b = Math.min(h - 1, y + 8);
                for (int k = a; k <= b; k++) dirty[k] = true;
            }
            if (est.changedRows > h * 6 / 10) {   // a different screen
                regTop = regBot = -1;
                scrollArea = null;
            }
            if (now - lastRigidMs > 150) dropChangedCovers(now);
        } else {
            pxV = 0;
        }

        framesSeen++;
        if (now - fpsWindowStart >= 1000) {
            fps = framesSeen;
            framesSeen = 0;
            fpsWindowStart = now;
        }
        if (debug) {
            String[] k = {"still", "SCROLL", "change", "BLIND"};
            dbgPx = "fast mode: " + k[est.kind] + " dy=" + Math.round(est.dyf * 10) / 10f
                    + " band=" + regTop + "-" + regBot + " v=" + Math.round(pxV * 1000)
                    + "px/s " + fps + "fps";
        }
        kickOcr();
        render();
        main.removeCallbacks(pxIdle);
        main.postDelayed(pxIdle, 70);
    }

    void onCaptureStopped() {
        if (heldImage != null) {
            try {
                heldImage.close();
            } catch (RuntimeException ignored) {
            }
            heldImage = null;
        }
        captureOn = false;
        est = null;
        pxV = 0;
        // Scroll tracking takes over again from where the covers are now.
        for (Tracker t : trackers) {
            t.tx.reset();
            t.ty.reset();
        }
        lastImgTsNs = 0;
        regTop = regBot = -1;
        dbgPx = "fast mode: off";
        // Covers found in the image can't be re-checked without it.
        for (Iterator<Cover> it = covers.iterator(); it.hasNext(); ) if (it.next().ocr) it.remove();
        render();
        scheduleScan(0);
    }

    /** Where our own overlay is drawn, in frame pixels, so the tracker ignores it. */
    private int buildMasks(int w, int h) {
        int n = 0;
        int grow = 6 + (int) (Math.abs(pxV) * 45 / fscale);
        for (Rect r : lastDrawn) {
            if (n >= maskBuf.length / 4) break;
            maskBuf[n * 4] = (int) (r.left / fscale) - 6;
            maskBuf[n * 4 + 1] = (int) (r.top / fscale) - grow;
            maskBuf[n * 4 + 2] = (int) (r.right / fscale) + 6;
            maskBuf[n * 4 + 3] = (int) (r.bottom / fscale) + grow;
            n++;
        }
        if (debug && overlay != null && n < maskBuf.length / 4) {
            maskBuf[n * 4] = 0;
            maskBuf[n * 4 + 1] = (int) (overlay.debugTop() / fscale) - 2;
            maskBuf[n * 4 + 2] = w;
            maskBuf[n * 4 + 3] = (int) (overlay.debugBottom() / fscale) + 2;
            n++;
        }
        return n;
    }

    /** Content in rows a..b moved by dy: the "not read yet" marks move with it. */
    private void shiftDirty(int a, int b, int dy) {
        if (dy == 0 || dirty == null) return;
        if (dy < 0) {
            for (int y = a; y <= b; y++) dirty[y] = y - dy > b || dirty[y - dy];
        } else {
            for (int y = b; y >= a; y--) dirty[y] = y - dy < a || dirty[y - dy];
        }
    }

    private void dropScrolledOut() {
        float top = regTop * fscale, bottom = (regBot + 1) * fscale;
        for (Iterator<Cover> it = covers.iterator(); it.hasNext(); ) {
            Cover c = it.next();
            if (!c.ocr) continue;
            if (c.r.bottom < 0 || c.r.top > screenMain.bottom
                    || (c.inRegion && (c.r.bottom <= top || c.r.top >= bottom))) {
                it.remove();
            }
        }
    }

    /**
     * The screen changed without scrolling. If the area right around an image-found cover
     * changed, what it was hiding is probably gone: remove it and re-read that spot.
     */
    private void dropChangedCovers(long now) {
        int h = est.h, binW = est.w / MotionEstimator.BINS;
        for (Iterator<Cover> it = covers.iterator(); it.hasNext(); ) {
            Cover c = it.next();
            if (!c.ocr || now < c.stickyUntil) continue;
            int t = (int) (c.r.top / fscale), b = (int) (c.r.bottom / fscale);
            int b0 = Math.max(0, (int) (c.r.left / fscale) / binW - 1);
            int b1 = Math.min(MotionEstimator.BINS - 1, (int) (c.r.right / fscale) / binW + 1);
            int mask = 0;
            for (int k = b0; k <= b1; k++) mask |= 1 << k;
            int rows = 0, changed = 0;
            for (int y = Math.max(0, t - 24); y <= Math.min(h - 1, b + 24); y++) {
                if (y >= t - 4 && y <= b + 4) continue;   // under the cover: can't see it
                rows++;
                if ((est.changedBins[y] & mask) != 0) changed++;
            }
            if (rows >= 8 && changed * 10 >= rows * 4) {
                recentDrops.add(new float[]{c.r.left, c.r.top, c.r.right, c.r.bottom, now});
                for (int y = Math.max(0, t - 12); y <= Math.min(h - 1, b + 12); y++) dirty[y] = true;
                it.remove();
            }
        }
        for (Iterator<float[]> it = recentDrops.iterator(); it.hasNext(); ) {
            if (now - (long) it.next()[4] > 2500) it.remove();
        }
    }

    /**
     * Blur style: take the pixels under a cover from the latest frame and shrink them to a
     * few dozen dots; drawn back at full size they are a smooth blur with nothing readable.
     */
    private void grabPatch(Cover c) {
        if (!blur || !captureOn || heldImage == null || est == null || screenMain.isEmpty()) return;
        try {
            RectF v = new RectF(c.r);
            if (!v.intersect(0, 0, screenMain.width(), screenMain.height())) return;
            int w = est.w, h = est.h, stride = est.stride;
            int fl = Math.max(0, (int) (v.left / fscale)), ft = Math.max(0, (int) (v.top / fscale));
            int fr = Math.min(w, (int) Math.ceil(v.right / fscale));
            int fb = Math.min(h, (int) Math.ceil(v.bottom / fscale));
            if (fr - fl < 8 || fb - ft < 8) return;
            // Dot size: ~22px on a 1080-wide screen, coarser for big covers so that large
            // lettering (logos in thumbnails) dissolves too.
            int cell = Math.max(Math.max(10, w / 48), Math.max(fr - fl, fb - ft) / 20);
            int sw = Math.max(2, (fr - fl) / cell), sh = Math.max(2, (fb - ft) / cell);
            float cw = (fr - fl) / (float) sw, ch = (fb - ft) / (float) sh;
            ByteBuffer buf = heldImage.getPlanes()[0].getBuffer();
            int[] rr = new int[sw * sh], gg = new int[sw * sh], bb = new int[sw * sh];
            for (int j = 0; j < sh; j++) {
                for (int i = 0; i < sw; i++) {
                    int r = 0, g = 0, b = 0;
                    for (int k = 0; k < 4; k++) {     // 4 samples per dot
                        int x = Math.min(w - 1, fl + (int) ((i + (k % 2 == 0 ? 0.25f : 0.75f)) * cw));
                        int y = Math.min(h - 1, ft + (int) ((j + (k < 2 ? 0.25f : 0.75f)) * ch));
                        int o = y * stride + x * 4;
                        r += buf.get(o) & 0xFF;
                        g += buf.get(o + 1) & 0xFF;
                        b += buf.get(o + 2) & 0xFF;
                    }
                    rr[j * sw + i] = r / 4;
                    gg[j * sw + i] = g / 4;
                    bb[j * sw + i] = b / 4;
                }
            }
            // Soften: each dot becomes the average of itself and its neighbours.
            int[] out = new int[sw * sh];
            long ar = 0, ag = 0, ab = 0;
            for (int j = 0; j < sh; j++) {
                for (int i = 0; i < sw; i++) {
                    int r = 0, g = 0, b = 0, n = 0;
                    for (int dj = -1; dj <= 1; dj++) {
                        for (int di = -1; di <= 1; di++) {
                            int x = i + di, y = j + dj;
                            if (x < 0 || y < 0 || x >= sw || y >= sh) continue;
                            r += rr[y * sw + x];
                            g += gg[y * sw + x];
                            b += bb[y * sw + x];
                            n++;
                        }
                    }
                    r /= n;
                    g /= n;
                    b /= n;
                    ar += r;
                    ag += g;
                    ab += b;
                    out[j * sw + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
            int n = sw * sh;
            c.avg = 0xFF000000 | ((int) (ar / n) << 16) | ((int) (ag / n) << 8) | (int) (ab / n);
            c.patch = Bitmap.createBitmap(out, sw, sh, Bitmap.Config.ARGB_8888);
            c.pl = v.left - c.r.left;
            c.pt = v.top - c.r.top;
            c.pr = v.right - c.r.left;
            c.pb = v.bottom - c.r.top;
        } catch (RuntimeException ignored) {
            // Frame went away mid-read: this cover stays a plain box.
        }
    }

    /** Start reading the not-yet-read part of the latest frame, if the reader is free. */
    private void kickOcr() {
        if (ocrBusy || heldImage == null || dirty == null || est == null) return;
        if (reader == null) {
            if (readerTried) return;
            readerTried = true;
            try {
                reader = (TextReader) Class.forName("dev.mwkov.spoilershield.MlKitReader")
                        .getDeclaredConstructor().newInstance();
                ocrThread = new HandlerThread("shield-ocr");
                ocrThread.start();
                ocrHandler = new Handler(ocrThread.getLooper());
            } catch (Throwable e) {
                dbgOcr = "ocr: unavailable (" + e.getClass().getSimpleName() + ")";
                return;
            }
        }
        final Pattern p = pattern;
        if (p == null) return;
        final int w = est.w, h = est.h, stride = est.stride;
        if (ocrBlocked()) {
            java.util.Arrays.fill(dirty, false);
            return;
        }
        // Not-yet-read rows come in runs (new strip at an edge, a picture that loaded...).
        // Read the biggest run now; the others follow on the next rounds.
        int a = -1, b = -1, runA = -1, last = -1;
        for (int y = 0; y <= h; y++) {
            boolean d = y < h && dirty[y];
            if (d) {
                if (runA < 0) runA = y;
                last = y;
            }
            if (runA >= 0 && (y == h || (!d && y - last > 2 * OCR_PAD))) {
                if (a < 0 || last - runA > b - a) {
                    a = runA;
                    b = last;
                }
                runA = -1;
            }
        }
        if (a < 0) return;
        final int dirtyA = a, dirtyB = b;
        a = Math.max(0, a - OCR_PAD);
        b = Math.min(h - 1, b + OCR_PAD);
        if (b - a + 1 < OCR_MIN_ROWS) {
            int need = OCR_MIN_ROWS - (b - a + 1);
            a = Math.max(0, a - need / 2);
            b = Math.min(h - 1, a + OCR_MIN_ROWS - 1);
            a = Math.max(0, b - OCR_MIN_ROWS + 1);
        }
        final int rows = b - a + 1, top = a;
        final byte[] px = new byte[rows * stride];
        try {
            ByteBuffer src = heldImage.getPlanes()[0].getBuffer().duplicate();
            src.position(Math.min(src.limit(), a * stride));
            src.get(px, 0, Math.min(px.length, src.remaining()));
        } catch (RuntimeException e) {
            return;   // image went away; the next frame will retry
        }
        for (int y = dirtyA; y <= dirtyB; y++) dirty[y] = false;
        final long seq = shiftSeq.get();
        final long t0 = SystemClock.uptimeMillis();
        ocrBusy = true;
        ocrSeq = seq;
        ocrHandler.post(() -> {
            final List<RectF> hits = new ArrayList<>();
            int blocks = -1;
            try {
                blocks = readText(px, w, rows, stride, top, p, hits);
            } catch (Throwable ignored) {
            }
            final int nBlocks = blocks;
            main.post(() -> onOcr(hits, seq, nBlocks, top, top + rows - 1,
                    SystemClock.uptimeMillis() - t0));
        });
    }

    /** OCR thread. @return number of text blocks seen; hit boxes are in frame pixels. */
    private int readText(byte[] px, int w, int rows, int stride, int top, Pattern p,
            List<RectF> hits) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(stride / 4, rows, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(px));
        // Text recognition gets slower with size and doesn't need full resolution.
        float ds = w > 900 ? 720f / w : 1f;
        Bitmap use = bmp;
        if (ds < 1f || stride / 4 != w) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(ds, ds);
            use = Bitmap.createBitmap(bmp, 0, 0, w, rows, m, true);
            bmp.recycle();
        }
        List<TextReader.Block> blocks = reader.read(use);
        use.recycle();
        for (TextReader.Block b : blocks) {
            if (b.text == null || !p.matcher(b.text).find()) continue;
            hits.add(new RectF(b.box.left / ds, b.box.top / ds + top,
                    b.box.right / ds, b.box.bottom / ds + top));
        }
        return blocks.size();
    }

    private void onOcr(List<RectF> hits, long seq, int blocks, int rowA, int rowB, long took) {
        ocrBusy = false;
        if (debug) {
            dbgOcr = "ocr: " + took + "ms rows " + rowA + "-" + rowB + ", "
                    + (blocks < 0 ? "FAILED" : blocks + " blocks") + ", " + hits.size() + " hits";
        }
        if (!captureOn) return;
        if (ocrBlocked()) hits.clear();
        long now = SystemClock.uptimeMillis();
        float pad = 14;
        float regT = regTop * fscale, regB = (regBot + 1) * fscale;
        for (RectF f : hits) {
            RectF r = new RectF(f.left * fscale - pad, f.top * fscale - pad,
                    f.right * fscale + pad, f.bottom * fscale + pad);
            Cover c = new Cover(r, null, seq);
            c.ocr = true;
            // Bring it from "where it was in that frame" to where that content is now.
            for (Shift s : shifts) if (s.seq > seq && s.moves(c)) c.r.offset(s.dx, s.dy);
            c.inRegion = regTop >= 0 && c.r.centerY() >= regT && c.r.centerY() <= regB;

            boolean merged = false;
            for (Cover old : covers) {
                if (!RectF.intersects(old.r, c.r)) continue;
                if (old.ocr) {
                    if (!old.r.contains(c.r)) {
                        old.r.union(c.r);
                        grabPatch(old);
                    }
                    merged = true;
                    break;
                }
                // Already inside a cover placed from the app's own text?
                float iw = Math.min(old.r.right, c.r.right) - Math.max(old.r.left, c.r.left);
                float ih = Math.min(old.r.bottom, c.r.bottom) - Math.max(old.r.top, c.r.top);
                if (iw * ih >= 0.7f * c.r.width() * c.r.height()) {
                    merged = true;
                    break;
                }
            }
            if (merged) continue;
            for (float[] d : recentDrops) {
                if (c.r.intersects(d[0], d[1], d[2], d[3])) {
                    c.stickyUntil = now + 15000;   // it came straight back: stop re-checking
                    break;
                }
            }
            grabPatch(c);
            covers.add(c);
            armed = true;
        }
        kickOcr();   // more may have scrolled in meanwhile
        render();
    }

    // ------------------------------------------------------------------ results (main thread)

    private void applyScan(List<Cover> found, long startSeq, int errors, boolean deepDone,
            List<Rect> areas) {
        // A read that hit errors may have missed spoilers. Don't uncover anything on its
        // word: keep existing covers it didn't re-find, keep the curtain, and read again.
        boolean incomplete = errors > 0 && incompleteStreak < 6;
        incompleteStreak = errors > 0 ? incompleteStreak + 1 : 0;
        List<Cover> keep = new ArrayList<>();
        for (Cover old : covers) if (old.ocr) keep.add(old);   // not this reader's to remove
        if (incomplete) {
            for (Cover old : covers) {
                if (old.ocr) continue;
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
                if (old.ocr || old.clip == null) continue;
                clipF.set(old.clip);
                if (!RectF.intersects(old.r, clipF)) keep.add(old);
            }
        }
        if (deepDone) deepAreas = areas;
        // Content may have moved while we were reading: bring the results up to date.
        for (Shift s : shifts) {
            if (s.seq <= startSeq) continue;
            for (Cover c : found) {
                if (s.seq > c.seq && s.moves(c)) c.r.offset(s.dx, s.dy);
            }
        }
        // Forget movement nobody needs any more (a text read in flight still needs its part).
        long pruneTo = Math.min(startSeq, ocrBusy ? ocrSeq : Long.MAX_VALUE);
        for (Iterator<Shift> it = shifts.iterator(); it.hasNext(); ) {
            if (it.next().seq <= pruneTo) it.remove();
        }
        // Whatever moved after this read began is still unverified; everything before is read.
        if (!incomplete) {
            for (Tracker t : trackers) t.unX = t.unY = 0;
            for (Shift s : shifts) {
                if (s.t == null || s.seq <= startSeq) continue;
                s.t.unX += s.dx;
                s.t.unY += s.dy;
            }
        }

        if (blur && captureOn) {
            for (Cover f : found) {
                Cover from = null;
                for (Cover old : covers) {
                    if (old.ocr || old.patch == null || !RectF.intersects(old.r, f.r)) continue;
                    float iw = Math.min(old.r.right, f.r.right) - Math.max(old.r.left, f.r.left);
                    float ih = Math.min(old.r.bottom, f.r.bottom) - Math.max(old.r.top, f.r.top);
                    if (iw * ih >= 0.6f * f.r.width() * f.r.height()) {
                        from = old;
                        break;
                    }
                }
                if (from != null) {
                    // Same spot as before: keep its picture (re-grabbing would only capture
                    // our own blur).
                    f.patch = from.patch;
                    f.avg = from.avg;
                    float ox = from.r.left - f.r.left, oy = from.r.top - f.r.top;
                    f.pl = from.pl + ox;
                    f.pr = from.pr + ox;
                    f.pt = from.pt + oy;
                    f.pb = from.pb + oy;
                } else {
                    grabPatch(f);
                }
            }
        }
        // A read during a scroll measured positions at a slightly different moment than the
        // tracked covers. For items already covered, keep the tracked cover (no jitter) unless
        // the read shows it is clearly off.
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
        found.addAll(keep);   // already at current positions
        covers = found;
        // Every list that has covers gets a tracker, so its re-reads can start right away.
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
            scheduleScan(incomplete ? 40 : 0);     // keep reading while scrolling / until clean
        } else if (!covers.isEmpty()) {
            main.postDelayed(recheckRunnable, RECHECK_MS);
        }
        if (!framesRunning) scheduleIdlePoll();
    }

    private void resetTracking() {
        main.removeCallbacks(idlePoll);
        markAllDirty();
        trackers.clear();
        shifts.clear();
        covers = new ArrayList<>();
        armed = false;
        render();
    }

    private void render() {
        if (overlay == null) return;
        List<Rect> labeled = new ArrayList<>(covers.size());
        List<OverlayView.Item> items = new ArrayList<>(covers.size());
        List<Rect> plain = new ArrayList<>(2);
        if (enabled) {
            boolean px = !eventsDrive();
            float regT = regTop * fscale, regB = (regBot + 1) * fscale;
            // The overlay reaches the display a couple of frames after the content moved:
            // stretch covers a little in the direction of travel so nothing peeks out.
            float lead = px ? pxV * LEAD_MS : 0;
            long nowMs = SystemClock.uptimeMillis();
            for (Cover c : covers) {
                float top = c.r.top, bottom = c.r.bottom;
                boolean moving = px && regTop >= 0 && inBand(c, regT, regB);
                if (moving && lead != 0) {
                    if (lead < 0) top += lead; else bottom += lead;
                    top -= 4;
                    bottom += 4;
                }
                if (!px) {
                    // Normal mode: stretch towards where the content is heading, by its speed
                    // and by how unsure the tracking currently is, so nothing peeks out.
                    for (Tracker t : trackers) {
                        if (!t.busy(nowMs) || !eventMoves(c, t.c)) continue;
                        float v = t.ty.velocity() * LEAD_EVENT_MS;
                        float unsure = Math.abs(t.ty.error(nowMs)) + 6;
                        if (v < 0) top += v; else bottom += v;
                        top -= unsure;
                        bottom += unsure;
                        break;
                    }
                }
                Rect r = new Rect(Math.round(c.r.left), Math.round(top),
                        Math.round(c.r.right), Math.round(bottom));
                if (c.clip != null && !r.intersect(c.clip)) continue;
                if (c.ocr && c.inRegion && regTop >= 0 && regB - regT >= 300
                        && !r.intersect(0, Math.round(regT), 1 << 20, Math.round(regB))) continue;
                if (!screenMain.isEmpty() && !r.intersect(screenMain)) continue;
                labeled.add(r);
                boolean useBlur = blur && px && c.patch != null;
                items.add(new OverlayView.Item(r, useBlur ? c.patch : null,
                        useBlur ? new RectF(c.r.left + c.pl, c.r.top + c.pt,
                                c.r.left + c.pr, c.r.top + c.pb) : null, c.avg));
            }
            lastDrawn = labeled;
            if (edgeCurtain && armed && !px) {
                for (Tracker t : trackers) {
                    if (wholePageRead(t.c)) continue;   // everything coming is already known
                    // Strip of content that entered since the last completed read (+ a little
                    // lead for the frames it takes this overlay to reach the display).
                    float vy = t.ty.velocity();
                    float h = Math.abs(t.unY) + Math.abs(vy) * LEAD_EVENT_MS;
                    if (h < 8) continue;
                    int hh = (int) Math.min(h, t.c.height());
                    boolean fromBottom = t.unY < 0 || (t.unY == 0 && vy < 0);
                    plain.add(fromBottom
                            ? new Rect(t.c.left, t.c.bottom - hh, t.c.right, t.c.bottom)
                            : new Rect(t.c.left, t.c.top, t.c.right, t.c.top + hh));
                }
            }
        }
        overlay.setFrame(items, plain);
        if (debug && enabled) {
            int active = 0;
            float v = 0;
            for (Tracker t : trackers) {
                if (t.moving) {
                    active++;
                    v = t.ty.velocity();
                }
            }
            overlay.setDebug(dbgScan + "\n" + dbgScroll + "\ntracking " + active + "/"
                    + trackers.size() + " v=" + Math.round(v * 1000) + "px/s armed="
                    + (armed ? "Y" : "N") + (regexTimeouts > 0 ? " regex-timeouts=" + regexTimeouts : "")
                    + "\n" + dbgPoll + "\n" + dbgPx + "\n" + dbgOcr);
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
        instance = null;
        onCaptureStopped();
        if (ocrThread != null) ocrThread.quitSafely();
        if (reader != null) reader.close();
        main.removeCallbacksAndMessages(null);
        if (framesRunning) Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (scanThread != null) scanThread.quitSafely();
        if (pollThread != null) pollThread.quitSafely();
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
