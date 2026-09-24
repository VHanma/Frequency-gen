package com.vhanma.ghostshot;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

public class WidgetSkinView extends FrameLayout {
    private final ImageView image;
    private final TextView label;

    public WidgetSkinView(Context context) {
        super(context);
        setClipChildren(true);
        setClipToPadding(true);

        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(image, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        label = new TextView(context);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(Color.WHITE);
        label.setShadowLayer(3f, 0f, 1f, Color.BLACK);
        label.setMaxLines(2);
        label.setPadding(dp(2), dp(1), dp(2), dp(1));
        addView(label, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        refreshSkin();
    }

    public void refreshSkin() {
        SharedPreferences p = getContext().getSharedPreferences("ghostshot", Context.MODE_PRIVATE);

        boolean showBase = p.getBoolean("showBaseDot", true);
        setBackgroundResource(showBase ? R.drawable.ghost_dot : 0);

        String text = p.getString("widgetText", "");
        label.setText(text == null ? "" : text);
        label.setTextSize(p.getInt("widgetTextSizeSp", 10));
        label.setVisibility(text == null || text.trim().isEmpty() ? View.GONE : View.VISIBLE);

        int imageAlpha = Math.max(10, Math.min(100, p.getInt("widgetImageAlphaPct", 100)));
        image.setAlpha(imageAlpha / 100f);
        image.setScaleType(p.getBoolean("imageCrop", true)
                ? ImageView.ScaleType.CENTER_CROP
                : ImageView.ScaleType.FIT_CENTER);

        String raw = p.getString("widgetImageUri", null);
        if (raw == null || raw.isEmpty()) {
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
        } else {
            try {
                image.setImageURI(Uri.parse(raw));
                image.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                image.setImageDrawable(null);
                image.setVisibility(View.GONE);
            }
        }

        String desc = "GhostShot screenshot control";
        if (text != null && !text.trim().isEmpty()) desc += ": " + text.trim();
        setContentDescription(desc);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
