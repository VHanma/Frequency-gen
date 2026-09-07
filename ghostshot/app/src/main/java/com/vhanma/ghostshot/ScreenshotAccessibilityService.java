package com.vhanma.ghostshot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScreenshotAccessibilityService extends AccessibilityService {
    private static final String ACTION_SHOW = "com.vhanma.ghostshot.SHOW_BUBBLE";
    private static final String ACTION_HIDE = "com.vhanma.ghostshot.HIDE_BUBBLE";
    private static final String ACTION_ALPHA = "com.vhanma.ghostshot.SET_ALPHA";
    private static final String ACTION_RESET = "com.vhanma.ghostshot.RESET_BUBBLE";

    private WindowManager wm;
    private View bubble;
    private WindowManager.LayoutParams bubbleLp;
    private View menu;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private float dotAlpha = 0.08f;
    private boolean captureBusy;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            switch (intent.getAction()) {
                case ACTION_SHOW:
                    getSharedPreferences("ghostshot", MODE_PRIVATE).edit().putBoolean("hidden", false).apply();
                    showBubble();
                    break;
                case ACTION_HIDE:
                    hideBubble(true);
                    break;
                case ACTION_ALPHA:
                    setDotAlpha(intent.getFloatExtra("alpha", dotAlpha));
                    break;
                case ACTION_RESET:
                    resetBubblePosition();
                    break;
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int alphaPct = getSharedPreferences("ghostshot", MODE_PRIVATE).getInt("alphaPct", 8);
        dotAlpha = clamp(alphaPct / 100f, 0.02f, 0.50f);
        registerActions();
        if (!getSharedPreferences("ghostshot", MODE_PRIVATE).getBoolean("hidden", false)) showBubble();
    }

    private void registerActions() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SHOW);
        filter.addAction(ACTION_HIDE);
        filter.addAction(ACTION_ALPHA);
        filter.addAction(ACTION_RESET);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        receiverRegistered = true;
    }

    private void showBubble() {
        if (wm == null || bubble != null) return;

        View dot = new View(this);
        dot.setBackgroundResource(R.drawable.ghost_dot);
        dot.setAlpha(dotAlpha);
        dot.setContentDescription("Screenshot button. Tap to capture, drag to move, long-press for controls.");

        int size = dp(26);
        bubbleLp = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = getSharedPreferences("ghostshot", MODE_PRIVATE).getInt("x", dp(8));
        bubbleLp.y = getSharedPreferences("ghostshot", MODE_PRIVATE).getInt("y", dp(180));

        installTouch(dot);
        bubble = dot;
        wm.addView(bubble, bubbleLp);
    }

    private void installTouch(View view) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        view.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;
            boolean longPressed;
            Runnable longPress;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = bubbleLp.x;
                        startY = bubbleLp.y;
                        moved = false;
                        longPressed = false;
                        longPress = () -> {
                            longPressed = true;
                            showMenu();
                        };
                        handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (!moved && Math.hypot(dx, dy) > slop) {
                            moved = true;
                            handler.removeCallbacks(longPress);
                        }
                        if (moved && bubble != null) {
                            bubbleLp.x = startX + Math.round(dx);
                            bubbleLp.y = startY + Math.round(dy);
                            wm.updateViewLayout(bubble, bubbleLp);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPress);
                        if (moved) {
                            getSharedPreferences("ghostshot", MODE_PRIVATE).edit()
                                    .putInt("x", bubbleLp.x)
                                    .putInt("y", bubbleLp.y)
                                    .apply();
                        } else if (!longPressed && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            captureScreenshot();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void showMenu() {
        if (wm == null || menu != null) return;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(5), dp(3), dp(5), dp(3));
        row.setBackgroundResource(R.drawable.menu_bg);

        Button hide = smallButton("Hide");
        Button close = smallButton("Close");
        Button back = smallButton("↩");
        row.addView(hide);
        row.addView(close);
        row.addView(back);

        hide.setOnClickListener(v -> {
            removeMenu();
            hideBubble(true);
        });
        close.setOnClickListener(v -> {
            removeMenu();
            hideBubble(false);
            disableSelf();
        });
        back.setOnClickListener(v -> removeMenu());

        WindowManager.LayoutParams menuLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        menuLp.gravity = Gravity.TOP | Gravity.START;
        menuLp.x = bubbleLp != null ? Math.max(0, bubbleLp.x - dp(18)) : dp(8);
        menuLp.y = bubbleLp != null ? Math.max(0, bubbleLp.y + dp(32)) : dp(210);
        menu = row;
        wm.addView(menu, menuLp);
    }

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(38));
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private void hideBubble(boolean rememberHidden) {
        if (rememberHidden) {
            getSharedPreferences("ghostshot", MODE_PRIVATE).edit().putBoolean("hidden", true).apply();
        }
        removeMenu();
        if (wm != null && bubble != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
        }
        bubble = null;
    }

    private void removeMenu() {
        if (wm != null && menu != null) {
            try { wm.removeView(menu); } catch (Exception ignored) {}
        }
        menu = null;
    }

    private void resetBubblePosition() {
        getSharedPreferences("ghostshot", MODE_PRIVATE).edit()
                .remove("x")
                .remove("y")
                .putBoolean("hidden", false)
                .apply();
        if (bubble == null) {
            showBubble();
            return;
        }
        bubbleLp.x = dp(8);
        bubbleLp.y = dp(180);
        wm.updateViewLayout(bubble, bubbleLp);
    }

    private void setDotAlpha(float alpha) {
        dotAlpha = clamp(alpha, 0.02f, 0.50f);
        getSharedPreferences("ghostshot", MODE_PRIVATE).edit()
                .putInt("alphaPct", Math.round(dotAlpha * 100f))
                .apply();
        if (bubble != null) bubble.setAlpha(dotAlpha);
    }

    private void captureScreenshot() {
        if (captureBusy) return;
        captureBusy = true;
        removeMenu();
        if (bubble != null) bubble.setVisibility(View.INVISIBLE);

        handler.postDelayed(() -> takeScreenshot(
                Display.DEFAULT_DISPLAY,
                getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult result) {
                        Bitmap copy = null;
                        HardwareBuffer hardwareBuffer = null;
                        try {
                            hardwareBuffer = result.getHardwareBuffer();
                            Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.getColorSpace());
                            if (hardwareBitmap != null) copy = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                            if (copy == null) throw new IllegalStateException("Screenshot bitmap unavailable");
                            saveBitmap(copy);
                        } catch (Exception exception) {
                            Toast.makeText(ScreenshotAccessibilityService.this,
                                    "Screenshot failed: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
                        } finally {
                            if (copy != null) copy.recycle();
                            if (hardwareBuffer != null) hardwareBuffer.close();
                            restoreBubbleAfterCapture();
                        }
                    }

                    @Override public void onFailure(int errorCode) {
                        Toast.makeText(ScreenshotAccessibilityService.this,
                                errorMessage(errorCode), Toast.LENGTH_SHORT).show();
                        restoreBubbleAfterCapture();
                    }
                }
        ), 90);
    }

    private void restoreBubbleAfterCapture() {
        handler.postDelayed(() -> {
            captureBusy = false;
            if (bubble != null && !getSharedPreferences("ghostshot", MODE_PRIVATE).getBoolean("hidden", false)) {
                bubble.setVisibility(View.VISIBLE);
                bubble.setAlpha(dotAlpha);
            }
        }, 110);
    }

    private void saveBitmap(Bitmap bitmap) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "GhostShot_" + stamp + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        ContentResolver resolver = getContentResolver();
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("MediaStore insert failed");

        boolean ok;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("Could not open output stream");
            ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception exception) {
            resolver.delete(uri, null, null);
            throw exception;
        }

        if (!ok) {
            resolver.delete(uri, null, null);
            throw new IllegalStateException("PNG encode failed");
        }

        ContentValues complete = new ContentValues();
        complete.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, complete, null, null);
        Toast.makeText(this, "Screenshot saved", Toast.LENGTH_SHORT).show();
    }

    private String errorMessage(int code) {
        switch (code) {
            case ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT:
                return "Tap again in a moment";
            case ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS:
                return "Screenshot access is off";
            case ERROR_TAKE_SCREENSHOT_SECURE_WINDOW:
                return "This screen blocks screenshots";
            case ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY:
                return "Display capture unavailable";
            case ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR:
                return "Android screenshot service error";
            default:
                return "Screenshot failed (" + code + ")";
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        removeMenu();
        hideBubble(false);
        if (receiverRegistered) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
