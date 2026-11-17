package dev.mwkov.spoilershield;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates the app from its GitHub releases, in place: no file to find and open. Android
 * still shows its own "Update this app?" screen; the first time it also asks to allow
 * installing apps from Spoiler Shield.
 */
final class Updater {
    private static final String BASE = "https://github.com/mwkov/Gta/releases/latest/download/";

    interface Progress {
        void on(String message);
    }

    /** "16 5.4" from the latest release, or null. Network: call off the main thread. */
    static String[] latest() {
        try {
            String s = new String(read(BASE + "version.txt", 64 * 1024, null), "UTF-8").trim();
            String[] p = s.split("\\s+");
            return p.length >= 2 ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    static long installedCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** True if Android lets us install; otherwise opens the setting to allow it. */
    static boolean canInstall(Activity a) {
        if (a.getPackageManager().canRequestPackageInstalls()) return true;
        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + a.getPackageName())));
        return false;
    }

    /** Downloads the new version straight into an install session. Off the main thread. */
    static void install(Context c, Progress progress) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("SpoilerShield.apk", 0, -1)) {
                HttpURLConnection con = open(BASE + "SpoilerShield.apk");
                long total = con.getContentLengthLong();
                try (InputStream in = con.getInputStream()) {
                    byte[] buf = new byte[16384];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) progress.on("Downloading… " + (100 * done / total) + "%");
                    }
                }
                s.fsync(out);
            }
            Intent i = new Intent(c, InstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent done = PendingIntent.getBroadcast(c, id, i, flags);
            progress.on("Installing…");
            s.commit(done.getIntentSender());
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        // GitHub answers with redirects to its file servers; follow them by hand (https only).
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            int code = con.getResponseCode();
            if (code / 100 == 3) {
                String next = con.getHeaderField("Location");
                con.disconnect();
                if (next == null || !next.startsWith("https://")) break;
                url = next;
                continue;
            }
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            return con;
        }
        throw new java.io.IOException("too many redirects");
    }

    private static byte[] read(String url, int max, Progress p) throws Exception {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < max) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }
}
