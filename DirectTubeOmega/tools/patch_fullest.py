#!/usr/bin/env python3
from pathlib import Path

root = Path('DirectTubeOmega/app/src/main/java/com/rivalomega/directtube')
client = root / 'YouTubeTranscriptClient.java'
main = root / 'MainActivity.java'

# Patch transcript client: site-compatible v2 first, direct YouTube routes remain as fallbacks.
s = client.read_text(encoding='utf-8')
start = s.index('    public static Result fetch(String urlOrId, String preferredLanguages) throws Exception {')
end = s.index('    public static String extractVideoId(String raw) {', start)
new_fetch = '''    public static Result fetch(String urlOrId, String preferredLanguages) throws Exception {\n        String videoId = extractVideoId(urlOrId);\n        if (videoId == null) throw new Exception("Could not find an 11-character YouTube video ID.");\n\n        // Route 0: mirror the current youtube-transcript.io browser path. This is\n        // intentionally first because that route is our completeness benchmark.\n        try {\n            Result r = SiteTranscriptCompat.fetch(videoId, preferredLanguages);\n            if (r != null && !r.segments.isEmpty()) return r;\n        } catch (Exception ignored) {\n            // Keep the app useful if the hosted site changes or is temporarily unavailable.\n        }\n\n        String[] prefs = splitLanguages(preferredLanguages);\n        WatchState watch = loadWatchState(videoId);\n\n        // Direct fallback 1: Android player caption track.\n        try {\n            Result r = fetchViaAndroidCaptionTrack(videoId, prefs, watch);\n            if (!r.segments.isEmpty()) return r;\n        } catch (Exception ignored) {}\n\n        // Direct fallback 2: YouTube transcript panel.\n        try {\n            Result r = fetchViaTranscriptPanel(videoId, watch);\n            if (!r.segments.isEmpty()) return r;\n        } catch (Exception ignored) {}\n\n        // Direct fallback 3: watch-page tracks.\n        try {\n            Result r = fetchViaWatchPlayerTracks(videoId, prefs, watch);\n            if (!r.segments.isEmpty()) return r;\n        } catch (Exception ignored) {}\n\n        if (watch.player != null) {\n            JSONObject ps = watch.player.optJSONObject("playabilityStatus");\n            if (ps != null && !"OK".equals(ps.optString("status", "OK"))) {\n                String reason = ps.optString("reason", ps.optString("status", "Unavailable"));\n                throw new Exception("Video is not playable: " + reason);\n            }\n        }\n        throw new Exception("No usable transcript was exposed for this video.");\n    }\n\n'''
s = s[:start] + new_fetch + s[end:]
client.write_text(s, encoding='utf-8')

# Patch MainActivity so the big Extract button calls the full cascade directly.
m = main.read_text(encoding='utf-8')
start = m.index('    private void fetchDefault() {')
end = m.index('    private YouTubeTranscriptClient.Result fetchPreferredFromProbe', start)
new_default = '''    private void fetchDefault() {\n        String raw = input.getText().toString().trim();\n        String langs = languages.getText().toString().trim();\n        if (raw.isEmpty()) {\n            toast("Paste or share a YouTube link.");\n            return;\n        }\n\n        busy("Extracting full transcript…");\n        executor.submit(() -> {\n            try {\n                YouTubeTranscriptClient.Result r = YouTubeTranscriptClient.fetch(raw, langs);\n                runOnUiThread(() -> acceptResult(null, r));\n            } catch (Exception e) {\n                runOnUiThread(() -> fail(e));\n            }\n        });\n    }\n\n'''
m = m[:start] + new_default + m[end:]
m = m.replace('String mode = r.generated ? "Auto-generated captions" : "Manual captions";',
'''String mode = r.sourceMethod != null && r.sourceMethod.startsWith("youtube-transcript-io")\n                ? "Full transcript"\n                : (r.generated ? "Auto-generated captions" : "Manual captions");''')
main.write_text(m, encoding='utf-8')

print('PATCH_OK')
