package dev.mwkov.spoilershield;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile to pause / resume the shield in one tap. */
public class ShieldTile extends TileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        boolean on = !Prefs.enabled(this);
        Prefs.get(this).edit().putBoolean(Prefs.KEY_ENABLED, on).apply();
        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = Prefs.enabled(this);
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Spoiler Shield");
        if (android.os.Build.VERSION.SDK_INT >= 29) t.setSubtitle(on ? "On" : "Paused");
        t.updateTile();
    }
}
