package com.vhanma.ghostshot;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.text.TextUtils;

public class GhostTileService extends TileService {
    private static final String ACTION_CAPTURE = "com.vhanma.ghostshot.CAPTURE";

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(isServiceEnabled() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel("GhostShot");
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        if (isServiceEnabled()) {
            Intent i = new Intent(ACTION_CAPTURE);
            i.setPackage(getPackageName());
            sendBroadcast(i);
            return;
        }

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(
                    this,
                    77,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            startActivityAndCollapse(pi);
        } else {
            startActivityAndCollapse(open);
        }
    }

    private boolean isServiceEnabled() {
        String expected = new ComponentName(this, ScreenshotAccessibilityService.class).flattenToString();
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            if (expected.equalsIgnoreCase(splitter.next())) return true;
        }
        return false;
    }
}
