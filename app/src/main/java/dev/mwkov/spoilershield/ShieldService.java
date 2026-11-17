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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Reads the text other apps show (through accessibility) and draws covers over anything that
 * matches the keywords. Nothing is stored or sent anywhere.
 *
 * Threads:
 *   main         owns covers and trackers, moves covers every display frame, draws.
 *   shield-event asks which list a scroll event came from (a request to the app).
 *   shield-scan  decides what to read next; reads just the moving list itself.
 *   shield-read  read one window each, in parallel (a slow app can't hold up the rest).
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
    private static final long READ_BUDGET_MS = 2000;      // one window, what's on screen
    private static final int READERS = 3;
    private static final int MAX_CHAR_LOOKUPS = 24;       // per read: one request each
    private static final long SHIFT_KEEP_MS = 20000;
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
    private static final long SOURCE_RECHECK_MS = 500;
    private static final float MAX_LEAD_PX = 160f;
    private static final float MAX_UNSURE_PX = 60f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread scanThread;
    private Handler scanHandler;
    private HandlerThread eventThread;
    private Handler eventHandler;
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
    private long lastDeepMs;
    // Reader threads: one window each at a time (see Read).
    private ExecutorService readers;
    private final Set<Integer> readingWindows = ConcurrentHashMap.newKeySet();
    // Texts you tapped "show" on: not covered again while the shield runs.
    private final Set<String> revealed = ConcurrentHashMap.newKeySet();
    // Invisible tap targets over each cover's "show" button (the cover layer itself lets
    // every touch through to the app).
    private final List<View> showButtons = new ArrayList<>();
    private final List<Cover> showFor = new ArrayList<>();
    private final List<Rect> showAt = new ArrayList<>();
    private volatile long lastStopAt;   // when the last scroll was seen to stop
    private boolean gliding;
    private final Choreographer.FrameCallback glideFrame = t -> {
        gliding = false;
        render();
    };
    private Cover confirming;    // first tap on its Show button, waiting for the second
    private long confirmUntil;
    // Where the text sits inside its item (see coverFor), most recent 256.
    private final java.util.LinkedHashMap<String, int[]> textBoxes =
            new java.util.LinkedHashMap<String, int[]>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, int[]> e) {
                    return size() > 256;
                }
            };
    private volatile boolean scrolling;
    private volatile boolean forceDeep = true;
    private volatile long lastScanStart;
    private volatile int regexTimeouts;

    // main thread state
    private int incompleteStreak;
    // Scrolling areas read whole (also what's scrolled out of view), per window.
    private final java.util.HashMap<Integer, List<Rect>> deepAreas = new java.util.HashMap<>();
    private List<Cover> covers = new ArrayList<>();
    private final List<Shift> shifts = new ArrayList<>();
    private final List<Tracker> trackers = new ArrayList<>();
    private final java.util.HashMap<String, Source> sources = new java.util.HashMap<>();
    private final Rect screenMain = new Rect();
    private boolean armed;
    private boolean framesRunning;
    private String lastPkg = "";
    private String lastAppPkg = "";
    // package -> when a cover on it was last drawn (main thread)
    private final java.util.HashMap<String, Long> spoilerSeen = new java.util.HashMap<>();
    // apps whose Recents card still shows spoilers (read by reader threads)
    private final Set<String> leftWithSpoilers = ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Boolean> appCache = new ConcurrentHashMap<>();
    private final java.util.Map<String, String> labelCache = new ConcurrentHashMap<>();
    private volatile String homePkg;
    private String lastScreen = "";
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
        int window = -1;         // the window it was read from
        float offX, offY;        // the item's own corner relative to r's corner
        String words;            // what matched, shown on the cover ("jason")
        String key;              // the covered text: revealing it is remembered by this
        String pkg;              // app it is in
        float glideX, glideY;    // drawn this far from r, shrinking to 0 (see applyRead)
        long glideAt;

        float glideNowX(long now) {
            return glideX * glideLeft(now);
        }

        float glideNowY(long now) {
            return glideY * glideLeft(now);
        }

        float glideLeft(long now) {
            float t = (now - glideAt) / 150f;
            if (glideAt == 0 || t >= 1) return 0;
            return (1 - t) * (1 - t);
        }
        boolean recents;         // an app's card in Recents, not text

        Cover(RectF r, Rect clip, long seq) {
            this.r = r;
            this.clip = clip;
            this.seq = seq;
        }
    }

    /** Movement applied to covers, kept so late read results can be brought up to date. */
    private static final class Shift {
        final long seq;
        final long at = SystemClock.uptimeMillis();
        final Tracker t;
        final float dx, dy;

        Shift(long seq, Tracker t, float dx, float dy) {
            this.seq = seq;
            this.t = t;
            this.dx = dx;
            this.dy = dy;
        }
    }

    /** Where a kind of scroll event comes from (see onAccessibilityEvent). */
    private static final class Source {
        final Rect c;
        long checkedAt;

        Source(Rect c, long checkedAt) {
            this.c = new Rect(c);
            this.checkedAt = checkedAt;
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
        int epoch;               // bumped when a read moves this list's covers
        boolean blind;           // moving, but neither events nor re-reads say how far
        int window = -1;         // the window it is in
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
                if (Prefs.KEY_REHIDE_AT.equals(key)) {
                    revealed.clear();   // "cover again everything I tapped Show on"
                    scheduleScan(0);
                    return;
                }
                if (Prefs.KEY_DUMP_AT.equals(key)) {
                    long at = p.getLong(Prefs.KEY_DUMP_AT, 0);
                    long wait = at - System.currentTimeMillis();
                    if (scanHandler != null && wait > -2000) {
                        scanHandler.postDelayed(this::saveDump, Math.max(0, wait));
                    }
                    return;
                }
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
        eventThread = new HandlerThread("shield-event");
        eventThread.start();
        eventHandler = new Handler(eventThread.getLooper());
        readers = Executors.newFixedThreadPool(READERS, r -> {
            Thread th = new Thread(r, "shield-read");
            th.setDaemon(true);
            return th;
        });
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
        if (eventThread != null) eventThread.quitSafely();
        if (readers != null) readers.shutdownNow();
        for (HandlerThread t : pollThreads) if (t != null) t.quitSafely();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        for (View v : new View[]{overlay, fingerWatcher}) {
            if (v == null || wm == null) continue;
            try {
                wm.removeView(v);
            } catch (RuntimeException ignored) {
            }
        }
        for (View v : showButtons) {
            try {
                wm.removeView(v);
            } catch (RuntimeException ignored) {
            }
        }
        showButtons.clear();
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
            // Which list scrolled is only known by asking the app (getSource is a request
            // answered on its main thread). Never ask from here: a busy app would freeze our
            // main thread, and with it every cover's movement. Ask on the event thread.
            // Once learned, the list is recognised from the event itself (window, kind of
            // view, direction), so movement is applied at once; the app is asked again now
            // and then to confirm (or when an unknown list scrolls).
            final long arrived = SystemClock.uptimeMillis();
            final AccessibilityEvent copy = Build.VERSION.SDK_INT >= 30
                    ? new AccessibilityEvent(event) : AccessibilityEvent.obtain(event);
            final String key = sourceKey(copy);
            Source known = sources.get(key);
            if (known != null) {
                onScrolled(copy, new Rect(known.c), arrived);
                if (arrived - known.checkedAt > SOURCE_RECHECK_MS && eventHandler != null) {
                    known.checkedAt = arrived;
                    eventHandler.post(() -> resolveScroll(copy, arrived, key, false));
                }
            } else if (eventHandler != null) {
                eventHandler.post(() -> resolveScroll(copy, arrived, key, true));
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pk = event.getPackageName();
            String pkg = pk == null ? "" : pk.toString();
            if (!pkg.equals(lastPkg) && !pkg.isEmpty()) {
                // Leaving an app: its card in Recents shows the screen as it was, so remember
                // whether spoilers were covered on it right then.
                Long seen = spoilerSeen.get(lastPkg);
                if (seen != null && SystemClock.uptimeMillis() - seen < 2000) {
                    leftWithSpoilers.add(lastPkg);
                } else if (isApp(pkg) || isApp(lastPkg)) {
                    leftWithSpoilers.remove(lastPkg);
                }
                if (trace) android.util.Log.i("ShieldTrace", "R " + SystemClock.uptimeMillis()
                        + " " + lastPkg + " -> " + pkg + " left=" + leftWithSpoilers
                        + " home=" + home() + " app=" + isApp(pkg));
            }
            // The shade, the screenshot preview, Recents and other system layers come and go
            // on top of the app: they are not a new screen, and the covers under them stay.
            if (isApp(pkg) && isNewScreen(event)) {
                // New screen: tracking from the old one no longer applies.
                trackers.clear();
                sources.clear();
                shifts.clear();
                deepAreas.clear();
                forceDeep = true;
                armed = false;
                if (!pkg.equals(lastAppPkg)) {
                    lastAppPkg = pkg;
                    leftWithSpoilers.remove(pkg);   // back in it: its card gets a new picture
                    // different app: its covers go (others' are dropped with their windows)
                    List<Cover> keep = new ArrayList<>();
                    for (Cover c : covers) if (c.recents) keep.add(c);
                    covers = keep;
                }
                render();
            }
            if (!pkg.isEmpty()) lastPkg = pkg;
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

    /** Event thread: find out which list scrolled, then continue on main. */
    /**
     * "Window state changed" also comes for small things: a pane or its title changing, a web
     * page updating its title. Only a different screen (another activity, dialog or app)
     * resets tracking; resetting on every one of these lost all tracking mid-scroll.
     */
    /** A normal app you open from the launcher (not system UI, not the home screen). */
    private boolean isApp(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) return false;
        Boolean b = appCache.get(pkg);
        if (b == null) {
            b = !pkg.equals(home()) && !pkg.equals("com.android.systemui")
                    && getPackageManager().getLaunchIntentForPackage(pkg) != null;
            appCache.put(pkg, b);
        }
        return b;
    }

    private String home() {
        if (homePkg == null) {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(i,
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            homePkg = r == null || r.activityInfo == null ? "" : r.activityInfo.packageName;
        }
        return homePkg;
    }

    private String label(String pkg) {
        String l = labelCache.get(pkg);
        if (l == null) {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim();
            } catch (Exception e) {
                l = "";
            }
            labelCache.put(pkg, l);
        }
        return l;
    }

    private boolean isNewScreen(AccessibilityEvent e) {
        int changes = Build.VERSION.SDK_INT >= 28 ? e.getContentChangeTypes() : 0;
        String what = e.getPackageName() + "/" + e.getClassName();
        if (trace) {
            android.util.Log.i("ShieldTrace", "W " + SystemClock.uptimeMillis() + " " + what
                    + " changes=" + changes);
        }
        if (changes != 0) return false;
        if (what.equals(lastScreen)) return false;
        lastScreen = what;
        return true;
    }

    private static String sourceKey(AccessibilityEvent e) {
        boolean sideways = Build.VERSION.SDK_INT >= 28
                && e.getScrollDeltaX() != 0 && e.getScrollDeltaX() != -1;
        return e.getWindowId() + "|" + e.getClassName() + "|" + (sideways ? "x" : "y");
    }

    /** @param apply false = the movement was already applied; only confirm the list */
    private void resolveScroll(AccessibilityEvent event, long arrived, String key,
            boolean apply) {
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
        main.post(() -> {
            Source known = sources.get(key);
            if (known == null) sources.put(key, new Source(container, arrived));
            else known.c.set(container);
            if (apply) onScrolled(event, container, arrived);
        });
    }

    private void onScrolled(AccessibilityEvent event, Rect container, long arrived) {
        if (!enabled) return;
        Tracker t = trackerFor(container);
        t.window = event.getWindowId();

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
            dbgScroll = "scroll: d=(" + dx + "," + dy + ") " + event.getClassName()
                    + " h=" + container.height()
                    + " late=" + (SystemClock.uptimeMillis() - arrived) + "ms";
        }
        long now = SystemClock.uptimeMillis();
        if (trace) android.util.Log.i("ShieldTrace", "E " + now + " " + (now - arrived) + " " + dy);
        if (dx == 0 && dy == 0) {
            // Some lists (ListView and others that move rows instead of scrolling) report
            // "I scrolled" without saying how far. It still means: this list is moving now.
            // Re-read its position every frame; that is where the movement comes from.
            t.lastEventArr = Math.max(t.lastEventArr, arrived);
            t.lastSeen = now;
            t.hotUntil = Math.max(t.hotUntil, now + HOT_AFTER_MOVE_MS);
            if (pickAnchor(t) != null || armed) {
                t.moving = true;
                scrolling = true;
                if (t.anchor == null) t.blind = true;
                startFrames();
            }
            return;
        }
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
        t.blind = false;
        t.lastEventSent = at;
        t.lastEventArr = Math.max(t.lastEventArr, arrived);
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
                    t.moving = false;   // (stays blind until a read shows what's there)
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
        // A scroll ended once nothing moves any more (a finger may still rest on the screen,
        // which keeps the frames running, but the page is still: read it whole now).
        boolean anyMoving = false;
        for (Tracker t : trackers) if (t.moving) anyMoving = true;
        if (scrolling && !anyMoving) {
            scrolling = false;
            forceDeep = true;
            lastStopAt = now;
            if (trace) android.util.Log.i("ShieldTrace", "Z " + now + " still");
            scheduleScan(0);
        }
        if (any && enabled) {
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else {
            framesRunning = false;
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
        final int epoch = t.epoch;
        final float shownX = t.tx.shown(), shownY = t.ty.shown();
        final float drawnX = a.r.left + a.offX, drawnY = a.r.top + a.offY;
        final Pattern p = pattern;
        final AccessibilityNodeInfo original = a.node;
        h.post(() -> {
            long t0 = SystemClock.uptimeMillis();
            boolean ok = false, still = false, shows = false;
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
                    shows = p != null
                            && (matches(p, n.getText()) || matches(p, n.getContentDescription()));
                    still = shows && n.isVisibleToUser();
                }
            } catch (RuntimeException e) {
                ok = false;
            }
            long t1 = SystemClock.uptimeMillis();
            final boolean fine = ok && still && !b.isEmpty();
            // answered, and it shows other content now (or scrolled out of view)
            final int gone = !ok || still ? 0 : shows ? 1 : 2;
            main.post(() -> onPoll(t, a, epoch, fine, gone, b, t0, t1, shownX, shownY,
                    drawnX, drawnY));
        });
    }

    private void onPoll(Tracker t, Cover a, int epoch, boolean ok, int gone, Rect b,
            long t0, long t1, float shownX, float shownY, float drawnX, float drawnY) {
        t.inFlight = Math.max(0, t.inFlight - 1);
        if (!trackers.contains(t)) return;
        // A read put this list's covers in new places after this question was asked: the
        // answer compares against where they were then, so it would correct twice.
        if (epoch != t.epoch) return;
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
            // The row now shows other content (lists reuse rows) or scrolled away: its cover
            // marks nothing any more. A fresh read places covers where the text is now.
            // (Out of view on a page that was read whole: keep it for when it comes back.)
            if (gone == 2 || (gone == 1 && !wholePageRead(t.c))) dropStale(a);
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
            dropStale(a);
            return;
        }
        // (2 px: web pages report positions that wobble by a pixel while still)
        boolean moved = !Float.isNaN(t.lastPollY)
                && (Math.abs(b.top - t.lastPollY) >= 2 || Math.abs(b.left - t.lastPollX) >= 2);
        t.lastPollX = b.left;
        t.lastPollY = b.top;
        t.lastPollLive = now;
        t.lastSeen = now;
        t.blind = false;
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
    private void dropStale(Cover a) {
        if (!covers.remove(a)) return;
        List<Cover> next = new ArrayList<>(covers);   // covers is replaced, never shared
        covers = next;
        render();
        scheduleScan(0);
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
    // Reading the screen
    // =====================================================================

    /**
     * One read of one window (or of just the moving list), with its own state. Windows are
     * read in parallel: every request is answered on that app's main thread, and one window
     * that answers slowly (a busy app, a frozen dialog) must not hold back the others.
     */
    private static final class Read {
        final long start = SystemClock.uptimeMillis();
        final long startSeq;
        final boolean deep;       // may also read what's scrolled out of view
        final Rect screen = new Rect();
        final Rect tmp = new Rect();
        final List<Cover> found = new ArrayList<>();
        final List<Rect> deepAreas = new ArrayList<>();
        int nodes, errors, scrollables, invisibles, charLookups;
        boolean deepCut, timedOut;

        Read(long startSeq, boolean deep) {
            this.startSeq = startSeq;
            this.deep = deep;
        }
    }

    private void scheduleScan(long delay) {
        if (scanHandler == null) return;
        if (!scanPending.compareAndSet(false, true)) return;
        if (scrolling) {
            // Each read is answered on the app's main thread, between its frames: reading
            // back-to-back while it scrolls makes it drop frames (measured on the emulator).
            long since = SystemClock.uptimeMillis() - lastScanStart;
            delay = Math.max(delay, SCAN_GAP_MOVING_MS - since);
        }
        scanHandler.postDelayed(scanRunnable, Math.max(0, delay));
    }

    /** Scan thread: decides what to read and hands each window to a reader thread. */
    private void backgroundScan() {
        scanPending.set(false);
        final Pattern p = pattern;
        if (!enabled || p == null) return;
        long now = SystemClock.uptimeMillis();
        lastScanStart = now;
        // While still, the app answers fast: read the whole page (also what's scrolled out
        // of view), so covers are ready before it scrolls in. Not more than every 3 s
        // unless a scroll just ended or the screen changed.
        boolean deep = !scrolling && (forceDeep || now - lastDeepMs > 3000);
        if (deep) {
            forceDeep = false;
            lastDeepMs = now;
        }
        final long seq = shiftSeq.get();
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (RuntimeException e) {
            windows = null;
        }
        if (windows == null || windows.isEmpty()) {
            AccessibilityNodeInfo root;
            try {
                root = getRootInActiveWindow();
            } catch (RuntimeException e) {
                root = null;
            }
            if (root != null) {
                Read rd = new Read(seq, deep);
                readScreenBounds(rd.screen);
                scanRoot(rd, root, p);
                Rect wb = new Rect(rd.screen);
                int id = root.getWindowId();
                traceRead(rd, "win active");
                main.post(() -> applyRead(rd, id, wb, null));
            }
            return;
        }
        final Set<Integer> present = new HashSet<>();
        for (AccessibilityWindowInfo w : windows) {
            int type = w.getType();
            if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    || type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                continue;
            }
            present.add(w.getId());
            readWindow(w, seq, deep, p);
        }
        main.post(() -> dropGoneWindows(present));
    }

    private void readWindow(AccessibilityWindowInfo w, long seq, boolean deep, Pattern p) {
        final int id = w.getId();
        if (!readingWindows.add(id)) return;   // still being read from before: its answer comes
        final Rect wb = new Rect();
        w.getBoundsInScreen(wb);
        final String label = trace ? "win " + w.getType() + " " + w.getTitle() : null;
        try {
            readers.execute(() -> {
                Read rd = new Read(seq, deep);
                try {
                    readScreenBounds(rd.screen);
                    AccessibilityNodeInfo root = rootOf(w);
                    if (root != null) {
                        scanRoot(rd, root, p);
                    } else if (SystemClock.uptimeMillis() - rd.start > 1000) {
                        rd.errors++;   // it didn't answer in time: we know nothing about it
                    }
                } catch (RuntimeException e) {
                    rd.errors++;
                } finally {
                    readingWindows.remove(id);
                }
                traceRead(rd, label);
                main.post(() -> applyRead(rd, id, wb, null));
            });
        } catch (RuntimeException e) {   // shutting down
            readingWindows.remove(id);
        }
    }

    private void traceRead(Read rd, String label) {
        if (!trace) return;
        long now = SystemClock.uptimeMillis();
        android.util.Log.i("ShieldTrace", "S " + now + " " + (now - rd.start) + " " + rd.nodes
                + " " + rd.found.size() + " " + rd.errors + " "
                + (label == null ? "win" : label.replace('\n', ' ')));
        android.util.Log.i("ShieldTrace", "D " + now + " scrollables=" + rd.scrollables
                + " invisible=" + rd.invisibles + " deep=" + rd.deep + " cut=" + rd.deepCut
                + " timeout=" + rd.timedOut + " areas=" + rd.deepAreas.size()
                + " charlookups=" + rd.charLookups);
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

    private void scanRoot(Read rd, AccessibilityNodeInfo root, Pattern p) {
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        if (pkg != null) {
            String name = pkg.toString();
            // Never cover our own settings screen, so the keyword list stays editable.
            if (name.equals(getPackageName()) || ignored.contains(name)) return;
        }
        walk(rd, root, p, 0, null);
    }

    /** @param container bounds of the nearest scrolling ancestor, or null */
    private void walk(Read rd, AccessibilityNodeInfo node, Pattern p, int depth, Rect container) {
        if (node == null || depth > MAX_DEPTH || rd.timedOut) return;
        rd.nodes++;
        if (SystemClock.uptimeMillis() - rd.start > READ_BUDGET_MS) {
            rd.timedOut = true;   // app answers too slowly: hand over what we have, try again
            rd.errors++;
            return;
        }
        try {
            boolean visible = node.isVisibleToUser();
            if (!visible) {
                rd.invisibles++;
                if (container == null) return;
                if (!rd.deep || scrolling || rd.nodes > DEEP_MAX_NODES
                        || SystemClock.uptimeMillis() - rd.start > DEEP_BUDGET_MS) {
                    rd.deepCut = true;
                    return;
                }
                boundsOf(node, rd.tmp);
                if (trace && rd.invisibles <= 3 && rd.deep) {
                    CharSequence tx = node.getText();
                    android.util.Log.i("ShieldTrace", "I " + SystemClock.uptimeMillis() + " "
                            + rd.tmp.toShortString() + " in " + container.toShortString() + " "
                            + node.getClassName() + " '" + (tx == null ? "" : tx.subSequence(0,
                            Math.min(20, tx.length()))) + "' extras=" + bundleText(node.getExtras()));
                }
                if (rd.tmp.isEmpty()) return;
                if (Rect.intersects(rd.tmp, container)) return;      // hidden, not scrolled off
                if (rd.tmp.right <= container.left || rd.tmp.left >= container.right) return;
                boolean known = false;
                for (Rect a : rd.deepAreas) {
                    if (a.equals(container)) {
                        known = true;
                        break;
                    }
                }
                if (!known) rd.deepAreas.add(new Rect(container));
            }

            if (!leftWithSpoilers.isEmpty() && visible) {
                Cover card = recentsCard(rd, node, container);
                if (card != null) {
                    rd.found.add(card);
                    return;
                }
            }
            CharSequence hitText = null;
            String words = null;
            if (!node.isEditable()) {
                CharSequence[] texts = {node.getText(), node.getContentDescription(),
                        Build.VERSION.SDK_INT >= 30 ? node.getStateDescription() : null,
                        node.getHintText(),
                        Build.VERSION.SDK_INT >= 28 ? node.getTooltipText() : null};
                for (CharSequence t : texts) {
                    words = matchWords(p, t);
                    if (words != null) {
                        hitText = t;
                        break;
                    }
                }
            }
            if (words != null) {
                String key = hitText.toString().trim();
                if (revealed.contains(key)) return;   // you chose to see this one
                AccessibilityNodeInfo hit = tightest(node, p, 0);
                if (trace && rd.found.isEmpty()) {
                    Rect hb = new Rect();
                    hit.getBoundsInScreen(hb);
                    android.util.Log.i("ShieldTrace", "T " + SystemClock.uptimeMillis() + " "
                            + hit.getClassName() + " " + hb.toShortString() + " children="
                            + hit.getChildCount() + " extra=" + (Build.VERSION.SDK_INT >= 26
                            ? hit.getAvailableExtraData() : null));
                }
                Cover c = coverFor(rd, hit, container);
                if (c != null) {
                    c.words = words;
                    c.key = key;
                    CharSequence np = node.getPackageName();
                    c.pkg = np == null ? null : np.toString();
                    rd.found.add(c);
                }
                return; // everything inside is covered already
            }

            Rect childContainer = container;
            if (visible && node.isScrollable()) {
                rd.scrollables++;
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                if (!b.isEmpty()) childContainer = b;
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo child = Build.VERSION.SDK_INT >= 33
                        ? node.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST)
                        : node.getChild(i);
                walk(rd, child, p, depth + 1, childContainer);
            }
        } catch (RuntimeException ignored) {
            rd.errors++;   // node went stale mid-read: this read is incomplete
        }
    }

    /**
     * The smallest item that still contains the match: a web paragraph reports its text, but
     * so does the line inside it, and covering just the line hides the right thing.
     */
    private AccessibilityNodeInfo tightest(AccessibilityNodeInfo node, Pattern p, int depth) {
        if (depth > 6) return node;
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = Build.VERSION.SDK_INT >= 33
                    ? node.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST)
                    : node.getChild(i);
            if (child == null || !child.isVisibleToUser()) continue;
            if (matches(p, child.getText()) || matches(p, child.getContentDescription())) {
                return tightest(child, p, depth + 1);
            }
        }
        return node;
    }

    // =====================================================================
    // Screen dump (settings button): everything readable, to diagnose missed text
    // =====================================================================

    private void saveDump() {
        StringBuilder sb = new StringBuilder();
        sb.append("Spoiler Shield ").append(BuildConfigVersion()).append(" dump ")
                .append(new java.util.Date()).append('\n');
        Pattern p = pattern;
        int[] count = {0};
        List<AccessibilityWindowInfo> ws;
        try {
            ws = getWindows();
        } catch (RuntimeException e) {
            ws = new ArrayList<>();
        }
        for (AccessibilityWindowInfo w : ws) {
            Rect b = new Rect();
            w.getBoundsInScreen(b);
            sb.append("\n=== window ").append(w.getId()).append(" type=").append(w.getType())
                    .append(" title=").append(w.getTitle()).append(' ').append(b.toShortString())
                    .append('\n');
            AccessibilityNodeInfo root = rootOf(w);
            if (root != null) dumpNode(sb, root, p, 0, count);
        }
        String name = "spoiler-shield-dump-" + new java.text.SimpleDateFormat("HHmmss",
                java.util.Locale.ROOT).format(new java.util.Date()) + ".txt";
        String where;
        try {
            byte[] data = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
                android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                try (java.io.OutputStream o = getContentResolver().openOutputStream(uri)) {
                    o.write(data);
                }
                where = "Downloads/" + name;
            } else {
                java.io.File f = new java.io.File(getExternalFilesDir(null), name);
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                    o.write(data);
                }
                where = f.getPath();
            }
        } catch (Exception e) {
            where = null;
        }
        final String msg = where != null ? "Saved " + where + " (" + count[0] + " items)"
                : "Couldn't save the dump";
        main.post(() -> android.widget.Toast.makeText(this, msg,
                android.widget.Toast.LENGTH_LONG).show());
    }

    private String BuildConfigVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void dumpNode(StringBuilder sb, AccessibilityNodeInfo n, Pattern p, int depth,
            int[] count) {
        if (n == null || depth > 80 || count[0] >= 5000) return;
        count[0]++;
        try {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            for (int i = 0; i < depth; i++) sb.append(' ');
            CharSequence cls = n.getClassName();
            sb.append(cls == null ? "?" : cls.toString().substring(cls.toString().lastIndexOf('.') + 1))
                    .append(' ').append(b.toShortString());
            if (!n.isVisibleToUser()) sb.append(" INVISIBLE");
            if (n.isScrollable()) sb.append(" SCROLLABLE");
            if (n.isEditable()) sb.append(" EDITABLE");
            if (n.getViewIdResourceName() != null) sb.append(" id=").append(n.getViewIdResourceName());
            field(sb, "text", n.getText(), p);
            field(sb, "desc", n.getContentDescription(), p);
            field(sb, "hint", n.getHintText(), p);
            if (Build.VERSION.SDK_INT >= 28) {
                field(sb, "tooltip", n.getTooltipText(), p);
                field(sb, "pane", n.getPaneTitle(), p);
            }
            if (Build.VERSION.SDK_INT >= 30) field(sb, "state", n.getStateDescription(), p);
            android.os.Bundle x = n.getExtras();
            if (x != null && !x.isEmpty()) sb.append(" extras=").append(bundleText(x));
            List<String> ed = n.getAvailableExtraData();
            if (ed != null && !ed.isEmpty()) sb.append(" extraData=").append(ed);
            sb.append(" children=").append(n.getChildCount()).append('\n');
            for (int i = 0; i < n.getChildCount(); i++) {
                dumpNode(sb, n.getChild(i), p, depth + 1, count);
            }
        } catch (RuntimeException e) {
            sb.append(" ERROR ").append(e).append('\n');
        }
    }

    private void field(StringBuilder sb, String name, CharSequence v, Pattern p) {
        if (v == null || v.length() == 0) return;
        String s = v.toString().replace('\n', ' ');
        if (s.length() > 300) s = s.substring(0, 300) + "...";
        sb.append(' ').append(name).append("=\"").append(s).append('"');
        if (p != null && matchWords(p, v) != null) sb.append(" <MATCH>");
    }

    /**
     * In Recents (or any system/home screen), an app's card is a picture of how the app looked
     * when you left it, which the shield can't read. If that app had spoilers covered at that
     * moment, cover its whole card: found by the app's name on the card.
     */
    private Cover recentsCard(Read rd, AccessibilityNodeInfo node, Rect container) {
        CharSequence np = node.getPackageName();
        String pkg = np == null ? "" : np.toString();
        if (isApp(pkg)) return null;   // only system screens (home, Recents, system UI)
        CharSequence t = node.getText();
        if (t == null || t.length() == 0) t = node.getContentDescription();
        if (t == null || t.length() == 0 || t.length() > 60) return null;
        String name = t.toString().trim();
        String app = null;
        for (String a : leftWithSpoilers) {
            String l = label(a);
            if (!l.isEmpty() && (name.equalsIgnoreCase(l) || name.toLowerCase(java.util.Locale.ROOT)
                    .startsWith(l.toLowerCase(java.util.Locale.ROOT) + ","))) {
                app = a;
                break;
            }
        }
        if (app == null) return null;
        if (revealed.contains("recents:" + app)) return null;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty() || rd.screen.isEmpty()) return null;
        long screenArea = (long) rd.screen.width() * rd.screen.height();
        int cardH = rd.screen.height() / 5;
        // Either this is the card itself (its description is the app name: Pixel-style), or
        // the name sits on the card's header: go up to the card (picture + header).
        int labelH = Math.min(r.height(), cardH / 4);
        AccessibilityNodeInfo cur = node;
        Rect b = new Rect(r);
        if ((long) r.width() * r.height() > screenArea * 0.8) return null;
        for (int i = 0; i < 5 && b.height() < Math.max(4 * labelH, cardH); i++) {
            AccessibilityNodeInfo parent = cur.getParent();
            if (parent == null) break;
            Rect pb = new Rect();
            parent.getBoundsInScreen(pb);
            if ((long) pb.width() * pb.height() > screenArea * 0.8) break;   // that's everything
            b.set(pb);
            cur = parent;
        }
        if (b.height() < 3 * labelH) return null;   // didn't find a card around it
        Cover c = new Cover(new RectF(b), container == null ? null : new Rect(container),
                rd.startSeq);
        c.window = node.getWindowId();
        c.recents = true;
        c.pkg = pkg;
        c.words = "spoilers in " + label(app);
        c.key = "recents:" + app;
        c.node = container == null ? null : cur;
        if (c.node != null) {
            c.offX = b.left - c.r.left;
            c.offY = b.top - c.r.top;
        }
        return c;
    }

    /** The distinct words that matched ("jason · vice city"), or null for none. */
    private String matchWords(Pattern p, CharSequence s) {
        if (s == null || s.length() == 0) return null;
        java.util.LinkedHashSet<String> got = new java.util.LinkedHashSet<>();
        try {
            java.util.regex.Matcher m = p.matcher(new TimedText(s, REGEX_BUDGET_MS));
            while (m.find() && got.size() < 3) {
                String w = m.group().trim().toLowerCase(java.util.Locale.ROOT);
                if (!w.isEmpty()) got.add(w);
                if (m.end() == m.start() && m.end() >= s.length()) break;
            }
        } catch (TimedText.TimeUp e) {
            regexTimeouts++;
        }
        return got.isEmpty() ? null : String.join(" · ", got);
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
    private Cover coverFor(Read rd, AccessibilityNodeInfo node, Rect container) {
        long seq = rd.startSeq;
        Rect r = new Rect();
        boundsOf(node, r);
        if (r.isEmpty()) return null;
        int window = node.getWindowId();
        boolean onScreen = node.isVisibleToUser();
        // Cover the text itself, not the box around it: a web paragraph's box includes its
        // padding, a text view's its padding. Ask where the characters are drawn.
        boolean measuredNow = false;
        int nodeLeft = r.left, nodeTop = r.top;
        if (onScreen && (!coverCard || container == null)) {
            // Where the text sits inside its box doesn't change while it scrolls: ask once
            // (one request), then reuse. While scrolling, never ask (the app is busy).
            CharSequence txt = node.getText();
            String key = node.hashCode() + "|" + window + "|" + txt;
            int[] rel;
            synchronized (textBoxes) {
                rel = textBoxes.get(key);
            }
            if (trace && rel == null && scrolling) {
                android.util.Log.i("ShieldTrace", "X skipped-scrolling");
            }
            if (rel != null && rel[4] == r.width() && rel[5] == r.height()) {
                r.set(r.left + rel[0], r.top + rel[1], r.left + rel[2], r.top + rel[3]);
            } else if (!scrolling) {
                long s0 = shiftSeq.get();
                Rect nb = new Rect(), tb = new Rect();
                if (textBounds(rd, node, tb, nb) && tb.intersect(nb)) {
                    synchronized (textBoxes) {
                        textBoxes.put(key, new int[]{tb.left - nb.left, tb.top - nb.top,
                                tb.right - nb.left, tb.bottom - nb.top, nb.width(), nb.height()});
                    }
                    r.set(tb);
                    nodeLeft = nb.left;
                    nodeTop = nb.top;
                    seq = s0;
                    measuredNow = true;
                }
            }
        }
        if (container == null) {
            if (!rd.screen.isEmpty() && !r.intersect(rd.screen)) return null;
            Cover c = new Cover(new RectF(r), null, seq);
            c.window = window;
            return c;
        }
        final int oldLeft = nodeLeft, oldTop = nodeTop;
        final long textArea = Math.max(1L, (long) r.width() * r.height());
        Rect tmp = rd.tmp;
        if (coverCard && !rd.screen.isEmpty()) {
            int maxH = (int) (rd.screen.height() * ITEM_MAX_HEIGHT);
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
        if (scrolling && !measuredNow) {
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
                rd.errors++;
            }
        }
        Cover c = new Cover(new RectF(r), new Rect(container), seq);
        c.node = node;
        c.window = window;
        c.offX = nodeLeft - r.left;
        c.offY = nodeTop - r.top;
        return c;
    }

    /**
     * Where the node's characters are drawn (union), and the node's own bounds measured in the
     * same answer. Text views, web views and Compose text answer this; others return false.
     */
    private boolean textBounds(Read rd, AccessibilityNodeInfo node, Rect text, Rect nodeBounds) {
        if (Build.VERSION.SDK_INT < 26 || rd.charLookups >= MAX_CHAR_LOOKUPS) return false;
        // Apps advertise it under the framework name or AndroidX's (web views use the latter).
        List<String> extra = node.getAvailableExtraData();
        if (extra == null) return false;
        String key = null;
        for (String k : extra) if (k.endsWith("DATA_TEXT_CHARACTER_LOCATION_KEY")) key = k;
        if (key == null) return false;
        String argPrefix = key.substring(0, key.length() - "KEY".length());
        CharSequence s = node.getText();
        if (s == null || s.length() == 0 || s.length() > 2000) return false;
        rd.charLookups++;
        android.os.Bundle args = new android.os.Bundle();
        args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0);
        args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, s.length());
        args.putInt(argPrefix + "ARG_START_INDEX", 0);
        args.putInt(argPrefix + "ARG_LENGTH", s.length());
        AccessibilityNodeInfo copy = Build.VERSION.SDK_INT >= 30
                ? new AccessibilityNodeInfo(node) : AccessibilityNodeInfo.obtain(node);
        if (!copy.refreshWithExtraData(key, args)) {
            if (trace) android.util.Log.i("ShieldTrace", "X refresh-failed " + key);
            return false;
        }
        android.os.Parcelable[] boxes = copy.getExtras().getParcelableArray(key);
        if (boxes == null) {
            if (trace) android.util.Log.i("ShieldTrace", "X no-boxes " + key + " "
                    + bundleText(copy.getExtras()));
            return false;
        }
        RectF u = null;
        for (android.os.Parcelable b : boxes) {
            if (!(b instanceof RectF)) continue;   // null = character not on screen
            RectF f = (RectF) b;
            if (f.width() <= 0 || f.height() <= 0) continue;
            if (u == null) u = new RectF(f); else u.union(f);
        }
        if (u == null) {
            if (trace) android.util.Log.i("ShieldTrace", "X all-null n=" + boxes.length);
            return false;
        }
        if (trace) android.util.Log.i("ShieldTrace", "X ok " + u.toShortString());
        text.set((int) Math.floor(u.left), (int) Math.floor(u.top),
                (int) Math.ceil(u.right), (int) Math.ceil(u.bottom));
        copy.getBoundsInScreen(nodeBounds);
        return !text.isEmpty() && !nodeBounds.isEmpty();
    }

    /**
     * The node's bounds on screen. For something scrolled out of view Android cuts the box
     * at the edge of the view, which loses where it really is. Web views also report the
     * uncut edges ("unclippedTop/Bottom"): with those, a whole web page is known in advance.
     */
    private static void boundsOf(AccessibilityNodeInfo node, Rect out) {
        node.getBoundsInScreen(out);
        if (out.top < out.bottom && out.left < out.right) return;
        android.os.Bundle x = node.getExtras();
        if (x == null) return;
        int top = x.getInt("AccessibilityNodeInfo.unclippedTop", Integer.MIN_VALUE);
        int bottom = x.getInt("AccessibilityNodeInfo.unclippedBottom", Integer.MIN_VALUE);
        if (top != Integer.MIN_VALUE) out.top = top;
        if (bottom != Integer.MIN_VALUE) out.bottom = bottom;
        if (out.top >= out.bottom) out.setEmpty();
    }

    private static String bundleText(android.os.Bundle b) {
        if (b == null) return "-";
        StringBuilder sb = new StringBuilder("{");
        for (String k : b.keySet()) sb.append(k).append('=').append(b.get(k)).append(' ');
        return sb.append('}').toString();
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

    /**
     * Did this read cover this list? Only a read of the list's own window does: system
     * windows (status bar, shade) can span the whole screen, and their reads used to count
     * as having read the list underneath, taking its edge curtain down mid-fling.
     */
    private static boolean readCovers(Tracker t, int window, Rect area) {
        if (t.window != -1 && window != -1) return t.window == window;
        return area == null || movesWith(t.c, area);
    }

    /** A window closed (a dialog, a popup): its covers go with it. */
    private void dropGoneWindows(Set<Integer> present) {
        deepAreas.keySet().retainAll(present);
        boolean changed = false;
        for (Iterator<Cover> it = covers.iterator(); it.hasNext(); ) {
            Cover c = it.next();
            if (c.window != -1 && !present.contains(c.window)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) render();
    }

    /**
     * One window's (or one list's) read is in. It replaces what is known about that window
     * only; covers elsewhere stay as they are.
     *
     * @param partial the list that was read alone, or null for a whole window
     */
    private void applyRead(Read rd, int window, Rect windowArea, Rect partial) {
        List<Cover> found = rd.found;
        long startSeq = rd.startSeq;
        boolean deepDone = partial == null && rd.deep && !rd.deepCut && rd.errors == 0;
        Rect area = partial != null ? partial : windowArea;
        if (debug) {
            dbgScan = "scan: " + (SystemClock.uptimeMillis() - rd.start) + "ms, " + rd.nodes
                    + " nodes, " + found.size() + " hits, " + rd.errors + " err, "
                    + (partial != null ? "list" : deepDone ? "FULL x" + rd.deepAreas.size()
                    : "visible");
        }
        // A read that hit errors may have missed spoilers: never uncover on its word.
        boolean incomplete = rd.errors > 0 && incompleteStreak < 6;
        incompleteStreak = rd.errors > 0 ? incompleteStreak + 1 : 0;
        List<Cover> keep = new ArrayList<>();
        List<Cover> scope = new ArrayList<>();
        for (Cover old : covers) {
            boolean in = partial != null ? movesWith(old.clip, partial)
                    : old.window == window || (old.window == -1 && window == -1);
            if (in) scope.add(old); else keep.add(old);
        }
        if (incomplete) {
            for (Cover old : scope) {
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
            // Only where the whole page was read before (web pages): a recycling list throws
            // away rows that leave the screen, so a cover kept for one would end up on
            // whatever row reuses it.
            RectF clipF = new RectF();
            for (Cover old : scope) {
                if (old.clip == null || !wholePageRead(old.clip)) continue;
                clipF.set(old.clip);
                if (!RectF.intersects(old.r, clipF)) keep.add(old);
            }
        }
        if (deepDone) {
            // (Per window: a read of a full-screen system window says nothing about the
            // page underneath; it used to wipe the page's "read whole" mark.)
            deepAreas.put(window, new ArrayList<>(rd.deepAreas));
        }

        // Bring results measured during movement up to date.
        for (Shift s : shifts) {
            if (s.seq <= startSeq) continue;
            for (Cover c : found) {
                if (s.seq > c.seq && movesWith(c.clip, s.t.c)) c.r.offset(s.dx, s.dy);
            }
        }
        // Whatever moved after this read began is still unread (edge curtain).
        if (!incomplete) {
            for (Tracker t : trackers) {
                if (readCovers(t, window, area)) {
                    t.unX = t.unY = 0;
                    t.blind = false;
                }
            }
            for (Shift s : shifts) {
                if (s.seq <= startSeq) continue;
                if (!readCovers(s.t, window, area)) continue;
                s.t.unX += s.dx;
                s.t.unY += s.dy;
            }
        }

        // During movement, keep covers that are already being tracked smoothly instead of
        // swapping in copies measured at a slightly different moment (no jitter), unless the
        // read shows one is clearly off.
        // A read that began before the scroll stopped measured things mid-movement: it can
        // say what is there, but not exactly where. Keep the covers where tracking put them
        // and let the fresh read after the stop place them (this was the "jumping for a couple
        // of seconds after I stop scrolling").
        // A fresh read that does move a cover glides it there (~150 ms) instead of snapping.
        boolean anyMoving = false;
        for (Tracker t : trackers) if (t.moving) anyMoving = true;
        boolean midMove = rd.start <= lastStopAt || anyMoving;
        long nowA = SystemClock.uptimeMillis();
        for (int i = 0; i < found.size(); i++) {
            Cover f = found.get(i);
            if (f.node == null) continue;
            for (Cover old : scope) {
                if (old.node == null || !old.node.equals(f.node)) continue;
                float dx = old.r.left - f.r.left, dy = old.r.top - f.r.top;
                boolean close = Math.abs(dx) <= 48 && Math.abs(dy) <= 48
                        && old.r.width() >= f.r.width() * 0.8f;
                if (midMove && (close || !anyMoving)) {
                    found.set(i, old);
                } else if (!midMove && (Math.abs(dx) >= 2 || Math.abs(dy) >= 2)
                        && Math.abs(dx) < 400 && Math.abs(dy) < 400) {
                    f.glideX = old.glideNowX(nowA) + dx;
                    f.glideY = old.glideNowY(nowA) + dy;
                    f.glideAt = nowA;
                }
                break;
            }
        }
        // Covers placed by this read: re-reads already on their way measured against the old
        // places and must not be applied on top.
        for (Cover c : found) {
            if (scope.contains(c) || c.clip == null) continue;
            for (Tracker t : trackers) if (movesWith(c.clip, t.c)) t.epoch++;
        }
        List<Cover> next = new ArrayList<>(found);
        next.addAll(keep);
        covers = next;
        // Every list with covers gets a tracker, so its re-reads can start right away.
        for (Cover c : covers) {
            if (c.node != null && c.clip != null && !c.clip.isEmpty()) {
                Tracker t = trackerFor(c.clip);
                if (t.window == -1) t.window = c.window;
            }
        }
        if (!found.isEmpty()) armed = true;
        if (!enabled) armed = false;

        long now = SystemClock.uptimeMillis();
        // Old movement records are only needed by reads still running (none run this long).
        for (Iterator<Shift> it = shifts.iterator(); it.hasNext(); ) {
            if (now - it.next().at > SHIFT_KEEP_MS) it.remove(); else break;
        }
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

        boolean curtainUp = false;
        for (Tracker t : trackers) if (t.unX != 0 || t.unY != 0 || t.blind) curtainUp = true;
        main.removeCallbacks(recheckRunnable);
        if (anyActive || incomplete) {
            // keep reading while it scrolls / until clean (gently, if the app is slow)
            scheduleScan(incomplete ? Math.min(300, Math.max(40, (now - rd.start) / 2)) : 0);
        } else if (curtainUp) {
            scheduleScan(100);   // something unread is blanked out: read it soon
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
        for (List<Rect> l : deepAreas.values()) {
            for (Rect a : l) if (overlapShare(c, a) >= 0.5f) return true;
        }
        return false;
    }

    /**
     * Puts a tap target over each cover's "show" button, only while nothing moves (moving a
     * window every frame is costly, and a target must never catch a scroll). At most 8.
     */
    private void placeShowButtons(List<Cover> cs, List<Rect> rects) {
        if (wm == null) return;
        int n = cs == null ? 0 : Math.min(cs.size(), 8);
        int hit = Math.round(44 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < Math.max(n, showButtons.size()); i++) {
            if (i >= showButtons.size()) {
                final int idx = i;
                View v = new View(this);
                v.setOnClickListener(x -> {
                    if (idx < showFor.size() && showFor.get(idx) != null) onShowTap(showFor.get(idx));
                });
                WindowManager.LayoutParams lp = buttonParams(hit);
                try {
                    wm.addView(v, lp);
                } catch (RuntimeException e) {
                    return;
                }
                showButtons.add(v);
                showFor.add(null);
                showAt.add(new Rect());
            }
            View v = showButtons.get(i);
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
            Rect want = new Rect();
            if (i < n) {
                RectF b = OverlayView.showButton(rects.get(i), getResources().getDisplayMetrics().density);
                want.set(Math.round(b.centerX() - hit / 2f), Math.round(b.centerY() - hit / 2f), 0, 0);
                want.right = want.left + hit;
                want.bottom = want.top + hit;
                showFor.set(i, cs.get(i));
            } else {
                showFor.set(i, null);
            }
            if (want.equals(showAt.get(i))) continue;
            showAt.get(i).set(want);
            if (trace) android.util.Log.i("ShieldTrace", "B " + SystemClock.uptimeMillis() + " "
                    + i + " " + want.toShortString() + " scrolling=" + scrolling);
            if (want.isEmpty()) {
                lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                lp.x = -10 * hit;
            } else {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                lp.x = want.left;
                lp.y = want.top;
            }
            try {
                wm.updateViewLayout(v, lp);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static WindowManager.LayoutParams buttonParams(int size) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = -10 * size;
        cutout(lp);
        return lp;
    }

    /**
     * "Show" needs two taps (the second within 3 s): the button sits on top of the app, and a
     * tap meant for the app underneath (a menu, a reply button) must never uncover a spoiler.
     */
    private void onShowTap(Cover c) {
        long now = SystemClock.uptimeMillis();
        if (trace) android.util.Log.i("ShieldTrace", "K " + now + " " + c.key);
        // (By what it covers: a read in between may have replaced the cover object.)
        if (confirming != null && now < confirmUntil && same(confirming, c)) {
            confirming = null;
            reveal(c);
            return;
        }
        confirming = c;
        confirmUntil = now + 3000;
        render();
        main.postDelayed(() -> {
            if (confirming != null && same(confirming, c)
                    && SystemClock.uptimeMillis() >= confirmUntil) {
                confirming = null;
                render();
            }
        }, 3050);
    }

    private static boolean same(Cover a, Cover b) {
        return a == b || (a.key != null && a.key.equals(b.key));
    }

    private int confirmingIndex(List<Cover> drawn) {
        if (confirming == null) return -1;
        for (int i = 0; i < drawn.size(); i++) if (same(drawn.get(i), confirming)) return i;
        return -1;
    }

    /** You confirmed "show": uncover it, and don't cover that text again. */
    private void reveal(Cover c) {
        if (c.key != null) revealed.add(c.key);
        List<Cover> next = new ArrayList<>();
        for (Cover o : covers) if (!same(o, c)) next.add(o);
        covers = next;
        render();
    }

    private void render() {
        if (overlay == null) return;
        List<Rect> drawn = new ArrayList<>(covers.size());
        List<Cover> drawnCovers = new ArrayList<>(covers.size());
        List<String> labels = new ArrayList<>(covers.size());
        List<Rect> plain = new ArrayList<>(2);
        boolean still = !scrolling;
        if (enabled) {
            long now = SystemClock.uptimeMillis();
            boolean anyGlide = false;
            for (Cover c : covers) {
                float gx = c.glideNowX(now), gy = c.glideNowY(now);
                if (gx != 0 || gy != 0) anyGlide = true;
                float top = c.r.top + gy, bottom = c.r.bottom + gy;
                // Stretch towards where the content is heading, by its speed and by how
                // unsure the tracking is right now, so a small error never shows text.
                for (Tracker t : trackers) {
                    if (!t.busy(now) || !movesWith(c.clip, t.c)) continue;
                    still = false;
                    // Capped: right after a big correction the error is briefly large, and
                    // stretching by all of it flashed covers the height of the whole list.
                    float lead = Math.max(-MAX_LEAD_PX, Math.min(MAX_LEAD_PX,
                            t.ty.velocity() * LEAD_MS));
                    float unsure = Math.min(Math.abs(t.ty.error(now)), MAX_UNSURE_PX) + 6;
                    if (lead < 0) top += lead; else bottom += lead;
                    top -= unsure;
                    bottom += unsure;
                    break;
                }
                Rect r = new Rect(Math.round(c.r.left + gx), Math.round(top),
                        Math.round(c.r.right + gx), Math.round(bottom));
                if (c.clip != null && !r.intersect(c.clip)) continue;
                if (!screenMain.isEmpty() && !r.intersect(screenMain)) continue;
                drawn.add(r);
                drawnCovers.add(c);
                if (c.pkg != null && !c.recents) spoilerSeen.put(c.pkg, now);
                labels.add(c.words);
            }
            if (anyGlide && !gliding) {   // keep drawing until the glides finish
                gliding = true;
                Choreographer.getInstance().postFrameCallback(glideFrame);
            }
            if (edgeCurtain && armed) {
                for (Tracker t : trackers) {
                    if (t.blind) {
                        // It moves and nothing says how far: what's in it now is unknown.
                        plain.add(new Rect(t.c));
                        continue;
                    }
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
        overlay.setConfirming(confirmingIndex(drawnCovers));
        overlay.setFrame(drawn, labels, plain);
        placeShowButtons(still ? drawnCovers : null, drawn);
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
                    + "px/s armed=" + (armed ? "Y" : "N") + " shown=" + revealed.size()
                    + (regexTimeouts > 0 ? " regex-timeouts=" + regexTimeouts : ""));
        } else {
            overlay.setDebug(null);
        }
    }
}
