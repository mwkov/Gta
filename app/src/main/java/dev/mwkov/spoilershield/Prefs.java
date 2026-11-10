package dev.mwkov.spoilershield;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Settings shared by the app screen, the accessibility service and the quick tile. */
final class Prefs {
    static final String FILE = "shield";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_COVER_CARD = "cover_card";
    static final String KEY_PATTERN = "pattern";
    static final String KEY_IGNORED = "ignored_apps";
    static final String KEY_SCROLL_BLACKOUT = "scroll_blackout";

    static final String DEFAULT_PATTERN =
            "gta|grand theft auto|rockstar|leonida|vice city|port gellhorn|grassrivers|mount kalaga"
            + "|\\bjason\\b|\\blucia\\b"
            + "|гта|рокстар|вайс[- ]?сити|джейсон|лючи[яи]|люси[яи]|луси[яи]";

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return get(c).getBoolean(KEY_ENABLED, true);
    }

    static boolean coverCard(Context c) {
        return get(c).getBoolean(KEY_COVER_CARD, true);
    }

    static boolean scrollBlackout(Context c) {
        return get(c).getBoolean(KEY_SCROLL_BLACKOUT, true);
    }

    static String pattern(Context c) {
        return get(c).getString(KEY_PATTERN, DEFAULT_PATTERN);
    }

    /** Apps the shield leaves alone. Claude is excluded by default (you talk about GTA there). */
    static Set<String> ignoredApps(Context c) {
        Set<String> s = get(c).getStringSet(KEY_IGNORED, null);
        if (s == null) {
            s = new HashSet<>();
            s.add("com.anthropic.claude");
        }
        return new HashSet<>(s);
    }

    static void setIgnoredApps(Context c, Set<String> apps) {
        get(c).edit().putStringSet(KEY_IGNORED, new HashSet<>(apps)).apply();
    }

    /** Case-insensitive for both Latin and Cyrillic. Returns null if the regex is invalid or empty. */
    static Pattern compile(String regex) {
        if (regex == null || regex.trim().isEmpty()) return null;
        try {
            return Pattern.compile(regex.trim(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }
}
