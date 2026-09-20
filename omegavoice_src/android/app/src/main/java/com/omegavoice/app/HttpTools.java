package com.omegavoice.app;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class HttpTools {
    static final int CONNECT_TIMEOUT = 15_000;
    static final int READ_TIMEOUT = 20 * 60_000;

    private HttpTools() {}

    static String normalizeBase(String s) {
        s = s == null ? "" : s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!(s.startsWith("http://") || s.startsWith("https://"))) {
            throw new IllegalArgumentException("Engine URL must start with http:// or https://");
        }
        return s;
    }

    static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json, audio/wav, */*");
        return c;
    }

    static String getText(String url) throws IOException {
        HttpURLConnection c = open(url, "GET");
        try { return readResponseText(c); } finally { c.disconnect(); }
    }

    static String deleteText(String url) throws IOException {
        HttpURLConnection c = open(url, "DELETE");
        try { return readResponseText(c); } finally { c.disconnect(); }
    }

    static String readResponseText(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : readAllText(in);
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code + ": " + text);
        return text;
    }

    static String readAllText(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) b.write(buf, 0, n);
        return b.toString(StandardCharsets.UTF_8.name());
    }

    static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static String postMultipartText(String url, Map<String,String> fields, List<Uri> files, ContentResolver resolver) throws IOException {
        HttpURLConnection c = postMultipart(url, fields, files, resolver);
        try { return readResponseText(c); } finally { c.disconnect(); }
    }

    static byte[] postMultipartBytes(String url, Map<String,String> fields) throws IOException {
        HttpURLConnection c = postMultipart(url, fields, Collections.emptyList(), null);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            byte[] data = in == null ? new byte[0] : readAllBytes(in);
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + ": " + new String(data, StandardCharsets.UTF_8));
            String type = c.getContentType();
            if (type != null && type.contains("application/json")) {
                throw new IOException("Engine returned JSON instead of WAV: " + new String(data, StandardCharsets.UTF_8));
            }
            if (data.length < 44) throw new IOException("Engine returned an empty/invalid WAV");
            return data;
        } finally { c.disconnect(); }
    }

    private static HttpURLConnection postMultipart(String url, Map<String,String> fields, List<Uri> files, ContentResolver resolver) throws IOException {
        String boundary = "Omega" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection c = open(url, "POST");
        c.setDoOutput(true);
        c.setChunkedStreamingMode(64 * 1024);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(c.getOutputStream()))) {
            for (Map.Entry<String,String> e : fields.entrySet()) {
                writeAscii(out, "--" + boundary + "\r\n");
                writeAscii(out, "Content-Disposition: form-data; name=\"" + safeHeader(e.getKey()) + "\"\r\n\r\n");
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                writeAscii(out, "\r\n");
            }
            if (files != null && resolver != null) {
                int i = 0;
                for (Uri uri : files) {
                    writeAscii(out, "--" + boundary + "\r\n");
                    String mime = resolver.getType(uri);
                    String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.US);
                    String ext = path.endsWith(".mp3") ? ".mp3" : path.endsWith(".m4a") ? ".m4a" : path.endsWith(".aac") ? ".aac" : path.endsWith(".ogg") ? ".ogg" : path.endsWith(".flac") ? ".flac" : ".wav";
                    if (mime != null && mime.contains("mp4")) ext = ".m4a";
                    if (mime != null && mime.contains("mpeg")) ext = ".mp3";
                    writeAscii(out, "Content-Disposition: form-data; name=\"files\"; filename=\"ref_" + i + ext + "\"\r\n");
                    writeAscii(out, "Content-Type: " + (mime == null ? "audio/*" : mime) + "\r\n\r\n");
                    InputStream opened = "file".equalsIgnoreCase(uri.getScheme())
                            ? new FileInputStream(new File(Objects.requireNonNull(uri.getPath())))
                            : resolver.openInputStream(uri);
                    try (InputStream in = opened) {
                        if (in == null) throw new IOException("Cannot open reference: " + uri);
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                    }
                    writeAscii(out, "\r\n");
                    i++;
                }
            }
            writeAscii(out, "--" + boundary + "--\r\n");
            out.flush();
        }
        return c;
    }

    private static void writeAscii(OutputStream out, String s) throws IOException { out.write(s.getBytes(StandardCharsets.UTF_8)); }
    private static String safeHeader(String s) { return s.replace("\"", "").replace("\r", "").replace("\n", ""); }
}
