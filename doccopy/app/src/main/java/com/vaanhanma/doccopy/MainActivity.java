package com.vaanhanma.doccopy;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Html;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.rendering.ImageType;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final int PICK_DOCUMENT = 1001;
    private static final int SAVE_TEXT = 1002;
    private static final int PREVIEW_LIMIT = 120_000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    private TextView statusView;
    private TextView previewView;
    private ProgressBar progressBar;
    private CheckBox ocrCheck;
    private Button copyButton;
    private Button saveButton;
    private Button shareButton;

    private File extractedFile;
    private String selectedName = "document";
    private long extractedChars = 0;
    private final StringBuilder preview = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        extractedFile = new File(getCacheDir(), "doccopy_all_extracted.txt");
        buildUi();
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.WHITE);

        TextView titleView = new TextView(this);
        titleView.setText("DOC COPY ALL");
        titleView.setTextSize(25);
        titleView.setTextColor(Color.BLACK);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(titleView);

        TextView sub = new TextView(this);
        sub.setText("Extract the entire document, then copy every page in one tap.");
        sub.setTextSize(15);
        sub.setTextColor(Color.DKGRAY);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        Button pick = new Button(this);
        pick.setText("SELECT DOCUMENT");
        pick.setOnClickListener(v -> pickDocument());
        root.addView(pick, fullWidth());

        ocrCheck = new CheckBox(this);
        ocrCheck.setText("OCR scanned / image-only PDF pages");
        ocrCheck.setChecked(true);
        root.addView(ocrCheck);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        root.addView(progressBar, fullWidth());

        statusView = new TextView(this);
        statusView.setText("Choose a PDF, DOCX, TXT, MD, HTML, XML, EPUB, CSV, JSON, or RTF file.");
        statusView.setTextSize(14);
        statusView.setTextColor(Color.DKGRAY);
        statusView.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        copyButton = actionButton("COPY ALL");
        copyButton.setOnClickListener(v -> copyAll());
        actions.addView(copyButton, weighted());

        saveButton = actionButton("SAVE TXT");
        saveButton.setOnClickListener(v -> saveTxt());
        actions.addView(saveButton, weighted());

        shareButton = actionButton("SHARE");
        shareButton.setOnClickListener(v -> shareTxt());
        actions.addView(shareButton, weighted());

        root.addView(actions, fullWidth());

        TextView previewLabel = new TextView(this);
        previewLabel.setText("Preview only. COPY ALL always uses the complete extracted document.");
        previewLabel.setTextSize(13);
        previewLabel.setTextColor(Color.GRAY);
        previewLabel.setPadding(0, dp(12), 0, dp(4));
        root.addView(previewLabel);

        previewView = new TextView(this);
        previewView.setTextSize(14);
        previewView.setTextColor(Color.rgb(20, 20, 20));
        previewView.setTextIsSelectable(true);
        previewView.setMovementMethod(new ScrollingMovementMethod());
        previewView.setPadding(dp(10), dp(10), dp(10), dp(10));
        previewView.setBackgroundColor(Color.rgb(246, 246, 246));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(previewView);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollLp);

        setContentView(root);
        setActionsEnabled(false);
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private Button actionButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        return b;
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void pickDocument() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain", "text/markdown", "text/html", "text/xml", "application/xml",
                "text/csv", "application/json", "application/rtf", "text/rtf", "application/epub+zip"
        });
        startActivityForResult(i, PICK_DOCUMENT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        if (requestCode == PICK_DOCUMENT) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            } catch (Exception ignored) { }
            selectedName = getDisplayName(uri);
            extractDocument(uri);
        } else if (requestCode == SAVE_TEXT) {
            worker.execute(() -> {
                try (InputStream in = new FileInputStream(extractedFile);
                     OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    if (out == null) throw new IllegalStateException("Cannot open destination");
                    copyBytes(in, out);
                    runOnUiThread(() -> toast("Saved full text"));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("Save failed: " + shortError(e)));
                }
            });
        }
    }

    private void extractDocument(Uri uri) {
        setBusy(true, "Opening " + selectedName + "…");
        worker.execute(() -> {
            preview.setLength(0);
            extractedChars = 0;
            try {
                if (extractedFile.exists()) extractedFile.delete();
                try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(extractedFile), StandardCharsets.UTF_8), 64 * 1024)) {
                    String lower = selectedName.toLowerCase(Locale.US);
                    String mime = getContentResolver().getType(uri);
                    if (mime == null) mime = "";

                    if (lower.endsWith(".pdf") || mime.equals("application/pdf")) {
                        extractPdf(uri, out);
                    } else if (lower.endsWith(".docx") || mime.contains("wordprocessingml")) {
                        extractDocx(uri, out);
                    } else if (lower.endsWith(".epub") || mime.equals("application/epub+zip")) {
                        extractEpub(uri, out);
                    } else if (lower.endsWith(".html") || lower.endsWith(".htm") || mime.equals("text/html")) {
                        append(out, Html.fromHtml(readWholeUri(uri), Html.FROM_HTML_MODE_LEGACY).toString());
                    } else if (lower.endsWith(".xml") || mime.contains("xml")) {
                        append(out, extractGenericXml(readWholeUri(uri)));
                    } else if (lower.endsWith(".rtf") || mime.contains("rtf")) {
                        append(out, stripRtf(readWholeUri(uri)));
                    } else {
                        extractPlainText(uri, out);
                    }
                }

                runOnUiThread(() -> {
                    setBusy(false, "Ready • " + formatCount(extractedChars) + " characters extracted from the whole document");
                    previewView.setText(preview.toString());
                    setActionsEnabled(extractedChars > 0);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false, "Extraction failed: " + shortError(e));
                    previewView.setText("");
                    setActionsEnabled(false);
                });
            }
        });
    }

    private void extractPdf(Uri uri, BufferedWriter out) throws Exception {
        try (InputStream raw = getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("Cannot open PDF");
            try (PDDocument doc = PDDocument.load(new BufferedInputStream(raw))) {
                int pages = doc.getNumberOfPages();
                PDFTextStripper stripper = new PDFTextStripper();
                PDFRenderer renderer = ocrCheck.isChecked() ? new PDFRenderer(doc) : null;

                for (int page = 1; page <= pages; page++) {
                    updateProgress(page - 1, pages, "Extracting PDF page " + page + " of " + pages);
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    StringWriter sw = new StringWriter();
                    stripper.writeText(doc, sw);
                    String pageText = sw.toString().trim();

                    if (pageText.length() < 3 && renderer != null) {
                        updateProgress(page - 1, pages, "OCR page " + page + " of " + pages);
                        Bitmap bitmap = renderer.renderImageWithDPI(page - 1, 180, ImageType.RGB);
                        pageText = recognizeBitmap(bitmap);
                        bitmap.recycle();
                    }

                    append(out, "\n\n===== PAGE " + page + " / " + pages + " =====\n\n");
                    append(out, pageText);
                    append(out, "\n");
                }
                updateProgress(pages, pages, "PDF extraction complete");
            }
        }
    }

    private String recognizeBitmap(Bitmap bitmap) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final String[] result = {""};
        final Exception[] error = {null};
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> {
                    result[0] = text.getText();
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    error[0] = e;
                    latch.countDown();
                });
        if (!latch.await(90, TimeUnit.SECONDS)) throw new Exception("OCR timed out");
        if (error[0] != null) throw error[0];
        return result[0] == null ? "" : result[0];
    }

    private void extractDocx(Uri uri, BufferedWriter out) throws Exception {
        Map<String, String> parts = new HashMap<>();
        try (InputStream raw = getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String n = entry.getName();
                if (n.equals("word/document.xml") || n.matches("word/header\\d+\\.xml") ||
                        n.matches("word/footer\\d+\\.xml") || n.equals("word/footnotes.xml") ||
                        n.equals("word/endnotes.xml") || n.equals("word/comments.xml")) {
                    parts.put(n, extractWordXml(readEntry(zip)));
                }
                zip.closeEntry();
            }
        }

        String body = parts.remove("word/document.xml");
        if (body != null) append(out, body);
        List<String> names = new ArrayList<>(parts.keySet());
        Collections.sort(names);
        for (String n : names) {
            String text = parts.get(n);
            if (text == null || text.trim().isEmpty()) continue;
            append(out, "\n\n===== " + n.replace("word/", "").replace(".xml", "").toUpperCase(Locale.US) + " =====\n\n");
            append(out, text);
        }
    }

    private String extractWordXml(String xml) throws Exception {
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(true);
        XmlPullParser p = f.newPullParser();
        p.setInput(new StringReader(xml));
        StringBuilder b = new StringBuilder();
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = p.getName();
                if ("t".equals(name)) b.append(p.nextText());
                else if ("tab".equals(name)) b.append('\t');
                else if ("br".equals(name) || "cr".equals(name)) b.append('\n');
            } else if (event == XmlPullParser.END_TAG && "p".equals(p.getName())) {
                b.append('\n');
            }
            event = p.next();
        }
        return b.toString();
    }

    private void extractEpub(Uri uri, BufferedWriter out) throws Exception {
        List<EpubPart> parts = new ArrayList<>();
        try (InputStream raw = getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String n = e.getName().toLowerCase(Locale.US);
                if (!e.isDirectory() && (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))) {
                    String text = Html.fromHtml(readEntry(zip), Html.FROM_HTML_MODE_LEGACY).toString().trim();
                    if (!text.isEmpty()) parts.add(new EpubPart(e.getName(), text));
                }
                zip.closeEntry();
            }
        }
        Collections.sort(parts, (a, b) -> a.name.compareToIgnoreCase(b.name));
        int i = 0;
        for (EpubPart part : parts) {
            i++;
            updateProgress(i - 1, parts.size(), "Extracting EPUB section " + i + " of " + parts.size());
            append(out, "\n\n===== SECTION " + i + " =====\n\n");
            append(out, part.text);
        }
        updateProgress(parts.size(), parts.size(), "EPUB extraction complete");
    }

    private void extractPlainText(Uri uri, BufferedWriter out) throws Exception {
        try (InputStream raw = getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("Cannot open file");
            BufferedInputStream in = new BufferedInputStream(raw);
            in.mark(4);
            byte[] bom = new byte[3];
            int n = in.read(bom);
            Charset charset = StandardCharsets.UTF_8;
            int skip = 0;
            if (n >= 2 && (bom[0] & 0xFF) == 0xFF && (bom[1] & 0xFF) == 0xFE) {
                charset = StandardCharsets.UTF_16LE;
                skip = 2;
            } else if (n >= 2 && (bom[0] & 0xFF) == 0xFE && (bom[1] & 0xFF) == 0xFF) {
                charset = StandardCharsets.UTF_16BE;
                skip = 2;
            } else if (n >= 3 && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF) {
                skip = 3;
            }
            in.reset();
            if (skip > 0) {
                long left = skip;
                while (left > 0) left -= in.skip(left);
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, charset), 64 * 1024)) {
                char[] buf = new char[16 * 1024];
                int got;
                while ((got = r.read(buf)) != -1) append(out, new String(buf, 0, got));
            }
        }
    }

    private String extractGenericXml(String xml) throws Exception {
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(true);
        XmlPullParser p = f.newPullParser();
        p.setInput(new StringReader(xml));
        StringBuilder b = new StringBuilder();
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT) {
                String t = p.getText();
                if (t != null && !t.trim().isEmpty()) b.append(t.trim()).append('\n');
            }
            event = p.next();
        }
        return b.toString();
    }

    private String stripRtf(String rtf) {
        String s = rtf.replaceAll("\\\\par[d]?", "\n");
        s = s.replaceAll("\\\\'[0-9a-fA-F]{2}", "");
        s = s.replaceAll("\\\\[a-zA-Z]+-?\\d* ?", "");
        return s.replace("{", "").replace("}", "");
    }

    private String readWholeUri(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Cannot open file");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            copyBytes(in, b);
            return b.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String readEntry(ZipInputStream zip) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = zip.read(buf)) != -1) b.write(buf, 0, n);
        return b.toString(StandardCharsets.UTF_8.name());
    }

    private void append(BufferedWriter out, String text) throws Exception {
        if (text == null || text.isEmpty()) return;
        out.write(text);
        extractedChars += text.length();
        if (preview.length() < PREVIEW_LIMIT) {
            int room = PREVIEW_LIMIT - preview.length();
            preview.append(text, 0, Math.min(room, text.length()));
            if (preview.length() == PREVIEW_LIMIT) {
                preview.append("\n\n[Preview capped here. COPY ALL / SAVE TXT / SHARE still use the entire extracted document.]\n");
            }
        }
    }

    private void copyAll() {
        if (!extractedFile.exists()) return;
        setBusy(true, "Loading the complete extracted document into clipboard…");
        worker.execute(() -> {
            try {
                String all = readFile(extractedFile);
                runOnUiThread(() -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText(selectedName + " — full text", all));
                        setBusy(false, "Copied ALL " + formatCount(all.length()) + " characters — full document, not one page");
                        toast("Whole document copied");
                    } catch (Exception e) {
                        setBusy(false, "Clipboard rejected this very large document. SAVE TXT keeps every character.");
                        toast("Clipboard too large for Android; use SAVE TXT or SHARE");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> setBusy(false, "Copy failed: " + shortError(e)));
            }
        });
    }

    private void saveTxt() {
        if (!extractedFile.exists()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        String base = selectedName.replaceAll("(?i)\\.[a-z0-9]{1,8}$", "");
        i.putExtra(Intent.EXTRA_TITLE, base + "_FULL_TEXT.txt");
        startActivityForResult(i, SAVE_TEXT);
    }

    private void shareTxt() {
        if (!extractedFile.exists()) return;
        worker.execute(() -> {
            try {
                File share = new File(getCacheDir(), safeFileBase(selectedName) + "_FULL_TEXT.txt");
                try (InputStream in = new FileInputStream(extractedFile);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(share))) {
                    copyBytes(in, out);
                }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", share);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, selectedName + " — full extracted text");
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "Share full document text")));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Share failed: " + shortError(e)));
            }
        });
    }

    private String readFile(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream((int)Math.min(file.length(), 8_000_000));
            copyBytes(in, b);
            return b.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void copyBytes(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
    }

    private String getDisplayName(Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return name == null ? "document" : name;
    }

    private void updateProgress(int done, int total, String msg) {
        int pct = total <= 0 ? 0 : Math.max(0, Math.min(100, Math.round(done * 100f / total)));
        runOnUiThread(() -> {
            progressBar.setProgress(pct);
            progressBar.setIndeterminate(false);
            statusView.setText(msg);
        });
    }

    private void setBusy(boolean busy, String message) {
        progressBar.setIndeterminate(busy);
        statusView.setText(message);
        if (busy) setActionsEnabled(false);
    }

    private void setActionsEnabled(boolean enabled) {
        copyButton.setEnabled(enabled);
        saveButton.setEnabled(enabled);
        shareButton.setEnabled(enabled);
    }

    private String shortError(Throwable t) {
        String s = t.getMessage();
        if (s == null || s.trim().isEmpty()) s = t.getClass().getSimpleName();
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String formatCount(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.2fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(Locale.US, "%.1fK", n / 1_000.0);
        return Long.toString(n);
    }

    private String safeFileBase(String name) {
        String base = name.replaceAll("(?i)\\.[a-z0-9]{1,8}$", "");
        base = base.replaceAll("[^a-zA-Z0-9._-]+", "_");
        return base.isEmpty() ? "document" : base;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recognizer.close();
        worker.shutdownNow();
    }

    private static class EpubPart {
        final String name;
        final String text;
        EpubPart(String name, String text) {
            this.name = name;
            this.text = text;
        }
    }
}
