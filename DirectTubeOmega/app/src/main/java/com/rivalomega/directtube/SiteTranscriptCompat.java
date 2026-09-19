package com.rivalomega.directtube;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SiteTranscriptCompat {
    private static final String BASE = "https://www.youtube-transcript.io/";
    private static final String FIREBASE_SIGNUP = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:139.0) Gecko/20100101 Firefox/139.0";

    private static final Pattern SCRIPT_SRC = Pattern.compile("<script[^>]+src=[\\\"']([^\\\"']+)[\\\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern API_KEY = Pattern.compile("apiKey\\s*:\\s*[\\\"']([^\\\"']+)");
    private static final Pattern APP_ID = Pattern.compile("appId\\s*:\\s*[\\\"']([^\\\"']+)");
    private static final Pattern CONTEXT = Pattern.compile(
            "header\\s*:\\s*[\\\"']([^\\\"']+)[\\\"'][^}]{0,500}value\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private SiteTranscriptCompat() {}

    static YouTubeTranscriptClient.Result fetch(String urlOrId, String preferredLanguages) throws Exception {
        String videoId = YouTubeTranscriptClient.extractVideoId(urlOrId);
        if (videoId == null) throw new Exception("Invalid YouTube video ID.");

        FirebaseConfig cfg = discoverFirebaseConfig();
        String token = anonymousFirebaseToken(cfg);
        HeaderPair channel = discoverRequestChannel(videoId);
        JSONObject response = callTranscriptV2(videoId, token, channel);
        return parseResponse(videoId, response, preferredLanguages);
    }

    private static FirebaseConfig discoverFirebaseConfig() throws Exception {
        String home = get(BASE);
        List<String> scripts = scriptUrls(BASE, home);
        for (String scriptUrl : scripts) {
            String js;
            try { js = get(scriptUrl); } catch (Exception ignored) { continue; }
            if (!js.contains("apiKey") || !js.contains("appId")) continue;
            String apiKey = first(API_KEY, js);
            String appId = first(APP_ID, js);
            if (apiKey != null && appId != null) return new FirebaseConfig(apiKey, appId);
        }
        throw new Exception("Site Firebase configuration was not found.");
    }

    private static String anonymousFirebaseToken(FirebaseConfig cfg) throws Exception {
        String url = FIREBASE_SIGNUP + enc(cfg.apiKey);
        JSONObject body = new JSONObject();
        body.put("returnSecureToken", true);

        HttpURLConnection c = open(url, "POST");
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("X-Client-Version", "Firefox/JsCore/10.14.1/FirebaseCore-web");
        String gmpid = firebaseGmpId(cfg.appId);
        if (!gmpid.isEmpty()) c.setRequestProperty("X-Firebase-gmpid", gmpid);
        write(c, body.toString());
        HttpResult r = read(c);
        if (r.code < 200 || r.code >= 300) throw new Exception("Site anonymous auth returned HTTP " + r.code + ".");
        String token = new JSONObject(r.body).optString("idToken", "");
        if (token.isEmpty()) throw new Exception("Site anonymous auth returned no token.");
        return token;
    }

    private static HeaderPair discoverRequestChannel(String videoId) throws Exception {
        String pageUrl = BASE + "videos/" + videoId;
        String page = get(pageUrl);
        List<String> scripts = scriptUrls(pageUrl, page);
        for (String scriptUrl : scripts) {
            String js;
            try { js = get(scriptUrl); } catch (Exception ignored) { continue; }
            if (!js.contains("/api/transcripts")) continue;
            Matcher m = CONTEXT.matcher(js);
            while (m.find()) {
                String name = m.group(1);
                String value = m.group(2);
                if (name != null && name.toLowerCase(Locale.US).startsWith("x-")) {
                    return new HeaderPair(name, value);
                }
            }
        }
        throw new Exception("Site transcript request channel was not found.");
    }

    private static JSONObject callTranscriptV2(String videoId, String token, HeaderPair channel) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray ids = new JSONArray();
        ids.put(videoId);
        body.put("ids", ids);
        body.put("source", "singleVideoUI");

        HttpURLConnection c = open(BASE + "api/transcripts/v2", "POST");
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty(channel.name, channel.value);
        c.setRequestProperty("Origin", "https://www.youtube-transcript.io");
        c.setRequestProperty("Referer", "https://www.youtube-transcript.io/");
        write(c, body.toString());
        HttpResult r = read(c);
        if (r.code == 402) throw new Exception("Site transcript allowance exhausted.");
        if (r.code == 401) throw new Exception("Site transcript session expired.");
        if (r.code < 200 || r.code >= 300) throw new Exception("Site transcript API returned HTTP " + r.code + ".");
        return new JSONObject(r.body);
    }

    private static YouTubeTranscriptClient.Result parseResponse(String videoId, JSONObject response,
                                                                 String preferredLanguages) throws Exception {
        JSONArray success = response.optJSONArray("success");
        JSONObject root = success != null && success.length() > 0 ? success.optJSONObject(0) : null;
        if (root == null) {
            JSONArray failed = response.optJSONArray("failed");
            if (failed != null && failed.length() > 0) {
                JSONObject f = failed.optJSONObject(0);
                String message = f == null ? "" : f.optString("text", f.optString("message", ""));
                throw new Exception(message.isEmpty() ? "Site could not extract this transcript." : message);
            }
            throw new Exception("Site returned no transcript result.");
        }

        JSONArray tracks = root.optJSONArray("tracks");
        if (tracks == null || tracks.length() == 0) throw new Exception("Site returned no transcript tracks.");
        JSONObject track = chooseTrack(tracks, preferredLanguages);
        if (track == null) track = tracks.optJSONObject(0);
        if (track == null) throw new Exception("Site transcript track was unreadable.");
        JSONArray transcript = track.optJSONArray("transcript");
        if (transcript == null || transcript.length() == 0) throw new Exception("Site transcript was empty.");

        YouTubeTranscriptClient.Result out = new YouTubeTranscriptClient.Result();
        out.videoId = root.optString("id", videoId);
        out.title = root.optString("title", "");
        JSONObject micro = root.optJSONObject("microformat");
        if (micro != null) out.author = micro.optString("ownerChannelName", micro.optString("ownerProfileUrl", ""));
        out.languageCode = track.optString("language", "en");
        out.languageName = out.languageCode;
        out.generated = true;
        out.translated = false;
        out.sourceMethod = "youtube-transcript-io-v2";

        JSONArray langs = root.optJSONArray("languages");
        if (langs != null) {
            for (int i = 0; i < langs.length(); i++) {
                JSONObject l = langs.optJSONObject(i);
                if (l == null) continue;
                String code = l.optString("languageCode", "");
                String label = l.optString("label", code);
                if (!code.isEmpty()) out.availableLanguages.add(code + " — " + label);
            }
        }

        for (int i = 0; i < transcript.length(); i++) {
            JSONObject x = transcript.optJSONObject(i);
            if (x == null) continue;
            String text = x.optString("text", "").replace('\u00A0', ' ').trim();
            if (text.isEmpty()) continue;
            long startMs = secondsStringToMs(x.optString("start", "0"));
            long durMs = secondsStringToMs(x.optString("dur", "0"));
            out.segments.add(new YouTubeTranscriptClient.Segment(text, startMs, durMs));
        }
        if (out.segments.isEmpty()) throw new Exception("Site transcript contained no text.");
        return out;
    }

    private static JSONObject chooseTrack(JSONArray tracks, String preferredLanguages) {
        String pref = "en";
        if (preferredLanguages != null && !preferredLanguages.trim().isEmpty()) {
            String[] p = preferredLanguages.trim().split("[,\\s]+");
            if (p.length > 0 && !p[0].isEmpty()) pref = p[0].toLowerCase(Locale.US);
        }
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            if (t == null) continue;
            String lang = t.optString("language", "").toLowerCase(Locale.US);
            if (lang.equals(pref) || lang.startsWith(pref + "-") || pref.startsWith(lang + "-")) return t;
        }
        return tracks.optJSONObject(0);
    }

    private static List<String> scriptUrls(String pageUrl, String html) throws Exception {
        ArrayList<String> out = new ArrayList<>();
        Matcher m = SCRIPT_SRC.matcher(html);
        URL base = new URL(pageUrl);
        while (m.find()) {
            String src = m.group(1);
            if (src == null || src.isEmpty()) continue;
            out.add(new URL(base, src).toString());
        }
        return out;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url, "GET");
        return read(c).requireOk();
    }

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(45000);
        c.setInstanceFollowRedirects(true);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        if ("POST".equals(method)) c.setDoOutput(true);
        return c;
    }

    private static void write(HttpURLConnection c, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
    }

    private static HttpResult read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
        }
        c.disconnect();
        return new HttpResult(code, new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s == null ? "" : s);
        return m.find() ? m.group(1) : null;
    }

    private static String enc(String s) throws Exception {
        return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String firebaseGmpId(String appId) {
        if (appId == null) return "";
        int a = appId.indexOf(':');
        if (a < 0) return appId;
        int b = appId.indexOf(':', a + 1);
        return b < 0 ? appId.substring(a + 1) : appId.substring(b + 1);
    }

    private static long secondsStringToMs(String s) {
        try { return Math.round(Double.parseDouble(s == null ? "0" : s) * 1000.0); }
        catch (Exception e) { return 0; }
    }

    private static final class FirebaseConfig {
        final String apiKey, appId;
        FirebaseConfig(String apiKey, String appId) { this.apiKey = apiKey; this.appId = appId; }
    }

    private static final class HeaderPair {
        final String name, value;
        HeaderPair(String name, String value) { this.name = name; this.value = value; }
    }

    private static final class HttpResult {
        final int code; final String body;
        HttpResult(int code, String body) { this.code = code; this.body = body; }
        String requireOk() throws Exception {
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            return body;
        }
    }
}
