package dev.mwkov.spoilershield;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Settings shared by the app screen, the accessibility service and the quick tile. */
final class Prefs {
    static final String FILE = "shield";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_COVER_CARD = "cover_card";
    static final String KEY_PATTERN = "pattern";

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

    static String pattern(Context c) {
        return get(c).getString(KEY_PATTERN, DEFAULT_PATTERN);
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
