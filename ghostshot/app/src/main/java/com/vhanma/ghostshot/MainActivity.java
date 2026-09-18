package com.vhanma.ghostshot;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String ACTION_SHOW = "com.vhanma.ghostshot.SHOW_BUBBLE";
    private static final String ACTION_HIDE = "com.vhanma.ghostshot.HIDE_BUBBLE";
    private static final String ACTION_RESET = "com.vhanma.ghostshot.RESET_BUBBLE";
    private static final String ACTION_CAPTURE = "com.vhanma.ghostshot.CAPTURE";
    private static final String ACTION_REFRESH = "com.vhanma.ghostshot.REFRESH";

    private SharedPreferences prefs;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ghostshot", MODE_PRIVATE);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private View buildUi() {
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7, 7, 10));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad, pad, dp(34));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("GhostShot Omega", 27, Color.WHITE, Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = text("", 14, Color.LTGRAY, Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.setMargins(0, dp(6), 0, dp(12));
        root.addView(status, statusLp);

        TextView info = text(
                "Tap = screenshot   •   Drag = move   •   Long-press = controls\n" +
                "The dot removes itself from the frame before Android captures the screen.",
                15, Color.LTGRAY, Gravity.CENTER);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, -2);
        infoLp.setMargins(0, 0, 0, dp(16));
        root.addView(info, infoLp);

        Button enable = button("Enable screenshot access");
        enable.setOnClickListener(v -> {
            prefs.edit().putBoolean("hidden", false).apply();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        root.addView(enable, fullButtonLp());

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setGravity(Gravity.CENTER);
        Button show = button("Show");
        Button hide = button("Hide");
        Button capture = button("Capture now");
        quick.addView(show, weightLp());
        quick.addView(hide, weightLp());
        quick.addView(capture, weightLp());
        show.setOnClickListener(v -> send(ACTION_SHOW));
        hide.setOnClickListener(v -> send(ACTION_HIDE));
        capture.setOnClickListener(v -> send(ACTION_CAPTURE));
        root.addView(quick, new LinearLayout.LayoutParams(-1, -2));

        Button reset = button("Reset floating button position");
        reset.setOnClickListener(v -> send(ACTION_RESET));
        root.addView(reset, fullButtonLp());

        root.addView(section("STEALTH"));

        int activePct = prefs.getInt("activePct", prefs.getInt("alphaPct", 10));
        TextView activeLabel = label("Touch visibility: " + activePct + "%");
        root.addView(activeLabel);
        SeekBar active = seek(2, 70, activePct);
        active.setOnSeekBarChangeListener(listener(value -> {
            prefs.edit().putInt("activePct", value).apply();
            activeLabel.setText("Touch visibility: " + value + "%");
            refreshService();
        }, 2));
        root.addView(active, new LinearLayout.LayoutParams(-1, -2));

        int idlePct = prefs.getInt("idlePct", 1);
        TextView idleLabel = label("Idle visibility: " + idlePct + "%");
        root.addView(idleLabel);
        SeekBar idle = seek(0, 20, idlePct);
        idle.setOnSeekBarChangeListener(listener(value -> {
            prefs.edit().putInt("idlePct", value).apply();
            idleLabel.setText("Idle visibility: " + value + "%");
            refreshService();
        }, 0));
        root.addView(idle, new LinearLayout.LayoutParams(-1, -2));

        CheckBox autoFade = check("Auto-fade after touch", prefs.getBoolean("autoFade", true));
        autoFade.setOnCheckedChangeListener((b, checked) -> saveBool("autoFade", checked));
        root.addView(autoFade);

        CheckBox snap = check("Magnet-snap to nearest screen edge", prefs.getBoolean("snapEdge", true));
        snap.setOnCheckedChangeListener((b, checked) -> saveBool("snapEdge", checked));
        root.addView(snap);

        int sizeDp = prefs.getInt("sizeDp", 24);
        TextView sizeLabel = label("Touch target size: " + sizeDp + " dp");
        root.addView(sizeLabel);
        SeekBar size = seek(18, 48, sizeDp);
        size.setOnSeekBarChangeListener(listener(value -> {
            prefs.edit().putInt("sizeDp", value).apply();
            sizeLabel.setText("Touch target size: " + value + " dp");
            refreshService();
        }, 18));
        root.addView(size, new LinearLayout.LayoutParams(-1, -2));

        root.addView(section("CAPTURE"));

        CheckBox haptics = check("Tiny vibration when a shot saves", prefs.getBoolean("haptics", true));
        haptics.setOnCheckedChangeListener((b, checked) -> saveBool("haptics", checked));
        root.addView(haptics);

        CheckBox silent = check("Silent mode: no success toast", prefs.getBoolean("silent", false));
        silent.setOnCheckedChangeListener((b, checked) -> saveBool("silent", checked));
        root.addView(silent);

        CheckBox hideAfter = check("Hide dot after every screenshot", prefs.getBoolean("hideAfter", false));
        hideAfter.setOnCheckedChangeListener((b, checked) -> saveBool("hideAfter", checked));
        root.addView(hideAfter);

        TextView formatLabel = label("Image format");
        root.addView(formatLabel);
        Spinner format = new Spinner(this);
        String[] formats = {"PNG • lossless", "JPEG • smaller file"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, formats);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        format.setAdapter(adapter);
        format.setSelection("jpeg".equals(prefs.getString("format", "png")) ? 1 : 0, false);
        format.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String next = position == 1 ? "jpeg" : "png";
                if (!next.equals(prefs.getString("format", "png"))) {
                    prefs.edit().putString("format", next).apply();
                    refreshService();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        root.addView(format, new LinearLayout.LayoutParams(-1, dp(52)));

        TextView note = text(
                "OMEGA GHOST MODE\nSet Idle visibility to 0% and leave Auto-fade on. The touch target remains active even when the dot is invisible. If you lose it, open this app and press Reset or Show.\n\nScreenshots save in Pictures/Screenshots. Secure Android windows can still block capture at the operating-system level.",
                13, Color.GRAY, Gravity.START);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.setMargins(0, dp(18), 0, 0);
        root.addView(note, noteLp);

        return scroll;
    }

    private interface IntConsumer { void accept(int value); }

    private SeekBar.OnSeekBarChangeListener listener(IntConsumer consumer, int min) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) consumer.accept(progress + min);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private SeekBar seek(int min, int max, int value) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        return bar;
    }

    private CheckBox check(String title, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(title);
        c.setTextColor(Color.LTGRAY);
        c.setTextSize(15);
        c.setChecked(checked);
        c.setMinHeight(dp(46));
        return c;
    }

    private void saveBool(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
        refreshService();
    }

    private void refreshService() {
        send(ACTION_REFRESH);
    }

    private void refreshStatus() {
        if (status == null) return;
        status.setText(isServiceEnabled() ? "Screenshot access: ON" : "Screenshot access: OFF");
        status.setTextColor(isServiceEnabled() ? Color.rgb(120, 230, 150) : Color.rgb(255, 150, 150));
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

    private TextView section(String value) {
        TextView t = text(value, 13, Color.WHITE, Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(20), 0, dp(9));
        t.setLayoutParams(lp);
        return t;
    }

    private TextView label(String value) {
        TextView t = text(value, 14, Color.LTGRAY, Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, dp(1));
        t.setLayoutParams(lp);
        return t;
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
        b.setTextSize(14);
        b.setMinHeight(dp(46));
        return b;
    }

    private LinearLayout.LayoutParams fullButtonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        return lp;
    }

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        return lp;
    }

    private void send(String action) {
        Intent i = new Intent(action);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
