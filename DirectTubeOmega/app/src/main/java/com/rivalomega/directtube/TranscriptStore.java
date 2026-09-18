package com.rivalomega.directtube;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class TranscriptStore {
    private static final String PREF = "history";
    private static final String INDEX = "index";
    private static final int MAX = 30;

    public static final class Entry {
        public String videoId;
        public String title;
        public String language;
        public long savedAt;
        public String fileName;

        String label() {
            String name = title == null || title.isEmpty() ? videoId : title;
            return name + "\n" + language;
        }
    }

    private TranscriptStore() {}

    public static void save(Context context, YouTubeTranscriptClient.Result result) {
        try {
            String safeLang = result.languageCode == null ? "und" : result.languageCode.replaceAll("[^A-Za-z0-9_-]", "_");
            String fileName = result.videoId + "_" + safeLang + ".json";
            File dir = new File(context.getFilesDir(), "transcripts");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, fileName);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(result.json().getBytes(StandardCharsets.UTF_8));
            }

            List<Entry> entries = list(context);
            List<Entry> fresh = new ArrayList<>();
            Entry newEntry = new Entry();
            newEntry.videoId = result.videoId;
            newEntry.title = result.title;
            newEntry.language = result.languageCode;
            newEntry.savedAt = System.currentTimeMillis();
            newEntry.fileName = fileName;
            fresh.add(newEntry);
            for (Entry e : entries) {
                if (!fileName.equals(e.fileName) && fresh.size() < MAX) fresh.add(e);
            }
            writeIndex(context, fresh);
        } catch (Exception ignored) {}
    }

    public static List<Entry> list(Context context) {
        List<Entry> out = new ArrayList<>();
        try {
            SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray a = new JSONArray(p.getString(INDEX, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x == null) continue;
                Entry e = new Entry();
                e.videoId = x.optString("videoId", "");
                e.title = x.optString("title", "");
                e.language = x.optString("language", "");
                e.savedAt = x.optLong("savedAt", 0);
                e.fileName = x.optString("fileName", "");
                if (!e.fileName.isEmpty()) out.add(e);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static YouTubeTranscriptClient.Result load(Context context, Entry entry) throws Exception {
        File f = new File(new File(context.getFilesDir(), "transcripts"), entry.fileName);
        byte[] data;
        try (FileInputStream in = new FileInputStream(f)) {
            data = new byte[(int) f.length()];
            int offset = 0;
            while (offset < data.length) {
                int n = in.read(data, offset, data.length - offset);
                if (n < 0) break;
                offset += n;
            }
        }
        return YouTubeTranscriptClient.Result.fromJson(new String(data, StandardCharsets.UTF_8));
    }

    public static void clear(Context context) {
        File dir = new File(context.getFilesDir(), "transcripts");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(INDEX).apply();
    }

    private static void writeIndex(Context context, List<Entry> entries) throws Exception {
        JSONArray a = new JSONArray();
        for (Entry e : entries) {
            JSONObject x = new JSONObject();
            x.put("videoId", e.videoId);
            x.put("title", e.title);
            x.put("language", e.language);
            x.put("savedAt", e.savedAt);
            x.put("fileName", e.fileName);
            a.put(x);
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(INDEX, a.toString()).apply();
    }
}
