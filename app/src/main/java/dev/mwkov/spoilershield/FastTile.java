package dev.mwkov.spoilershield;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: one tap to start (or stop) fast mode. */
public class FastTile extends TileService {

    @Override
    public void onStartListening() {
        refresh(CaptureService.running);
    }

    @Override
    public void onClick() {
        if (CaptureService.running) {
            startService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_STOP));
            refresh(false);
            return;
        }
        Intent i = new Intent(this, ConsentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }

    private void refresh(boolean on) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Shield fast mode");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(on ? "On" : "Off");
        t.updateTile();
    }
}
