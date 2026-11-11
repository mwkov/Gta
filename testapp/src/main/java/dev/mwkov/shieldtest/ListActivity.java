package dev.mwkov.shieldtest;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/**
 * A recycling list (rows are reused while scrolling, like most apps). Every frame it logs
 * the exact screen rectangle of each visible GTA line:  H <uptimeMs> l,t,r,b;...
 */
public class ListActivity extends Activity {

    private ListView list;
    private final int[] loc = new int[2];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        list = new ListView(this);
        list.setBackgroundColor(Color.WHITE);
        list.setAdapter(new BaseAdapter() {
            public int getCount() { return Feed.ROWS; }
            public Object getItem(int i) { return i; }
            public long getItemId(int i) { return i; }

            public View getView(int i, View v, ViewGroup parent) {
                LinearLayout row = (LinearLayout) v;
                if (row == null) {
                    row = new LinearLayout(ListActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    int p = dp(16);
                    row.setPadding(p, p, p, p);
                    TextView title = new TextView(ListActivity.this);
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                    row.addView(title);
                    View pic = new View(ListActivity.this);
                    row.addView(pic, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(90)));
                }
                TextView title = (TextView) row.getChildAt(0);
                title.setText(Feed.text(i));
                boolean s = Feed.isSpoiler(i);
                title.setTextColor(s ? Color.RED : Color.DKGRAY);
                title.setTag(s ? "gta" : null);
                row.getChildAt(1).setBackgroundColor(s ? 0xFFFFB0B0 : 0xFFDDE6EE);
                return row;
            }
        });
        setContentView(list);
        Choreographer.getInstance().postFrameCallback(this::frame);
    }

    private void frame(long nanos) {
        StringBuilder sb = new StringBuilder("H ").append(SystemClock.uptimeMillis()).append(' ');
        // Only the part inside the list can be seen (the list clips its rows).
        list.getLocationOnScreen(loc);
        int clipTop = loc[1], clipBottom = loc[1] + list.getHeight();
        for (int i = 0; i < list.getChildCount(); i++) {
            ViewGroup row = (ViewGroup) list.getChildAt(i);
            TextView t = (TextView) row.getChildAt(0);
            if (!"gta".equals(t.getTag())) continue;
            t.getLocationOnScreen(loc);
            int top = Math.max(loc[1], clipTop), bottom = Math.min(loc[1] + t.getHeight(), clipBottom);
            if (bottom <= top) continue;
            sb.append(loc[0]).append(',').append(top).append(',')
                    .append(loc[0] + t.getWidth()).append(',').append(bottom).append(';');
        }
        Log.i("ShieldHarness", sb.toString());
        if (!isFinishing()) Choreographer.getInstance().postFrameCallback(this::frame);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
