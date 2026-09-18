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
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
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
    private static final String ACTION_RESET = "com.vhanma.ghostshot.RESET_BUBBLE";
    private static final String ACTION_CAPTURE = "com.vhanma.ghostshot.CAPTURE";
    private static final String ACTION_REFRESH = "com.vhanma.ghostshot.REFRESH";

    private WindowManager wm;
    private View bubble;
    private WindowManager.LayoutParams bubbleLp;
    private View menu;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private boolean captureBusy;

    private float activeAlpha = 0.10f;
    private float idleAlpha = 0.01f;
    private int bubbleSizeDp = 24;
    private boolean autoFade = true;
    private boolean snapEdge = true;
    private boolean haptics = true;
    private boolean silent;
    private boolean hideAfter;
    private String format = "png";

    private Runnable fadeRunnable;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            switch (intent.getAction()) {
                case ACTION_SHOW:
                    getPrefs().edit().putBoolean("hidden", false).apply();
                    showBubble();
                    break;
                case ACTION_HIDE:
                    hideBubble(true);
                    break;
                case ACTION_RESET:
                    resetBubblePosition();
                    break;
                case ACTION_CAPTURE:
                    captureScreenshot();
                    break;
                case ACTION_REFRESH:
                    refreshFromPrefs();
                    break;
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        loadPrefs();
        registerActions();
        if (!getPrefs().getBoolean("hidden", false)) showBubble();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences("ghostshot", MODE_PRIVATE);
    }

    private void loadPrefs() {
        SharedPreferences p = getPrefs();
        int legacy = p.getInt("alphaPct", 10);
        activeAlpha = clamp(p.getInt("activePct", legacy) / 100f, 0.02f, 0.70f);
        idleAlpha = clamp(p.getInt("idlePct", 1) / 100f, 0f, 0.20f);
        bubbleSizeDp = clampInt(p.getInt("sizeDp", 24), 18, 48);
        autoFade = p.getBoolean("autoFade", true);
        snapEdge = p.getBoolean("snapEdge", true);
        haptics = p.getBoolean("haptics", true);
        silent = p.getBoolean("silent", false);
        hideAfter = p.getBoolean("hideAfter", false);
        format = p.getString("format", "png");
        if (!"jpeg".equals(format)) format = "png";
    }

    private void refreshFromPrefs() {
        loadPrefs();
        if (bubble != null && bubbleLp != null && wm != null) {
            int px = dp(bubbleSizeDp);
            bubbleLp.width = px;
            bubbleLp.height = px;
            clampAndMaybeSnap(false);
            try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) {}
            setActiveVisual();
            scheduleIdleFade();
        }
    }

    private void registerActions() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SHOW);
        filter.addAction(ACTION_HIDE);
        filter.addAction(ACTION_RESET);
        filter.addAction(ACTION_CAPTURE);
        filter.addAction(ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        receiverRegistered = true;
    }

    private void showBubble() {
        if (wm == null || bubble != null) return;
        loadPrefs();

        View dot = new View(this);
        dot.setBackgroundResource(R.drawable.ghost_dot);
        dot.setAlpha(activeAlpha);
        dot.setContentDescription("GhostShot screenshot button. Tap to capture, drag to move, long-press for controls.");

        int size = dp(bubbleSizeDp);
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
        bubbleLp.x = getPrefs().getInt("x", dp(8));
        bubbleLp.y = getPrefs().getInt("y", dp(180));

        installTouch(dot);
        bubble = dot;
        clampAndMaybeSnap(false);
        wm.addView(bubble, bubbleLp);
        scheduleIdleFade();
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
                        cancelFade();
                        setActiveVisual();
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = bubbleLp.x;
                        startY = bubbleLp.y;
                        moved = false;
                        longPressed = false;
                        longPress = () -> {
                            longPressed = true;
                            vibrate(26);
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
                            clampAndMaybeSnap(false);
                            try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) {}
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(longPress);
                        if (moved) {
                            clampAndMaybeSnap(true);
                            try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) {}
                            savePosition();
                            scheduleIdleFade();
                        } else if (!longPressed && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            captureScreenshot();
                        } else {
                            scheduleIdleFade();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void setActiveVisual() {
        if (bubble != null) bubble.setAlpha(activeAlpha);
    }

    private void scheduleIdleFade() {
        cancelFade();
        if (bubble == null) return;
        if (!autoFade) {
            bubble.setAlpha(activeAlpha);
            return;
        }
        fadeRunnable = () -> {
            if (bubble != null && !captureBusy) bubble.setAlpha(idleAlpha);
        };
        handler.postDelayed(fadeRunnable, 700);
    }

    private void cancelFade() {
        if (fadeRunnable != null) handler.removeCallbacks(fadeRunnable);
        fadeRunnable = null;
    }

    private void clampAndMaybeSnap(boolean applySnap) {
        if (bubbleLp == null || wm == null) return;
        Rect bounds = wm.getCurrentWindowMetrics().getBounds();
        int size = dp(bubbleSizeDp);
        int maxX = Math.max(0, bounds.width() - size);
        int maxY = Math.max(0, bounds.height() - size);
        bubbleLp.x = clampInt(bubbleLp.x, 0, maxX);
        bubbleLp.y = clampInt(bubbleLp.y, 0, maxY);
        if (applySnap && snapEdge) {
            int inset = dp(2);
            bubbleLp.x = bubbleLp.x + size / 2 < bounds.width() / 2 ? inset : Math.max(inset, maxX - inset);
        }
    }

    private void savePosition() {
        if (bubbleLp == null) return;
        getPrefs().edit().putInt("x", bubbleLp.x).putInt("y", bubbleLp.y).apply();
    }

    private void showMenu() {
        if (wm == null || menu != null) return;
        cancelFade();
        setActiveVisual();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(4), dp(3), dp(4), dp(3));
        row.setBackgroundResource(R.drawable.menu_bg);

        Button shot = smallButton("Shot");
        Button hide = smallButton("Hide");
        Button settings = smallButton("Settings");
        Button close = smallButton("Close");
        row.addView(shot);
        row.addView(hide);
        row.addView(settings);
        row.addView(close);

        shot.setOnClickListener(v -> {
            removeMenu();
            captureScreenshot();
        });
        hide.setOnClickListener(v -> {
            removeMenu();
            hideBubble(true);
        });
        settings.setOnClickListener(v -> {
            removeMenu();
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            scheduleIdleFade();
        });
        close.setOnClickListener(v -> {
            removeMenu();
            hideBubble(false);
            disableSelf();
        });

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
        Rect bounds = wm.getCurrentWindowMetrics().getBounds();
        int estimatedWidth = dp(260);
        int desiredX = bubbleLp != null ? bubbleLp.x - dp(24) : dp(8);
        int desiredY = bubbleLp != null ? bubbleLp.y + dp(bubbleSizeDp + 6) : dp(210);
        menuLp.x = clampInt(desiredX, 0, Math.max(0, bounds.width() - estimatedWidth));
        menuLp.y = clampInt(desiredY, 0, Math.max(0, bounds.height() - dp(52)));
        menu = row;
        wm.addView(menu, menuLp);
    }

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(38));
        button.setPadding(dp(9), 0, dp(9), 0);
        return button;
    }

    private void hideBubble(boolean rememberHidden) {
        cancelFade();
        if (rememberHidden) getPrefs().edit().putBoolean("hidden", true).apply();
        removeMenu();
        if (wm != null && bubble != null) {
            try { wm.removeView(bubble); } catch (Exception ignored) {}
        }
        bubble = null;
        bubbleLp = null;
    }

    private void removeMenu() {
        if (wm != null && menu != null) {
            try { wm.removeView(menu); } catch (Exception ignored) {}
        }
        menu = null;
    }

    private void resetBubblePosition() {
        getPrefs().edit().remove("x").remove("y").putBoolean("hidden", false).apply();
        if (bubble == null) {
            showBubble();
            return;
        }
        bubbleLp.x = dp(8);
        bubbleLp.y = dp(180);
        clampAndMaybeSnap(false);
        try { wm.updateViewLayout(bubble, bubbleLp); } catch (Exception ignored) {}
        savePosition();
        setActiveVisual();
        scheduleIdleFade();
    }

    private void captureScreenshot() {
        if (captureBusy) return;
        captureBusy = true;
        cancelFade();
        removeMenu();
        if (bubble != null) bubble.setVisibility(View.INVISIBLE);

        handler.postDelayed(() -> takeScreenshot(
                Display.DEFAULT_DISPLAY,
                getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult result) {
                        Bitmap copy = null;
                        HardwareBuffer hardwareBuffer = null;
                        boolean success = false;
                        try {
                            hardwareBuffer = result.getHardwareBuffer();
                            Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.getColorSpace());
                            if (hardwareBitmap != null) copy = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                            if (copy == null) throw new IllegalStateException("Screenshot bitmap unavailable");
                            saveBitmap(copy);
                            success = true;
                        } catch (Exception exception) {
                            Toast.makeText(ScreenshotAccessibilityService.this,
                                    "Screenshot failed: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
                        } finally {
                            if (copy != null) copy.recycle();
                            if (hardwareBuffer != null) hardwareBuffer.close();
                            finishCapture(success);
                        }
                    }

                    @Override public void onFailure(int errorCode) {
                        Toast.makeText(ScreenshotAccessibilityService.this,
                                errorMessage(errorCode), Toast.LENGTH_SHORT).show();
                        finishCapture(false);
                    }
                }
        ), 70);
    }

    private void finishCapture(boolean success) {
        if (success && haptics) vibrate(18);
        if (success && hideAfter) {
            captureBusy = false;
            hideBubble(true);
            return;
        }
        handler.postDelayed(() -> {
            captureBusy = false;
            if (bubble != null && !getPrefs().getBoolean("hidden", false)) {
                bubble.setVisibility(View.VISIBLE);
                setActiveVisual();
                scheduleIdleFade();
            }
        }, 90);
    }

    private void saveBitmap(Bitmap bitmap) throws Exception {
        boolean jpeg = "jpeg".equals(format);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String ext = jpeg ? ".jpg" : ".png";
        String mime = jpeg ? "image/jpeg" : "image/png";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "GhostShot_" + stamp + ext);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        ContentResolver resolver = getContentResolver();
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("MediaStore insert failed");

        boolean ok;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("Could not open output stream");
            ok = bitmap.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG,
                    jpeg ? 97 : 100, out);
        } catch (Exception exception) {
            resolver.delete(uri, null, null);
            throw exception;
        }

        if (!ok) {
            resolver.delete(uri, null, null);
            throw new IllegalStateException("Image encode failed");
        }

        ContentValues complete = new ContentValues();
        complete.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, complete, null, null);
        if (!silent) Toast.makeText(this, "GhostShot saved", Toast.LENGTH_SHORT).show();
    }

    private void vibrate(long millis) {
        if (!haptics) return;
        try {
            Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) {}
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
        cancelFade();
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

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
