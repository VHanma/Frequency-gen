package com.vhanma.ghostshot;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class WidgetSkinActivity extends Activity {
    private static final int REQ_IMAGE = 901;
    private static final String ACTION_REFRESH = "com.vhanma.ghostshot.REFRESH";
    private SharedPreferences prefs;
    private EditText textInput;
    private TextView imageState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ghostshot", MODE_PRIVATE);
        setContentView(buildUi());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            prefs.edit().putString("widgetImageUri", uri.toString()).apply();
            refreshState();
            notifyService();
        }
    }

    private LinearLayout buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(Color.rgb(7, 7, 10));

        TextView title = new TextView(this);
        title.setText("Widget Skin");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title, full());

        textInput = new EditText(this);
        textInput.setHint("Type anything for the button");
        textInput.setHintTextColor(Color.DKGRAY);
        textInput.setTextColor(Color.WHITE);
        textInput.setSingleLine(false);
        textInput.setMaxLines(2);
        textInput.setText(prefs.getString("widgetText", ""));
        root.addView(textInput, full());

        Button saveText = button("Save text");
        saveText.setOnClickListener(v -> {
            prefs.edit().putString("widgetText", textInput.getText().toString()).apply();
            notifyService();
            Toast.makeText(this, "Text updated", Toast.LENGTH_SHORT).show();
        });
        root.addView(saveText, full());

        LinearLayout imageRow = row();
        Button choose = button("Choose image");
        Button clear = button("Clear image");
        choose.setOnClickListener(v -> chooseImage());
        clear.setOnClickListener(v -> {
            prefs.edit().remove("widgetImageUri").apply();
            refreshState();
            notifyService();
        });
        imageRow.addView(choose, weighted());
        imageRow.addView(clear, weighted());
        root.addView(imageRow, full());

        imageState = label("");
        root.addView(imageState, full());

        addSlider(root, "Text size", 7, 24, prefs.getInt("widgetTextSizeSp", 10), "widgetTextSizeSp", " sp");
        addSlider(root, "Image strength", 10, 100, prefs.getInt("widgetImageAlphaPct", 100), "widgetImageAlphaPct", "%");

        CheckBox crop = check("Crop image to fill widget", prefs.getBoolean("imageCrop", true));
        crop.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("imageCrop", checked).apply();
            notifyService();
        });
        root.addView(crop, full());

        CheckBox base = check("Keep ghost-dot background", prefs.getBoolean("showBaseDot", true));
        base.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("showBaseDot", checked).apply();
            notifyService();
        });
        root.addView(base, full());

        TextView note = label("Image and text can be used together. They inherit the widget's active/idle opacity, drag position, edge snap, and screenshot self-hide.");
        root.addView(note, full());

        refreshState();
        return root;
    }

    private void chooseImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_IMAGE);
    }

    private void refreshState() {
        if (imageState != null) {
            imageState.setText(prefs.getString("widgetImageUri", null) == null ? "Image: none" : "Image: selected");
        }
    }

    private void addSlider(LinearLayout root, String name, int min, int max, int current, String key, String suffix) {
        TextView label = label(name + ": " + current + suffix);
        root.addView(label, full());
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, current - min)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int value = progress + min;
                prefs.edit().putInt(key, value).apply();
                label.setText(name + ": " + value + suffix);
                notifyService();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(bar, full());
    }

    private void notifyService() {
        Intent i = new Intent(ACTION_REFRESH);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private CheckBox check(String text, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextColor(Color.LTGRAY);
        c.setChecked(checked);
        return c;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.LTGRAY);
        t.setTextSize(14);
        t.setPadding(0, dp(7), 0, dp(7));
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, dp(50), 1f);
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
