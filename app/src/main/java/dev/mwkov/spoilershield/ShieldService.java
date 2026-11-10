package dev.mwkov.spoilershield;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the text on screen (titles, captions, messages, notifications) and draws an
 * opaque cover over anything matching the user's regex. Nothing is stored or sent anywhere.
 */
public class ShieldService extends AccessibilityService {

    private static final long THROTTLE_MS = 60;     // max rescan rate while things change
    private static final long RECHECK_MS = 600;     // re-verify while covers are showing
    private static final int MAX_DEPTH = 60;
    private static final int MAX_CLIMB = 8;
    private static final float CARD_MAX_HEIGHT = 0.6f;  // a "card" is at most 60% of the screen

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private OverlayView overlay;
    private SharedPreferences prefs;

    private volatile Pattern pattern;
    private volatile boolean enabled;
    private volatile boolean coverCard;
    private boolean scanPending;

    private final Rect screen = new Rect();
    private final Rect tmp = new Rect();

    private final Runnable scanRunnable = () -> {
        scanPending = false;
        scan();
    };

    private final Runnable recheckRunnable = () -> scheduleScan(0);

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (p, key) -> {
                loadPrefs();
                scheduleScan(0);
            };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = getSystemService(WindowManager.class);
        prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        loadPrefs();
        addOverlay();
        scheduleScan(0);
    }

    private void loadPrefs() {
        enabled = Prefs.enabled(this);
        coverCard = Prefs.coverCard(this);
        pattern = Prefs.compile(Prefs.pattern(this));
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
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!enabled) return;
        scheduleScan(THROTTLE_MS);
    }

    private void scheduleScan(long delay) {
        if (scanPending) return;
        scanPending = true;
        handler.postDelayed(scanRunnable, delay);
    }

    private void scan() {
        handler.removeCallbacks(recheckRunnable);
        List<Rect> covers = new ArrayList<>();
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
                    scanRoot(safeRoot(w), p, covers);
                }
            } else {
                scanRoot(getRootInActiveWindow(), p, covers);
            }
        }
        if (overlay != null) overlay.setCovers(covers);
        if (!covers.isEmpty()) handler.postDelayed(recheckRunnable, RECHECK_MS);
    }

    private static AccessibilityNodeInfo safeRoot(AccessibilityWindowInfo w) {
        try {
            return w.getRoot();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void scanRoot(AccessibilityNodeInfo root, Pattern p, List<Rect> out) {
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        // Never cover our own settings screen, so the keyword list stays editable.
        if (pkg != null && getPackageName().contentEquals(pkg)) return;
        walk(root, p, out, 0);
    }

    private void walk(AccessibilityNodeInfo node, Pattern p, List<Rect> out, int depth) {
        if (node == null || depth > MAX_DEPTH) return;
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
            AccessibilityNodeInfo child;
            try {
                child = node.getChild(i);
            } catch (RuntimeException e) {
                child = null;
            }
            walk(child, p, out, depth + 1);
        }
    }

    private static boolean matches(Pattern p, CharSequence s) {
        return s != null && s.length() > 0 && p.matcher(s).find();
    }

    /**
     * The matching text's own bounds, or (with "cover whole card") the biggest parent that is
     * still a single item, e.g. a YouTube video card including its thumbnail.
     */
    private Rect coverFor(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (coverCard && !screen.isEmpty()) {
            int maxH = (int) (screen.height() * CARD_MAX_HEIGHT);
            AccessibilityNodeInfo parent = node.getParent();
            for (int i = 0; parent != null && i < MAX_CLIMB; i++) {
                parent.getBoundsInScreen(tmp);
                if (tmp.isEmpty() || tmp.height() > maxH) break;
                r.union(tmp);
                parent = parent.getParent();
            }
        }
        if (!screen.isEmpty() && !r.intersect(screen)) r.setEmpty();
        return r;
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

    @Override
    public void onInterrupt() {
        if (overlay != null) overlay.setCovers(new ArrayList<>());
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
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
