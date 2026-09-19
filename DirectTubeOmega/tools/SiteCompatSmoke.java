package com.rivalomega.directtube;

public final class SiteCompatSmoke {
    public static void main(String[] args) throws Exception {
        String id = args.length > 0 ? args[0] : "A7cfV5rwZdk";
        YouTubeTranscriptClient.Result r = SiteTranscriptCompat.fetch(id, "en");
        String text = r.plainText().replaceAll("\\s+", " ").trim();
        int words = text.isEmpty() ? 0 : text.split("\\s+").length;
        long last = 0;
        for (YouTubeTranscriptClient.Segment s : r.segments) {
            last = Math.max(last, s.startMs + s.durationMs);
        }

        System.out.println("JAVA_SITE_COMPAT source=" + r.sourceMethod);
        System.out.println("JAVA_SITE_COMPAT segments=" + r.segments.size());
        System.out.println("JAVA_SITE_COMPAT words=" + words);
        System.out.println("JAVA_SITE_COMPAT lastMs=" + last);
        System.out.println("JAVA_SITE_COMPAT first=" + text.substring(0, Math.min(180, text.length())));
        System.out.println("JAVA_SITE_COMPAT tail=" + text.substring(Math.max(0, text.length() - 180)));

        if (!"youtube-transcript-io-v2".equals(r.sourceMethod)) throw new AssertionError("wrong source");
        if (r.segments.size() < 1100) throw new AssertionError("too few non-empty segments: " + r.segments.size());
        if (words < 7500) throw new AssertionError("too few words: " + words);
        if (last < 3099000L) throw new AssertionError("transcript ended early: " + last);
        if (!text.startsWith("All right. Hope everyone's having a great Friday night.")) {
            throw new AssertionError("wrong beginning: " + text.substring(0, Math.min(100, text.length())));
        }
        if (!text.endsWith("Have a great night.")) {
            throw new AssertionError("wrong ending: " + text.substring(Math.max(0, text.length() - 100)));
        }
        System.out.println("JAVA_FULL_TRANSCRIPT_GATE_OK");
    }
}
