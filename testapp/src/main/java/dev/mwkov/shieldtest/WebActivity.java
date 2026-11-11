package dev.mwkov.shieldtest;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * A long web page (like a news site or search results). The page reports where each GTA
 * line is on every animation frame; it is converted to screen pixels and logged as H lines.
 */
public class WebActivity extends Activity {

    private WebView web;
    private final int[] loc = new int[2];
    private volatile int offX, offY;
    private float density;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.addJavascriptInterface(this, "H");
        StringBuilder h = new StringBuilder("<html><head><meta name='viewport' "
                + "content='width=device-width, initial-scale=1'><style>"
                + "body{font-family:sans-serif;margin:0;padding:8px 16px;background:#fff}"
                + "p{font-size:18px;margin:0;padding:16px 0 106px 0;border-bottom:1px solid #ddd}"
                + ".gta{color:#f00}</style></head><body>");
        for (int i = 0; i < Feed.ROWS; i++) {
            h.append(Feed.isSpoiler(i) ? "<p><span class='gta'>" : "<p><span>")
                    .append(Feed.text(i)).append("</span></p>");
        }
        h.append("<script>function f(){var s='';var e=document.querySelectorAll('.gta');"
                + "for(var i=0;i<e.length;i++){var r=e[i].getBoundingClientRect();"
                + "if(r.bottom<0||r.top>innerHeight)continue;"
                + "s+=r.left+','+r.top+','+r.right+','+r.bottom+';'}H.report(s);"
                + "requestAnimationFrame(f)}requestAnimationFrame(f)</script></body></html>");
        web.loadDataWithBaseURL("https://example.test/", h.toString(), "text/html", "utf-8", null);
        setContentView(web);
        web.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            web.getLocationOnScreen(loc);
            offX = loc[0];
            offY = loc[1];
        });
    }

    @JavascriptInterface
    public void report(String cssRects) {
        long now = SystemClock.uptimeMillis();
        StringBuilder sb = new StringBuilder("H ").append(now).append(' ');
        for (String r : cssRects.split(";")) {
            if (r.isEmpty()) continue;
            String[] p = r.split(",");
            sb.append(Math.round(Float.parseFloat(p[0]) * density) + offX).append(',')
                    .append(Math.round(Float.parseFloat(p[1]) * density) + offY).append(',')
                    .append(Math.round(Float.parseFloat(p[2]) * density) + offX).append(',')
                    .append(Math.round(Float.parseFloat(p[3]) * density) + offY).append(';');
        }
        Log.i("ShieldHarness", sb.toString());
    }
}
