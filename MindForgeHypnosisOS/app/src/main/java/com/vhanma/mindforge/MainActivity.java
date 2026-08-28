package com.vhanma.mindforge;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1407;
    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private ValueCallback<Uri[]> filePathCallback;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF050712);
        getWindow().setNavigationBarColor(0xFF050712);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setDatabaseEnabled(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "File picker unavailable", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });
        webView.addJavascriptInterface(new Bridge(), "MindForgeAndroid");
        setContentView(webView);
        initTts();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                int result = tts.setLanguage(Locale.getDefault());
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }
                    @Override public void onDone(String utteranceId) { callbackDone(utteranceId, "done"); }
                    @Override public void onError(String utteranceId) { callbackDone(utteranceId, "error"); }
                    @Override public void onStop(String utteranceId, boolean interrupted) { callbackDone(utteranceId, "stopped"); }
                });
            }
            main.post(() -> eval("window.__nativeReady && window.__nativeReady(" + (ttsReady ? "true" : "false") + ")"));
        });
    }

    private void eval(String js) {
        if (webView != null) webView.evaluateJavascript(js, null);
    }

    private String js(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private void callbackDone(String id, String status) {
        main.post(() -> eval("window.__nativeTtsDone && window.__nativeTtsDone(\"" + js(id) + "\",\"" + js(status) + "\")"));
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                results = new Uri[n];
                for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (webView != null) { webView.removeJavascriptInterface("MindForgeAndroid"); webView.destroy(); }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("window.__mindforgeBack ? window.__mindforgeBack() : false", value -> {
                if ("true".equals(value)) return;
                if (webView.canGoBack()) webView.goBack(); else MainActivity.super.onBackPressed();
            });
        } else super.onBackPressed();
    }

    public class Bridge {
        @JavascriptInterface public boolean isTtsReady() { return ttsReady; }
        @JavascriptInterface public String version() { return "MindForge Hypnosis OS 2.0.0"; }
        @JavascriptInterface public void toast(String msg) { main.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show()); }

        @JavascriptInterface public String getVoices() {
            JSONArray arr = new JSONArray();
            try {
                if (tts == null || !ttsReady) return arr.toString();
                Set<Voice> voices = tts.getVoices();
                if (voices == null) return arr.toString();
                for (Voice v : voices) {
                    JSONObject o = new JSONObject();
                    o.put("name", v.getName());
                    o.put("locale", v.getLocale() == null ? "" : v.getLocale().toLanguageTag());
                    o.put("network", v.isNetworkConnectionRequired());
                    arr.put(o);
                }
            } catch (Exception ignored) { }
            return arr.toString();
        }

        @JavascriptInterface public boolean setVoice(String name) {
            if (tts == null || !ttsReady || name == null || name.trim().isEmpty()) return false;
            try {
                Set<Voice> voices = tts.getVoices();
                if (voices == null) return false;
                for (Voice v : voices) {
                    if (name.equals(v.getName())) return tts.setVoice(v) == TextToSpeech.SUCCESS;
                }
            } catch (Exception ignored) { }
            return false;
        }

        @JavascriptInterface public void keepAwake(boolean on) {
            main.post(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface public void immersive(boolean on) {
            main.post(() -> {
                if (on) {
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                } else {
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                }
            });
        }

        @JavascriptInterface public void setBrightness(float level) {
            main.post(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                if (level < 0f) lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                else lp.screenBrightness = Math.max(0.02f, Math.min(1f, level));
                getWindow().setAttributes(lp);
            });
        }

        @JavascriptInterface public void speak(String utteranceId, String text, float rate, float pitch, String voiceName) {
            main.post(() -> {
                if (tts == null || !ttsReady) { callbackDone(utteranceId, "tts_not_ready"); return; }
                float r = Math.max(0.35f, Math.min(1.8f, rate));
                float p = Math.max(0.5f, Math.min(1.6f, pitch));
                if (voiceName != null && !voiceName.isEmpty()) setVoice(voiceName);
                tts.setSpeechRate(r);
                tts.setPitch(p);
                tts.speak(text == null ? "" : text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
            });
        }

        @JavascriptInterface public void stopSpeech() { main.post(() -> { if (tts != null) tts.stop(); }); }

        @JavascriptInterface public void vibrate(int ms) {
            main.post(() -> {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                int dur = Math.max(10, Math.min(ms, 2000));
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(dur);
            });
        }

        @JavascriptInterface public void vibratePattern(String csv) {
            main.post(() -> {
                try {
                    String[] parts = csv.split(",");
                    long[] pattern = new long[Math.min(parts.length, 20)];
                    for (int i = 0; i < pattern.length; i++) pattern[i] = Math.max(0, Math.min(2000, Long.parseLong(parts[i].trim())));
                    Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    if (v == null) return;
                    if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                    else v.vibrate(pattern, -1);
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface public void copyText(String label, String content) {
            main.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText(label == null ? "MindForge" : label, content == null ? "" : content));
                Toast.makeText(MainActivity.this, "Copied", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface public void shareText(String title, String content) {
            main.post(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TITLE, title == null ? "MindForge Session" : title);
                send.putExtra(Intent.EXTRA_TEXT, content == null ? "" : content);
                startActivity(Intent.createChooser(send, "Share MindForge text"));
            });
        }

        @JavascriptInterface public void saveText(String filename, String content) {
            main.post(() -> {
                String safe = filename == null || filename.trim().isEmpty() ? "mindforge-session.txt" : filename.replaceAll("[^a-zA-Z0-9._-]", "_");
                String data = content == null ? "" : content;
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                        values.put(MediaStore.Downloads.MIME_TYPE, safe.endsWith(".json") ? "application/json" : "text/plain");
                        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MindForge");
                        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (uri == null) throw new Exception("No download URI");
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            if (os == null) throw new Exception("No output stream");
                            os.write(data.getBytes(StandardCharsets.UTF_8));
                        }
                    } else {
                        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MindForge");
                        if (!dir.exists()) dir.mkdirs();
                        try (OutputStream os = new FileOutputStream(new File(dir, safe))) { os.write(data.getBytes(StandardCharsets.UTF_8)); }
                    }
                    Toast.makeText(MainActivity.this, "Saved to Downloads/MindForge", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}
