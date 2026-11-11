package dev.mwkov.spoilershield;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.WindowManager;

/**
 * Fast mode: mirrors the screen into memory so ShieldService can track and read it.
 * Frames are handed straight to ShieldService and never saved or sent anywhere.
 */
public class CaptureService extends Service {

    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String ACTION_STOP = "dev.mwkov.spoilershield.STOP_CAPTURE";
    private static final String CHANNEL = "fast_mode";

    static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private int w, h;
    private boolean shuttingDown;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, notification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(1, notification());
            }
        } catch (RuntimeException e) {
            shutdown();
            return START_NOT_STICKY;
        }
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) {
            shutdown();
            return START_NOT_STICKY;
        }
        releaseCapture();
        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) throw new IllegalStateException("no projection");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    shutdown();   // user stopped it, phone locked, or another app took over
                }
            }, main);
            createDisplay();
            running = true;
        } catch (RuntimeException e) {
            shutdown();
        }
        return START_NOT_STICKY;
    }

    private void readSize() {
        WindowManager wm = getSystemService(WindowManager.class);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            w = b.width();
            h = b.height();
        } else {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
    }

    private ImageReader newReader() {
        ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4);
        r.setOnImageAvailableListener(rd -> {
            Image img;
            try {
                img = rd.acquireLatestImage();
            } catch (RuntimeException e) {
                return;
            }
            if (img == null) return;
            ShieldService s = ShieldService.instance;
            if (s == null) {
                img.close();
                return;
            }
            try {
                s.onFrame(img);   // takes ownership of the image
            } catch (RuntimeException e) {
                // Never let one bad frame take the service down.
            }
        }, main);
        return r;
    }

    private void createDisplay() {
        readSize();
        reader = newReader();
        int dpi = getResources().getDisplayMetrics().densityDpi;
        display = projection.createVirtualDisplay("spoiler-shield", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, main);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        main.post(() -> {
            if (display == null || projection == null) return;
            int ow = w, oh = h;
            readSize();
            if (w == ow && h == oh) return;
            try {
                ShieldService s = ShieldService.instance;
                if (s != null) s.onCaptureStopped();   // drops the frame it was holding
                ImageReader old = reader;
                reader = newReader();
                display.resize(w, h, getResources().getDisplayMetrics().densityDpi);
                display.setSurface(reader.getSurface());
                if (old != null) old.close();
            } catch (RuntimeException e) {
                shutdown();
            }
        });
    }

    private void releaseCapture() {
        ShieldService s = ShieldService.instance;
        if (s != null) s.onCaptureStopped();
        if (display != null) {
            try {
                display.release();
            } catch (RuntimeException ignored) {
            }
            display = null;
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (RuntimeException ignored) {
            }
            reader = null;
        }
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            try {
                p.stop();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        running = false;
        releaseCapture();
        try {
            stopForeground(true);
        } catch (RuntimeException ignored) {
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        shuttingDown = true;
        releaseCapture();
        super.onDestroy();
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Fast mode",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Spoiler Shield fast mode is on")
                .setContentText("Reading the screen on this phone only. Tap Stop to turn off.")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }
}
