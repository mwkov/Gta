package dev.mwkov.spoilershield;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Single settings screen, built in code so the app has no resource dependencies. */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0E0B14;
    private static final int CARD = 0xFF1B1626;
    private static final int ACCENT = 0xFF9B6BFF;
    private static final int TEXT = 0xFFECE6F7;
    private static final int MUTED = 0xFF9A90AD;
    private static final int OK = 0xFF5BE49B;
    private static final int BAD = 0xFFFF6B8A;

    private TextView status;
    private Button openSettings;
    private TextView ignoredSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, dp(28), pad, dp(40));
        scroll.addView(col);

        TextView title = text("Spoiler Shield", 28, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        col.addView(text("Covers anything on screen that matches your keywords.", 14, MUTED));

        // --- Status card
        LinearLayout statusCard = card(col);
        status = text("", 16, TEXT);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        statusCard.addView(status);
        openSettings = button("Open Accessibility settings");
        openSettings.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        statusCard.addView(openSettings);
        TextView help = text(
                "First time setup:\n"
                        + "1. Tap the button above → Downloaded apps (or Installed services) → "
                        + "Spoiler Shield → turn it on.\n"
                        + "2. If it's greyed out (\"Restricted setting\"): go to Settings → Apps → "
                        + "Spoiler Shield → ⋮ menu top-right → Allow restricted settings, then try again.\n"
                        + "3. In Apps → Spoiler Shield → Battery, allow background activity so "
                        + "ColorOS doesn't kill it.", 13, MUTED);
        help.setPadding(0, dp(10), 0, 0);
        statusCard.addView(help);

        // --- Switches
        LinearLayout switches = card(col);
        Switch enabled = toggle("Shield on", Prefs.enabled(this));
        enabled.setOnCheckedChangeListener((b, on) ->
                Prefs.get(this).edit().putBoolean(Prefs.KEY_ENABLED, on).apply());
        switches.addView(enabled);
        switches.addView(text("Also on your Quick Settings panel: add the \"Spoiler Shield\" tile "
                + "to pause it in one tap.", 12, MUTED));
        Switch card = toggle("Cover the whole item (thumbnail too)", Prefs.coverCard(this));
        card.setOnCheckedChangeListener((b, on) ->
                Prefs.get(this).edit().putBoolean(Prefs.KEY_COVER_CARD, on).apply());
        switches.addView(card);
        switches.addView(text("Covers the whole video/post/message the text is in. "
                + "Off = only the matching text gets covered.", 12, MUTED));
        Switch blackout = toggle("Edge curtain while scrolling", Prefs.scrollBlackout(this));
        blackout.setOnCheckedChangeListener((b, on) ->
                Prefs.get(this).edit().putBoolean(Prefs.KEY_SCROLL_BLACKOUT, on).apply());
        switches.addView(blackout);
        switches.addView(text("On pages with spoilers, the strip of new content sliding in is "
                + "covered until it has been checked (a fraction of a second). Faster scroll = "
                + "taller strip.", 12, MUTED));

        Switch dbg = toggle("Show debug numbers", Prefs.debug(this));
        dbg.setOnCheckedChangeListener((b, on) ->
                Prefs.get(this).edit().putBoolean(Prefs.KEY_DEBUG, on).apply());
        switches.addView(dbg);
        switches.addView(text("Green text at the top of the screen with read speed and scroll "
                + "data. Turn on when recording a video for tuning.", 12, MUTED));

        Button dump = button("Save what the shield sees (in 8 s)");
        dump.setOnClickListener(v -> {
            Prefs.get(this).edit()
                    .putLong(Prefs.KEY_DUMP_AT, System.currentTimeMillis() + 8000).apply();
            android.widget.Toast.makeText(this, "Go to the screen with the missed text now. "
                    + "Saving to Downloads in 8 seconds.", android.widget.Toast.LENGTH_LONG).show();
        });
        switches.addView(dump, matchWrap(dp(10)));
        switches.addView(text("For text that isn't covered: saves every piece of text the shield "
                + "can read on that screen to a file in Downloads, to send for a fix.", 12, MUTED));

        // --- Ignored apps
        LinearLayout ign = card(col);
        TextView ignTitle = text("Ignored apps", 15, TEXT);
        ignTitle.setTypeface(Typeface.DEFAULT_BOLD);
        ign.addView(ignTitle);
        ignoredSummary = text("", 13, MUTED);
        ign.addView(ignoredSummary);
        Button pick = button("Choose apps to ignore");
        pick.setOnClickListener(v -> showAppPicker());
        ign.addView(pick, matchWrap(dp(10)));
        refreshIgnoredSummary();

        // --- Keywords
        LinearLayout kw = card(col);
        TextView kwTitle = text("Keywords (regex, | between words)", 15, TEXT);
        kwTitle.setTypeface(Typeface.DEFAULT_BOLD);
        kw.addView(kwTitle);
        kw.addView(text("Capitals don't matter, English and Russian both work. "
                + "\\b...\\b = whole word only.", 12, MUTED));
        EditText input = new EditText(this);
        input.setText(Prefs.pattern(this));
        input.setTextColor(TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackgroundColor(0xFF120E1A);
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        kw.addView(input, matchWrap(dp(10)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button save = button("Save");
        Button reset = button("Reset to default");
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.setMargins(0, dp(10), dp(6), 0);
        row.addView(save, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half2.setMargins(dp(6), dp(10), 0, 0);
        row.addView(reset, half2);
        kw.addView(row);

        save.setOnClickListener(v -> {
            String s = input.getText().toString().replace("\n", "").trim();
            if (Prefs.compile(s) == null) {
                Toast.makeText(this, "That regex has an error — not saved", Toast.LENGTH_LONG).show();
                return;
            }
            Prefs.get(this).edit().putString(Prefs.KEY_PATTERN, s).apply();
            input.setText(s);
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        });
        reset.setOnClickListener(v -> {
            Prefs.get(this).edit().putString(Prefs.KEY_PATTERN, Prefs.DEFAULT_PATTERN).apply();
            input.setText(Prefs.DEFAULT_PATTERN);
            Toast.makeText(this, "Default keywords restored", Toast.LENGTH_SHORT).show();
        });

        col.addView(text("Can't see text inside images or videos. Everything runs on your phone; "
                + "nothing is saved or sent.", 12, MUTED), matchWrap(dp(18)));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = serviceEnabled();
        status.setText(on ? "● Accessibility service is ON" : "● Accessibility service is OFF");
        status.setTextColor(on ? OK : BAD);
        openSettings.setText(on ? "Accessibility settings" : "Turn it on in Accessibility settings");
    }

    private void refreshIgnoredSummary() {
        java.util.Set<String> ign = Prefs.ignoredApps(this);
        if (ign.isEmpty()) {
            ignoredSummary.setText("None — the shield works in every app.");
            return;
        }
        PackageManager pm = getPackageManager();
        List<String> names = new ArrayList<>();
        for (String p : ign) {
            try {
                names.add(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString());
            } catch (PackageManager.NameNotFoundException e) {
                names.add(p);
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        ignoredSummary.setText("Never covered in: " + String.join(", ", names));
    }

    private void showAppPicker() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(launcher, 0);
        Map<String, String> apps = new TreeMap<>(String.CASE_INSENSITIVE_ORDER); // label -> pkg
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            apps.put(ri.loadLabel(pm).toString() + "\u0000" + pkg, pkg);
        }
        int n = apps.size();
        String[] labels = new String[n];
        String[] pkgs = new String[n];
        boolean[] checked = new boolean[n];
        java.util.Set<String> current = Prefs.ignoredApps(this);
        int i = 0;
        for (Map.Entry<String, String> e : apps.entrySet()) {
            labels[i] = e.getKey().substring(0, e.getKey().indexOf('\u0000'));
            pkgs[i] = e.getValue();
            checked[i] = current.contains(pkgs[i]);
            i++;
        }
        new AlertDialog.Builder(this)
                .setTitle("Don't cover anything in…")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Save", (d, w) -> {
                    java.util.Set<String> next = new java.util.HashSet<>();
                    for (int k = 0; k < n; k++) if (checked[k]) next.add(pkgs[k]);
                    // Keep ignored apps that aren't in the launcher list (e.g. uninstalled ones).
                    for (String p : current) if (!apps.containsValue(p)) next.add(p);
                    Prefs.setIgnoredApps(this, next);
                    refreshIgnoredSummary();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean serviceEnabled() {
        String list = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName me = new ComponentName(this, ShieldService.class);
        for (String s : list.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (me.equals(c)) return true;
        }
        return false;
    }

    // ---- tiny UI helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(ACCENT);
        return b;
    }

    private Switch toggle(String s, boolean checked) {
        Switch sw = new Switch(this);
        sw.setText(s);
        sw.setTextColor(TEXT);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sw.setChecked(checked);
        sw.setPadding(0, dp(10), 0, dp(4));
        return sw;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(CARD);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        parent.addView(c, matchWrap(dp(16)));
        return c;
    }

    private LinearLayout.LayoutParams matchWrap(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }
}
