package com.vhanma.ghostshot;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String ACTION_SHOW = "com.vhanma.ghostshot.SHOW_BUBBLE";
    private static final String ACTION_HIDE = "com.vhanma.ghostshot.HIDE_BUBBLE";
    private static final String ACTION_ALPHA = "com.vhanma.ghostshot.SET_ALPHA";
    private static final String ACTION_RESET = "com.vhanma.ghostshot.RESET_BUBBLE";

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private LinearLayout buildUi() {
        int pad = dp(22);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(9, 9, 11));

        TextView title = text("GhostShot Widget", 27, Color.WHITE, Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = text("", 14, Color.LTGRAY, Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.setMargins(0, dp(8), 0, dp(14));
        root.addView(status, statusLp);

        TextView info = text("Tap dot = screenshot\nDrag = move\nLong-press = Hide / Close\n\nThe dot disappears during capture, so it stays out of the saved image.", 16, Color.LTGRAY, Gravity.CENTER);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, -2);
        infoLp.setMargins(0, 0, 0, dp(18));
        root.addView(info, infoLp);

        Button enable = button("Enable screenshot access");
        enable.setOnClickListener(v -> {
            getSharedPreferences("ghostshot", MODE_PRIVATE).edit().putBoolean("hidden", false).apply();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        root.addView(enable, fullButtonLp());

        Button show = button("Show floating button");
        show.setOnClickListener(v -> send(ACTION_SHOW, -1f));
        root.addView(show, fullButtonLp());

        Button hide = button("Hide floating button");
        hide.setOnClickListener(v -> send(ACTION_HIDE, -1f));
        root.addView(hide, fullButtonLp());

        Button reset = button("Reset button position");
        reset.setOnClickListener(v -> send(ACTION_RESET, -1f));
        root.addView(reset, fullButtonLp());

        int saved = getSharedPreferences("ghostshot", MODE_PRIVATE).getInt("alphaPct", 8);
        TextView opacityLabel = text("Button visibility: " + saved + "%", 15, Color.LTGRAY, Gravity.START);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
        labelLp.setMargins(0, dp(18), 0, dp(4));
        root.addView(opacityLabel, labelLp);

        SeekBar opacity = new SeekBar(this);
        opacity.setMax(48);
        opacity.setProgress(Math.max(0, Math.min(48, saved - 2)));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int pct = progress + 2;
                opacityLabel.setText("Button visibility: " + pct + "%");
                getSharedPreferences("ghostshot", MODE_PRIVATE).edit().putInt("alphaPct", pct).apply();
                send(ACTION_ALPHA, pct / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(opacity, new LinearLayout.LayoutParams(-1, -2));

        TextView note = text("Screenshots save as PNG files in Pictures/Screenshots. Closing the widget from its long-press menu disables the accessibility service completely.", 13, Color.GRAY, Gravity.START);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.setMargins(0, dp(18), 0, 0);
        root.addView(note, noteLp);

        return root;
    }

    private void refreshStatus() {
        if (status == null) return;
        status.setText(isServiceEnabled() ? "Screenshot access: ON" : "Screenshot access: OFF");
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

    private TextView text(String value, float size, int color, int gravity) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setGravity(gravity);
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setMinHeight(dp(48));
        return b;
    }

    private LinearLayout.LayoutParams fullButtonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(5), 0, dp(5));
        return lp;
    }

    private void send(String action, float alpha) {
        Intent i = new Intent(action);
        i.setPackage(getPackageName());
        if (alpha >= 0f) i.putExtra("alpha", alpha);
        sendBroadcast(i);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
