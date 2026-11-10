package dev.mwkov.spoilershield;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
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
 * Reads the text on screen (titles, captions, messages, notifications) and draws an
 * opaque cover over anything matching the user's regex. Nothing is stored or sent anywhere.
 *
 * Speed strategy:
 *  - Reading the screen is slow (the app answers piece by piece), so it runs on a background
 *    thread and never blocks drawing.
 *  - Scroll events carry the scroll distance, so existing covers move instantly with the
 *    content, and results of a read that was overtaken by scrolling get corrected.
 *  - "Blackout while scrolling": a scrolling area that has spoilers in it is covered entirely
 *    until scrolling stops and a fresh read has finished, so new rows can't flash past.
 */
public class ShieldService extends AccessibilityService {

    private static final long THROTTLE_MS = 30;      // min gap between reads while things change
    private static final long RECHECK_MS = 500;      // re-verify while covers are showing
    private static final long SCROLL_IDLE_MS = 180;  // no scroll events for this long = stopped
    private static final int MAX_DEPTH = 120;
    private static final int MAX_CLIMB = 8;
    private static final float CARD_MAX_HEIGHT = 0.5f;  // a list item is at most half the screen

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
    private volatile boolean scrollBlackout;
    private volatile Set<String> ignored = new HashSet<>();

    // Background read state
    private final AtomicBoolean scanPending = new AtomicBoolean();
    private final AtomicLong shiftSeq = new AtomicLong();
    private final Rect screen = new Rect();   // scan thread only
    private final Rect tmp = new Rect();      // scan thread only

    // Main-thread state
    private List<Rect> covers = new ArrayList<>();
    private final List<Shift> shifts = new ArrayList<>();
    private final Rect blackout = new Rect();
    private boolean blackoutActive;
    private long lastScrollAt;
    private long idleAt;   // when scrolling stopped; a read that starts after this clears blackout

    private static final class Shift {
        final long seq;
        final Rect container;
        final int dx, dy;

        Shift(long seq, Rect container, int dx, int dy) {
            this.seq = seq;
            this.container = container;
            this.dx = dx;
            this.dy = dy;
        }
    }

    private final Runnable scanRunnable = this::backgroundScan;
    private final Runnable recheckRunnable = () -> scheduleScan(0);
    private final Runnable idleCheck = this::onScrollMaybeIdle;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (p, key) -> {
                loadPrefs();
                if (!enabled) {
                    blackoutActive = false;
                    covers = new ArrayList<>();
                    render();
                }
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
        scrollBlackout = Prefs.scrollBlackout(this);
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
            // New screen / app: old covers and blackout no longer apply.
            if (blackoutActive) {
                blackoutActive = false;
                render();
            }
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

        int dx = 0, dy = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            dx = event.getScrollDeltaX();
            dy = event.getScrollDeltaY();
            if (dx == -1) dx = 0;   // -1 = not reported by the app
            if (dy == -1) dy = 0;
        }

        boolean changed = false;
        if (dx != 0 || dy != 0) {
            // Move existing covers with the content right away.
            long seq = shiftSeq.incrementAndGet();
            shifts.add(new Shift(seq, container, dx, dy));
            for (Rect r : covers) {
                if (Rect.intersects(container, r)) {
                    r.offset(-dx, -dy);
                    changed = true;
                }
            }
        }

        if (scrollBlackout && (blackoutActive || anyCoverIn(container))) {
            blackout.set(container);
            blackoutActive = true;
            changed = true;
        }

        lastScrollAt = SystemClock.uptimeMillis();
        idleAt = 0;
        main.removeCallbacks(idleCheck);
        main.postDelayed(idleCheck, SCROLL_IDLE_MS);
        if (changed) render();
    }

    private boolean anyCoverIn(Rect container) {
        for (Rect r : covers) if (Rect.intersects(container, r)) return true;
        return false;
    }

    private void onScrollMaybeIdle() {
        long now = SystemClock.uptimeMillis();
        if (now - lastScrollAt < SCROLL_IDLE_MS) {
            main.postDelayed(idleCheck, SCROLL_IDLE_MS - (now - lastScrollAt));
            return;
        }
        idleAt = now;
        scheduleScan(0);
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
        final long startTime = SystemClock.uptimeMillis();
        final List<Rect> found = new ArrayList<>();
        Pattern p = pattern;
        if (enabled && p != null) {
            updateScreenBounds();
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
        main.post(() -> applyScan(found, startSeq, startTime));
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

    private void scanRoot(AccessibilityNodeInfo root, Pattern p, List<Rect> out) {
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        if (pkg != null) {
            String name = pkg.toString();
            // Never cover our own settings screen, so the keyword list stays editable.
            if (name.equals(getPackageName()) || ignored.contains(name)) return;
        }
        walk(root, p, out, 0);
    }

    private void walk(AccessibilityNodeInfo node, Pattern p, List<Rect> out, int depth) {
        if (node == null || depth > MAX_DEPTH) return;
        try {
            if (!node.isVisibleToUser()) return;

            if (!node.isEditable() && (matches(p, node.getText())
                    || matches(p, node.getContentDescription())
                    || (Build.VERSION.SDK_INT >= 30 && matches(p, node.getStateDescription())))) {
                Rect r = coverFor(node);
                if (!r.isEmpty()) out.add(r);
                return; // everything inside is already covered
            }

            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo child = Build.VERSION.SDK_INT >= 33
                        ? node.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST)
                        : node.getChild(i);
                walk(child, p, out, depth + 1);
            }
        } catch (RuntimeException ignored) {
            // Node went stale mid-read (screen changed); the next read will catch up.
        }
    }

    private static boolean matches(Pattern p, CharSequence s) {
        return s != null && s.length() > 0 && p.matcher(s).find();
    }

    /**
     * The matching text's own bounds. With "cover whole item" on, and only when the text sits
     * inside a list item (a video card, a message, a post), the whole item is covered so the
     * thumbnail goes too. Headers, toolbars and other non-list text only get the text covered.
     */
    private Rect coverFor(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (coverCard && !screen.isEmpty()) {
            int maxH = (int) (screen.height() * CARD_MAX_HEIGHT);
            AccessibilityNodeInfo cur = node;
            for (int i = 0; i < MAX_CLIMB; i++) {
                AccessibilityNodeInfo parent = cur.getParent();
                if (parent == null) break;
                if (isList(parent)) {
                    // `cur` is one item of a list: cover it if it's a sensible size.
                    cur.getBoundsInScreen(tmp);
                    if (!tmp.isEmpty() && tmp.height() <= maxH) r.union(tmp);
                    break;
                }
                cur = parent;
            }
        }
        if (!screen.isEmpty() && !r.intersect(screen)) r.setEmpty();
        return r;
    }

    private static boolean isList(AccessibilityNodeInfo n) {
        return n.isScrollable() || n.getCollectionInfo() != null;
    }

    private void updateScreenBounds() {
        if (wm == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            screen.set(wm.getMaximumWindowMetrics().getBounds());
        } else {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screen.set(0, 0, dm.widthPixels, dm.heightPixels);
        }
    }

    // ------------------------------------------------------------------ results (main thread)

    private void applyScan(List<Rect> found, long startSeq, long startTime) {
        // The content may have scrolled while we were reading: shift the results to match.
        for (Shift s : shifts) {
            if (s.seq <= startSeq) continue;
            for (Rect r : found) {
                if (Rect.intersects(s.container, r)) r.offset(-s.dx, -s.dy);
            }
        }
        for (Iterator<Shift> it = shifts.iterator(); it.hasNext(); ) {
            if (it.next().seq <= startSeq) it.remove();
        }

        covers = found;
        if (!enabled) blackoutActive = false;
        if (blackoutActive && idleAt > 0 && startTime >= idleAt) {
            // Scrolling stopped and this read began afterwards: the exact covers are reliable.
            blackoutActive = false;
            idleAt = 0;
        }
        render();

        main.removeCallbacks(recheckRunnable);
        if (!covers.isEmpty() || blackoutActive) main.postDelayed(recheckRunnable, RECHECK_MS);
    }

    private void render() {
        if (overlay == null) return;
        List<Rect> all = new ArrayList<>(covers.size() + 1);
        for (Rect r : covers) all.add(new Rect(r));
        if (blackoutActive) all.add(new Rect(blackout));
        overlay.setCovers(all);
    }

    @Override
    public void onInterrupt() {
        blackoutActive = false;
        covers = new ArrayList<>();
        render();
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
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
