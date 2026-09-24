package com.vhanma.ghostshot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback;
import android.content.BroadcastReceiver;
import android.content.ClipData;
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
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.MediaStore;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
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
    private static final String ACTION_BURST = "com.vhanma.ghostshot.BURST";
    private static final String ACTION_REFRESH = "com.vhanma.ghostshot.REFRESH";
    private static final String ACTION_OPEN_LAST = "com.vhanma.ghostshot.OPEN_LAST";
    private static final String ACTION_SHARE_LAST = "com.vhanma.ghostshot.SHARE_LAST";
    private static final String ACTION_DELETE_LAST = "com.vhanma.ghostshot.DELETE_LAST";

    private WindowManager wm;
    private View bubble;
    private WindowManager.LayoutParams bubbleLp;
    private View menu;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private boolean captureBusy;

    private float activeAlpha = 0.10f;
    private float idleAlpha = 0f;
    private int bubbleSizeDp = 24;
    private boolean autoFade = true;
    private boolean snapEdge = true;
    private boolean haptics = true;
    private boolean silent;
    private boolean hideAfter;
    private boolean doubleTapBurst = true;
    private boolean volumeCapture;
    private boolean includeAppName;
    private int burstCount = 3;
    private int burstIntervalMs = 650;
    private int delaySec;
    private String format = "png";
    private String lastPackage = "";

    private Runnable fadeRunnable;
    private Runnable pendingSingleTap;
    private long lastTapUp;
    private long lastVolumeDown;
    private int burstRemaining;
    private boolean burstAnySuccess;

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
                    requestSingleCapture();
                    break;
                case ACTION_BURST:
                    requestBurst();
                    break;
                case ACTION_REFRESH:
                    refreshFromPrefs();
                    break;
                case ACTION_OPEN_LAST:
                    openLast();
                    break;
                case ACTION_SHARE_LAST:
                    shareLast();
                    break;
                case ACTION_DELETE_LAST:
                    deleteLast();
                    break;
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        loadPrefs();
        configureServiceFlags();
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
        idleAlpha = clamp(p.getInt("idlePct", 0) / 100f, 0f, 0.20f);
        bubbleSizeDp = clampInt(p.getInt("sizeDp", 24), 18, 56);
        autoFade = p.getBoolean("autoFade", true);
        snapEdge = p.getBoolean("snapEdge", true);
        haptics = p.getBoolean("haptics", true);
        silent = p.getBoolean("silent", false);
        hideAfter = p.getBoolean("hideAfter", false);
        doubleTapBurst = p.getBoolean("doubleTapBurst", true);
        volumeCapture = p.getBoolean("volumeCapture", false);
        includeAppName = p.getBoolean("includeAppName", false);
        burstCount = clampInt(p.getInt("burstCount", 3), 2, 5);
        burstIntervalMs = clampInt(p.getInt("burstIntervalMs", 650), 450, 1500);
        delaySec = clampInt(p.getInt("delaySec", 0), 0, 5);
        format = p.getString("format", "png");
        if (!"jpeg".equals(format)) format = "png";
    }

    private void configureServiceFlags() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                setServiceInfo(info);
            }
        } catch (Exception ignored) {}
    }

    private void refreshFromPrefs() {
        loadPrefs();
        configureServiceFlags();
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
        filter.addAction(ACTION_BURST);
        filter.addAction(ACTION_REFRESH);
        filter.addAction(ACTION_OPEN_LAST);
        filter.addAction(ACTION_SHARE_LAST);
        filter.addAction(ACTION_DELETE_LAST);
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
        dot.setContentDescription("GhostShot screenshot control. Tap to capture, double-tap for burst, drag to move, long-press for controls.");

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
                            cancelPendingTap();
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
                            handleTap();
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

    private void handleTap() {
        if (!doubleTapBurst) {
            requestSingleCapture();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (lastTapUp > 0 && now - lastTapUp <= 320 && pendingSingleTap != null) {
            handler.removeCallbacks(pendingSingleTap);
            pendingSingleTap = null;
            lastTapUp = 0;
            requestBurst();
            return;
        }
        lastTapUp = now;
        pendingSingleTap = () -> {
            pendingSingleTap = null;
            lastTapUp = 0;
            requestSingleCapture();
        };
        handler.postDelayed(pendingSingleTap, 330);
    }

    private void cancelPendingTap() {
        if (pendingSingleTap != null) handler.removeCallbacks(pendingSingleTap);
        pendingSingleTap = null;
        lastTapUp = 0;
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

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(dp(4), dp(3), dp(4), dp(3));
        column.setBackgroundResource(R.drawable.menu_bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        Button shot = smallButton("Shot");
        Button burst = smallButton("Burst");
        Button hide = smallButton("Hide");
        top.addView(shot);
        top.addView(burst);
        top.addView(hide);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        Button last = smallButton("Last");
        Button settings = smallButton("Settings");
        Button close = smallButton("Close");
        bottom.addView(last);
        bottom.addView(settings);
        bottom.addView(close);

        column.addView(top);
        column.addView(bottom);

        shot.setOnClickListener(v -> { removeMenu(); requestSingleCapture(); });
        burst.setOnClickListener(v -> { removeMenu(); requestBurst(); });
        hide.setOnClickListener(v -> { removeMenu(); hideBubble(true); });
        last.setOnClickListener(v -> { removeMenu(); openLast(); scheduleIdleFade(); });
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
        int estimatedWidth = dp(220);
        int estimatedHeight = dp(92);
        int desiredX = bubbleLp != null ? bubbleLp.x - dp(24) : dp(8);
        int desiredY = bubbleLp != null ? bubbleLp.y + dp(bubbleSizeDp + 6) : dp(210);
        menuLp.x = clampInt(desiredX, 0, Math.max(0, bounds.width() - estimatedWidth));
        menuLp.y = clampInt(desiredY, 0, Math.max(0, bounds.height() - estimatedHeight));
        menu = column;
        wm.addView(menu, menuLp);
    }

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(36));
        button.setPadding(dp(9), 0, dp(9), 0);
        return button;
    }

    private void hideBubble(boolean rememberHidden) {
        cancelFade();
        cancelPendingTap();
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

    private void requestSingleCapture() {
        if (captureBusy) return;
        captureBusy = true;
        burstRemaining = 0;
        burstAnySuccess = false;
        if (delaySec > 0 && !silent) Toast.makeText(this, "Shot in " + delaySec + "s", Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> doCapture(false), delaySec * 1000L);
    }

    private void requestBurst() {
        if (captureBusy) return;
        captureBusy = true;
        burstRemaining = burstCount;
        burstAnySuccess = false;
        if (delaySec > 0 && !silent) Toast.makeText(this, "Burst in " + delaySec + "s", Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> doCapture(true), delaySec * 1000L);
    }

    private void doCapture(boolean burstMode) {
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
                            finishCapture(success, burstMode);
                        }
                    }

                    @Override public void onFailure(int errorCode) {
                        Toast.makeText(ScreenshotAccessibilityService.this,
                                errorMessage(errorCode), Toast.LENGTH_SHORT).show();
                        finishCapture(false, burstMode);
                    }
                }
        ), 70);
    }

    private void finishCapture(boolean success, boolean burstMode) {
        if (success) {
            burstAnySuccess = true;
            if (haptics) vibrate(18);
        }

        if (burstMode) {
            burstRemaining--;
            if (burstRemaining > 0) {
                handler.postDelayed(() -> doCapture(true), burstIntervalMs);
                return;
            }
            completeCaptureSequence(burstAnySuccess);
            return;
        }
        completeCaptureSequence(success);
    }

    private void completeCaptureSequence(boolean success) {
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
        String appPrefix = includeAppName ? sanitizePackage(lastPackage) : "";
        String displayName = "GhostShot_" + (appPrefix.isEmpty() ? "" : appPrefix + "_") + stamp + ext;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
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

        SharedPreferences p = getPrefs();
        p.edit()
                .putString("lastUri", uri.toString())
                .putInt("shotCount", p.getInt("shotCount", 0) + 1)
                .apply();

        if (!silent) Toast.makeText(this, "GhostShot saved", Toast.LENGTH_SHORT).show();
    }

    private String sanitizePackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) return "";
        int dot = pkg.lastIndexOf('.');
        String shortName = dot >= 0 && dot < pkg.length() - 1 ? pkg.substring(dot + 1) : pkg;
        shortName = shortName.replaceAll("[^A-Za-z0-9_-]", "");
        if (shortName.length() > 24) shortName = shortName.substring(0, 24);
        return shortName;
    }

    private Uri getLastUri() {
        String raw = getPrefs().getString("lastUri", null);
        if (raw == null || raw.isEmpty()) return null;
        try { return Uri.parse(raw); } catch (Exception ignored) { return null; }
    }

    private void openLast() {
        Uri uri = getLastUri();
        if (uri == null) {
            Toast.makeText(this, "No GhostShot yet", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "image/*");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No image viewer available", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareLast() {
        Uri uri = getLastUri();
        if (uri == null) {
            Toast.makeText(this, "No GhostShot yet", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("image/*");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newRawUri("GhostShot", uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "Share GhostShot");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
        } catch (Exception e) {
            Toast.makeText(this, "Share unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteLast() {
        Uri uri = getLastUri();
        if (uri == null) {
            Toast.makeText(this, "No GhostShot yet", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int deleted = getContentResolver().delete(uri, null, null);
            if (deleted > 0) {
                getPrefs().edit().remove("lastUri").apply();
                if (haptics) vibrate(18);
                Toast.makeText(this, "Last GhostShot deleted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Could not delete last shot", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Delete unavailable", Toast.LENGTH_SHORT).show();
        }
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

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (volumeCapture && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            long now = SystemClock.uptimeMillis();
            if (lastVolumeDown > 0 && now - lastVolumeDown <= 550) {
                lastVolumeDown = 0;
                requestSingleCapture();
            } else {
                lastVolumeDown = now;
            }
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null) {
            String pkg = event.getPackageName().toString();
            if (!pkg.equals(getPackageName())) lastPackage = pkg;
        }
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        cancelFade();
        cancelPendingTap();
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
