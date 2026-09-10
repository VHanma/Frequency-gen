package com.vaanhanma.doccopy;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
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
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
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
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final int PICK_DOCUMENT = 1001;
    private static final int SAVE_TEXT = 1002;
    private static final int PREVIEW_LIMIT = 150_000;
    private static final int DIRECT_CLIPBOARD_UTF8_LIMIT = 360_000;
    private static final int CHUNK_CHARS = 160_000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Integer, TextRecognizer> recognizers = new HashMap<>();

    private TextView statusView;
    private TextView previewView;
    private ProgressBar progressBar;
    private CheckBox smartOcrCheck;
    private CheckBox forceOcrCheck;
    private CheckBox cleanCheck;
    private Spinner scriptSpinner;
    private EditText pageRangeEdit;
    private EditText passwordEdit;
    private LinearLayout advancedBox;
    private Button copyButton;
    private Button chunkButton;
    private Button saveButton;
    private Button shareButton;
    private Button cancelButton;

    private File extractedFile;
    private String selectedName = "document";
    private long extractedChars = 0;
    private long chunkCursorChars = 0;
    private final StringBuilder preview = new StringBuilder();
    private volatile boolean cancelled = false;

    private int pdfPagesSeen = 0;
    private int pdfNativePages = 0;
    private int pdfOcrPages = 0;
    private int pdfLowConfidencePages = 0;
    private int documentsDone = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        extractedFile = new File(getFilesDir(), "doccopy_omega_last.txt");
        buildUi();
        restoreLastExtraction();
    }

    private void buildUi() {
        int pad = dp(14);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("DOC COPY Ω");
        title.setTextSize(25);
        title.setTextColor(Color.BLACK);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Whole-document extraction • smart OCR • giant-document copy");
        sub.setTextSize(14);
        sub.setTextColor(Color.DKGRAY);
        sub.setPadding(0, dp(3), 0, dp(9));
        root.addView(sub);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);

        Button pick = new Button(this);
        pick.setText("SELECT DOCS");
        pick.setOnClickListener(v -> pickDocuments());
        topRow.addView(pick, weighted());

        Button advanced = new Button(this);
        advanced.setText("ADVANCED");
        advanced.setOnClickListener(v -> advancedBox.setVisibility(
                advancedBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        topRow.addView(advanced, weighted());
        root.addView(topRow, fullWidth());

        smartOcrCheck = new CheckBox(this);
        smartOcrCheck.setText("Smart OCR for scanned / suspicious PDF pages");
        smartOcrCheck.setChecked(true);
        root.addView(smartOcrCheck);

        cleanCheck = new CheckBox(this);
        cleanCheck.setText("Clean soft hyphens + broken line wraps");
        cleanCheck.setChecked(false);
        root.addView(cleanCheck);

        advancedBox = new LinearLayout(this);
        advancedBox.setOrientation(LinearLayout.VERTICAL);
        advancedBox.setVisibility(View.GONE);
        advancedBox.setPadding(dp(8), 0, dp(8), dp(6));

        forceOcrCheck = new CheckBox(this);
        forceOcrCheck.setText("Force OCR every selected PDF page");
        forceOcrCheck.setChecked(false);
        advancedBox.addView(forceOcrCheck);

        TextView scriptLabel = smallLabel("OCR script");
        advancedBox.addView(scriptLabel);
        scriptSpinner = new Spinner(this);
        String[] scripts = {"Latin", "Chinese", "Japanese", "Korean", "Devanagari"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, scripts);
        scriptSpinner.setAdapter(adapter);
        advancedBox.addView(scriptSpinner, fullWidth());

        pageRangeEdit = new EditText(this);
        pageRangeEdit.setHint("PDF pages: all   or   1-20,25,40-55");
        pageRangeEdit.setSingleLine(true);
        pageRangeEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        advancedBox.addView(pageRangeEdit, fullWidth());

        passwordEdit = new EditText(this);
        passwordEdit.setHint("PDF password (leave blank if none)");
        passwordEdit.setSingleLine(true);
        passwordEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        advancedBox.addView(passwordEdit, fullWidth());

        root.addView(advancedBox, fullWidth());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        root.addView(progressBar, fullWidth());

        statusView = new TextView(this);
        statusView.setText("Select one document or a whole batch.");
        statusView.setTextSize(13);
        statusView.setTextColor(Color.DKGRAY);
        statusView.setPadding(0, dp(6), 0, dp(6));
        root.addView(statusView);

        LinearLayout actions1 = new LinearLayout(this);
        actions1.setOrientation(LinearLayout.HORIZONTAL);
        actions1.setGravity(Gravity.CENTER);

        copyButton = actionButton("COPY ALL");
        copyButton.setOnClickListener(v -> copyAll());
        actions1.addView(copyButton, weighted());

        saveButton = actionButton("SAVE TXT");
        saveButton.setOnClickListener(v -> saveTxt());
        actions1.addView(saveButton, weighted());

        shareButton = actionButton("SHARE");
        shareButton.setOnClickListener(v -> shareTxt());
        actions1.addView(shareButton, weighted());
        root.addView(actions1, fullWidth());

        LinearLayout actions2 = new LinearLayout(this);
        actions2.setOrientation(LinearLayout.HORIZONTAL);
        actions2.setGravity(Gravity.CENTER);

        chunkButton = actionButton("COPY CHUNK");
        chunkButton.setOnClickListener(v -> copyNextChunk());
        actions2.addView(chunkButton, weighted());

        cancelButton = actionButton("CANCEL");
        cancelButton.setOnClickListener(v -> {
            cancelled = true;
            statusView.setText("Cancelling after the current extraction step…");
        });
        cancelButton.setEnabled(false);
        actions2.addView(cancelButton, weighted());
        root.addView(actions2, fullWidth());

        TextView previewLabel = smallLabel(
                "Preview only. COPY ALL, SAVE and SHARE use the complete extracted text.");
        previewLabel.setPadding(0, dp(8), 0, dp(3));
        root.addView(previewLabel);

        previewView = new TextView(this);
        previewView.setTextSize(14);
        previewView.setTextColor(Color.rgb(20, 20, 20));
        previewView.setTextIsSelectable(true);
        previewView.setMovementMethod(new ScrollingMovementMethod());
        previewView.setPadding(dp(9), dp(9), dp(9), dp(9));
        previewView.setBackgroundColor(Color.rgb(246, 246, 246));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(previewView);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollLp);

        setContentView(root);
        setActionsEnabled(false);
    }

    private TextView smallLabel(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(12);
        v.setTextColor(Color.GRAY);
        return v;
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

    private void pickDocuments() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
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

        if (requestCode == PICK_DOCUMENT) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri u = data.getClipData().getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            for (Uri uri : uris) persistReadPermission(uri, data.getFlags());
            extractDocuments(uris);
        } else if (requestCode == SAVE_TEXT) {
            Uri uri = data.getData();
            if (uri == null || !hasExtraction()) return;
            worker.execute(() -> {
                try (InputStream in = new FileInputStream(extractedFile);
                     OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    if (out == null) throw new IllegalStateException("Cannot open destination");
                    copyBytes(in, out);
                    runOnUiThread(() -> toast("Saved every extracted character"));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("Save failed: " + shortError(e)));
                }
            });
        }
    }

    private void persistReadPermission(Uri uri, int flags) {
        try {
            int take = flags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, take);
        } catch (Exception ignored) { }
    }

    private void extractDocuments(List<Uri> uris) {
        final ExtractionOptions opt = new ExtractionOptions(
                smartOcrCheck.isChecked(),
                forceOcrCheck.isChecked(),
                cleanCheck.isChecked(),
                scriptSpinner.getSelectedItemPosition(),
                pageRangeEdit.getText().toString().trim(),
                passwordEdit.getText().toString());

        cancelled = false;
        resetStats();
        setBusy(true, "Preparing " + uris.size() + (uris.size() == 1 ? " document…" : " documents…"));

        worker.execute(() -> {
            preview.setLength(0);
            extractedChars = 0;
            chunkCursorChars = 0;
            try {
                if (extractedFile.exists() && !extractedFile.delete()) {
                    throw new IllegalStateException("Cannot replace previous extraction");
                }
                try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(extractedFile), StandardCharsets.UTF_8), 64 * 1024)) {
                    for (int i = 0; i < uris.size(); i++) {
                        checkCancelled();
                        Uri uri = uris.get(i);
                        String name = getDisplayName(uri);
                        if (uris.size() > 1) {
                            appendRaw(out, "\n\n############################################################\n");
                            appendRaw(out, "DOCUMENT " + (i + 1) + " / " + uris.size() + ": " + name + "\n");
                            appendRaw(out, "############################################################\n\n");
                        }
                        extractOne(uri, name, out, opt, i + 1, uris.size());
                        documentsDone++;
                    }
                }

                selectedName = uris.size() == 1 ? getDisplayName(uris.get(0))
                        : "DocCopyOmega_Batch_" + uris.size() + "_documents";
                saveLastState();
                final String report = buildReport();
                runOnUiThread(() -> {
                    progressBar.setProgress(100);
                    setBusy(false, report);
                    previewView.setText(preview.toString());
                });
            } catch (CancelledException e) {
                runOnUiThread(() -> {
                    setBusy(false, "Cancelled. Partial extraction discarded.");
                    setActionsEnabled(false);
                    previewView.setText("");
                });
                if (extractedFile.exists()) extractedFile.delete();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false, "Extraction failed: " + shortError(e));
                    setActionsEnabled(false);
                    previewView.setText("");
                });
            }
        });
    }

    private void extractOne(Uri uri, String name, BufferedWriter out, ExtractionOptions opt,
                            int docIndex, int docTotal) throws Exception {
        String lower = name.toLowerCase(Locale.US);
        String mime = getContentResolver().getType(uri);
        if (mime == null) mime = "";
        String prefix = docTotal > 1 ? "Doc " + docIndex + "/" + docTotal + " • " : "";

        if (lower.endsWith(".pdf") || mime.equals("application/pdf")) {
            extractPdf(uri, out, opt, prefix);
        } else if (lower.endsWith(".docx") || mime.contains("wordprocessingml")) {
            updateStatus(prefix + "Extracting DOCX…");
            extractDocx(uri, out, opt.cleanText);
        } else if (lower.endsWith(".epub") || mime.equals("application/epub+zip")) {
            extractEpub(uri, out, opt.cleanText, prefix);
        } else if (lower.endsWith(".html") || lower.endsWith(".htm") || mime.equals("text/html")) {
            updateStatus(prefix + "Extracting HTML…");
            String html = readTextUri(uri);
            appendText(out, Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString(), opt.cleanText);
        } else if (lower.endsWith(".xml") || mime.contains("xml")) {
            updateStatus(prefix + "Extracting XML…");
            appendText(out, extractGenericXml(readTextUri(uri)), opt.cleanText);
        } else if (lower.endsWith(".rtf") || mime.contains("rtf")) {
            updateStatus(prefix + "Extracting RTF…");
            appendText(out, parseRtf(readTextUri(uri)), opt.cleanText);
        } else {
            updateStatus(prefix + "Extracting text…");
            extractPlainText(uri, out, opt.cleanText);
        }
    }

    private void extractPdf(Uri uri, BufferedWriter out, ExtractionOptions opt, String prefix) throws Exception {
        try (InputStream raw = getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("Cannot open PDF");
            try (PDDocument doc = opt.password.isEmpty()
                    ? PDDocument.load(new BufferedInputStream(raw))
                    : PDDocument.load(new BufferedInputStream(raw), opt.password)) {

                int pages = doc.getNumberOfPages();
                boolean[] wanted = parsePageRange(opt.pageRange, pages);
                int selectedPages = countTrue(wanted);
                PDFTextStripper stripper = new PDFTextStripper();
                PDFRenderer renderer = (opt.smartOcr || opt.forceOcr) ? new PDFRenderer(doc) : null;
                int done = 0;

                for (int page = 1; page <= pages; page++) {
                    checkCancelled();
                    if (!wanted[page - 1]) continue;
                    done++;
                    pdfPagesSeen++;
                    updateProgress(done - 1, selectedPages,
                            prefix + "PDF page " + page + "/" + pages);

                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    StringWriter sw = new StringWriter();
                    stripper.writeText(doc, sw);
                    String nativeText = sw.toString().trim();
                    boolean suspicious = isSuspiciousNativeText(nativeText);
                    boolean shouldOcr = renderer != null && (opt.forceOcr || (opt.smartOcr && suspicious));
                    String chosen = nativeText;
                    boolean usedOcr = false;
                    float ocrConfidence = 1f;

                    if (shouldOcr) {
                        updateProgress(done - 1, selectedPages,
                                prefix + "Smart OCR page " + page + "/" + pages + " @180 DPI");
                        OcrResult first = ocrPdfPage(renderer, page - 1, 180, opt.script);
                        OcrResult best = first;

                        if (needsOcrRetry(first, nativeText)) {
                            checkCancelled();
                            updateProgress(done - 1, selectedPages,
                                    prefix + "OCR refine page " + page + "/" + pages + " @260 DPI");
                            OcrResult second = ocrPdfPage(renderer, page - 1, 260, opt.script);
                            if (ocrScore(second) > ocrScore(first)) best = second;
                        }

                        if (opt.forceOcr) {
                            if (!best.text.trim().isEmpty()) {
                                chosen = best.text;
                                usedOcr = true;
                            }
                        } else if (shouldPreferOcr(nativeText, best)) {
                            chosen = best.text;
                            usedOcr = true;
                        }
                        ocrConfidence = best.confidence;
                    }

                    if (usedOcr) {
                        pdfOcrPages++;
                        if (ocrConfidence > 0f && ocrConfidence < 0.56f) pdfLowConfidencePages++;
                    } else {
                        pdfNativePages++;
                    }

                    appendRaw(out, "\n\n===== PAGE " + page + " / " + pages +
                            (usedOcr ? " • OCR" : " • TEXT") + " =====\n\n");
                    appendText(out, chosen, opt.cleanText);
                    appendRaw(out, "\n");
                }
                updateProgress(selectedPages, selectedPages, prefix + "PDF complete");
            }
        } catch (com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new Exception("PDF password required or incorrect");
        }
    }

    private OcrResult ocrPdfPage(PDFRenderer renderer, int pageIndex, int dpi, int script) throws Exception {
        Bitmap bitmap = null;
        try {
            bitmap = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
            return recognizeBitmap(bitmap, script);
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private OcrResult recognizeBitmap(Bitmap bitmap, int script) throws Exception {
        TextRecognizer recognizer = getRecognizer(script);
        CountDownLatch latch = new CountDownLatch(1);
        final OcrResult[] result = {new OcrResult("", 0f, 0)};
        final Exception[] error = {null};

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> {
                    float confidenceSum = 0f;
                    int confidenceCount = 0;
                    int lineCount = 0;
                    for (Text.TextBlock block : text.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            lineCount++;
                            float c = line.getConfidence();
                            if (c > 0f) {
                                confidenceSum += c;
                                confidenceCount++;
                            }
                        }
                    }
                    float avg = confidenceCount == 0 ? 0f : confidenceSum / confidenceCount;
                    result[0] = new OcrResult(text.getText() == null ? "" : text.getText(), avg, lineCount);
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    error[0] = e;
                    latch.countDown();
                });

        if (!latch.await(90, TimeUnit.SECONDS)) throw new Exception("OCR timed out");
        if (error[0] != null) throw error[0];
        return result[0];
    }

    private synchronized TextRecognizer getRecognizer(int script) {
        TextRecognizer existing = recognizers.get(script);
        if (existing != null) return existing;
        TextRecognizer r;
        switch (script) {
            case 1:
                r = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                break;
            case 2:
                r = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
                break;
            case 3:
                r = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
                break;
            case 4:
                r = TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
                break;
            default:
                r = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                break;
        }
        recognizers.put(script, r);
        return r;
    }

    private boolean isSuspiciousNativeText(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.length() < 90) return true;
        int alnum = 0;
        int weird = 0;
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) alnum++;
            if (c == '\uFFFD' || (Character.isISOControl(c) && !Character.isWhitespace(c))) weird++;
            if (Character.isLetterOrDigit(c)) {
                if (!inWord) words++;
                inWord = true;
            } else {
                inWord = false;
            }
        }
        double density = t.isEmpty() ? 0 : alnum / (double) t.length();
        return words < 18 || density < 0.42 || weird > 2;
    }

    private boolean needsOcrRetry(OcrResult ocr, String nativeText) {
        if (ocr.text.trim().isEmpty()) return true;
        if (ocr.confidence > 0f && ocr.confidence < 0.62f) return true;
        return textQuality(ocr.text) < Math.max(80, textQuality(nativeText));
    }

    private boolean shouldPreferOcr(String nativeText, OcrResult ocr) {
        if (ocr.text.trim().isEmpty()) return false;
        int nativeScore = textQuality(nativeText);
        int ocrScore = textQuality(ocr.text);
        if (nativeText.trim().length() < 90) return ocrScore > 20;
        return ocrScore > nativeScore * 1.12;
    }

    private int ocrScore(OcrResult r) {
        int score = textQuality(r.text);
        if (r.confidence > 0f) score += Math.round(r.confidence * 120f);
        score += Math.min(r.lines * 2, 80);
        return score;
    }

    private int textQuality(String s) {
        if (s == null || s.isEmpty()) return 0;
        int letters = 0, spaces = 0, weird = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) letters++;
            else if (Character.isWhitespace(c)) spaces++;
            else if (c == '\uFFFD' || Character.isISOControl(c)) weird++;
        }
        return Math.max(0, letters * 2 + Math.min(spaces, letters) - weird * 8);
    }

    private boolean[] parsePageRange(String input, int pages) {
        boolean[] selected = new boolean[pages];
        String s = input == null ? "" : input.trim().toLowerCase(Locale.US);
        if (s.isEmpty() || s.equals("all") || s.equals("*") || s.equals("everything")) {
            for (int i = 0; i < pages; i++) selected[i] = true;
            return selected;
        }
        try {
            String[] chunks = s.split(",");
            for (String chunk : chunks) {
                String c = chunk.trim();
                if (c.isEmpty()) continue;
                if (c.contains("-")) {
                    String[] ab = c.split("-", 2);
                    int a = Integer.parseInt(ab[0].trim());
                    int b = Integer.parseInt(ab[1].trim());
                    if (a > b) { int tmp = a; a = b; b = tmp; }
                    a = Math.max(1, a);
                    b = Math.min(pages, b);
                    for (int p = a; p <= b; p++) selected[p - 1] = true;
                } else {
                    int p = Integer.parseInt(c);
                    if (p >= 1 && p <= pages) selected[p - 1] = true;
                }
            }
            if (countTrue(selected) == 0) throw new IllegalArgumentException();
            return selected;
        } catch (Exception e) {
            for (int i = 0; i < pages; i++) selected[i] = true;
            return selected;
        }
    }

    private int countTrue(boolean[] a) {
        int n = 0;
        for (boolean b : a) if (b) n++;
        return n;
    }

    private void extractDocx(Uri uri, BufferedWriter out, boolean clean) throws Exception {
        Map<String, String> parts = new HashMap<>();
        InputStream source = getContentResolver().openInputStream(uri);
        if (source == null) throw new IllegalStateException("Cannot open DOCX");
        try (InputStream raw = source;
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled();
                String n = entry.getName();
                if (n.equals("word/document.xml") ||
                        n.matches("word/header\\d+\\.xml") ||
                        n.matches("word/footer\\d+\\.xml") ||
                        n.equals("word/footnotes.xml") || n.equals("word/endnotes.xml") ||
                        n.equals("word/comments.xml")) {
                    parts.put(n, extractWordXml(readEntry(zip)));
                }
                zip.closeEntry();
            }
        }

        String body = parts.remove("word/document.xml");
        if (body != null) appendText(out, body, clean);

        List<String> names = new ArrayList<>(parts.keySet());
        Collections.sort(names);
        for (String n : names) {
            String text = parts.get(n);
            if (text == null || text.trim().isEmpty()) continue;
            appendRaw(out, "\n\n===== " + n.replace("word/", "").replace(".xml", "")
                    .toUpperCase(Locale.US) + " =====\n\n");
            appendText(out, text, clean);
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
            } else if (event == XmlPullParser.END_TAG) {
                String name = p.getName();
                if ("tc".equals(name)) b.append('\t');
                else if ("tr".equals(name)) b.append('\n');
                else if ("p".equals(name)) b.append('\n');
            }
            event = p.next();
        }
        return b.toString();
    }

    private void extractEpub(Uri uri, BufferedWriter out, boolean clean, String prefix) throws Exception {
        File temp = copyUriToTemp(uri, ".epub");
        try (ZipFile zip = new ZipFile(temp)) {
            String opfPath = findEpubOpf(zip);
            List<String> ordered = opfPath == null
                    ? fallbackEpubHtmlEntries(zip)
                    : parseEpubSpine(zip, opfPath);
            if (ordered.isEmpty()) ordered = fallbackEpubHtmlEntries(zip);

            int i = 0;
            for (String path : ordered) {
                checkCancelled();
                ZipEntry e = zip.getEntry(path);
                if (e == null || e.isDirectory()) continue;
                i++;
                updateProgress(i - 1, ordered.size(),
                        prefix + "EPUB section " + i + "/" + ordered.size());
                String html = readZipEntry(zip, e);
                String text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim();
                if (text.isEmpty()) continue;
                appendRaw(out, "\n\n===== SECTION " + i + " / " + ordered.size() + " =====\n\n");
                appendText(out, text, clean);
            }
            updateProgress(ordered.size(), ordered.size(), prefix + "EPUB complete");
        } finally {
            temp.delete();
        }
    }

    private String findEpubOpf(ZipFile zip) throws Exception {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) return null;
        String xml = readZipEntry(zip, container);
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(true);
        XmlPullParser p = f.newPullParser();
        p.setInput(new StringReader(xml));
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "rootfile".equals(p.getName())) {
                String path = p.getAttributeValue(null, "full-path");
                if (path != null && !path.trim().isEmpty()) return normalizeZipPath(path.trim());
            }
            event = p.next();
        }
        return null;
    }

    private List<String> parseEpubSpine(ZipFile zip, String opfPath) throws Exception {
        ZipEntry opf = zip.getEntry(opfPath);
        if (opf == null) return new ArrayList<>();
        String xml = readZipEntry(zip, opf);
        String base = "";
        int slash = opfPath.lastIndexOf('/');
        if (slash >= 0) base = opfPath.substring(0, slash + 1);

        Map<String, String> manifest = new LinkedHashMap<>();
        List<String> spine = new ArrayList<>();
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(true);
        XmlPullParser p = f.newPullParser();
        p.setInput(new StringReader(xml));
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = p.getName();
                if ("item".equals(name)) {
                    String id = p.getAttributeValue(null, "id");
                    String href = p.getAttributeValue(null, "href");
                    String media = p.getAttributeValue(null, "media-type");
                    if (id != null && href != null && (media == null || media.contains("html"))) {
                        manifest.put(id, resolveZipHref(base, href));
                    }
                } else if ("itemref".equals(name)) {
                    String idref = p.getAttributeValue(null, "idref");
                    if (idref != null) spine.add(idref);
                }
            }
            event = p.next();
        }

        List<String> ordered = new ArrayList<>();
        for (String id : spine) {
            String path = manifest.get(id);
            if (path != null && zip.getEntry(path) != null) ordered.add(path);
        }
        return ordered;
    }

    private List<String> fallbackEpubHtmlEntries(ZipFile zip) {
        List<String> paths = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String n = e.getName().toLowerCase(Locale.US);
            if (!e.isDirectory() && (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))) {
                paths.add(e.getName());
            }
        }
        Collections.sort(paths, String::compareToIgnoreCase);
        return paths;
    }

    private String resolveZipHref(String base, String href) {
        String h = href;
        int hash = h.indexOf('#');
        if (hash >= 0) h = h.substring(0, hash);
        try { h = URLDecoder.decode(h, "UTF-8"); } catch (Exception ignored) { }
        return normalizeZipPath(base + h);
    }

    private String normalizeZipPath(String raw) {
        String p = raw.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        String[] seg = p.split("/");
        List<String> clean = new ArrayList<>();
        for (String s : seg) {
            if (s.isEmpty() || ".".equals(s)) continue;
            if ("..".equals(s)) {
                if (!clean.isEmpty()) clean.remove(clean.size() - 1);
            } else clean.add(s);
        }
        return String.join("/", clean);
    }

    private void extractPlainText(Uri uri, BufferedWriter out, boolean clean) throws Exception {
        try (InputStream raw = getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("Cannot open file");
            BufferedInputStream in = new BufferedInputStream(raw);
            in.mark(8192);
            byte[] sample = new byte[8192];
            int n = in.read(sample);
            CharsetInfo info = detectCharset(sample, n);
            in.reset();
            skipFully(in, info.skipBytes);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, info.charset), 64 * 1024)) {
                char[] buf = new char[16 * 1024];
                int got;
                if (!clean) {
                    while ((got = r.read(buf)) != -1) {
                        checkCancelled();
                        appendRaw(out, new String(buf, 0, got));
                    }
                } else {
                    StringBuilder all = new StringBuilder();
                    while ((got = r.read(buf)) != -1) {
                        checkCancelled();
                        all.append(buf, 0, got);
                    }
                    appendText(out, all.toString(), true);
                }
            }
        }
    }

    private String readTextUri(Uri uri) throws Exception {
        try (InputStream raw = getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("Cannot open file");
            byte[] bytes = readAllBytes(raw);
            CharsetInfo info = detectCharset(bytes, bytes.length);
            return new String(bytes, info.skipBytes, bytes.length - info.skipBytes, info.charset);
        }
    }

    private CharsetInfo detectCharset(byte[] bytes, int n) {
        if (n >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new CharsetInfo(StandardCharsets.UTF_8, 3);
        }
        if (n >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new CharsetInfo(StandardCharsets.UTF_16LE, 2);
        }
        if (n >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new CharsetInfo(StandardCharsets.UTF_16BE, 2);
        }
        int sampleLen = Math.min(n, 4096);
        String ascii = new String(bytes, 0, Math.max(0, sampleLen), StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("(?i)(?:charset\\s*=\\s*[\\\"']?([A-Za-z0-9._-]+)|encoding\\s*=\\s*[\\\"']([^\\\"']+))")
                .matcher(ascii);
        if (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            try { return new CharsetInfo(Charset.forName(name), 0); } catch (Exception ignored) { }
        }
        return new CharsetInfo(StandardCharsets.UTF_8, 0);
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

    private String parseRtf(String rtf) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean[] skip = new boolean[Math.max(32, Math.min(4096, rtf.length() / 4 + 32))];
        Charset cp1252 = Charset.forName("windows-1252");

        for (int i = 0; i < rtf.length(); i++) {
            char c = rtf.charAt(i);
            if (c == '{') {
                depth = Math.min(depth + 1, skip.length - 1);
                skip[depth] = skip[Math.max(0, depth - 1)];
                continue;
            }
            if (c == '}') {
                if (depth > 0) depth--;
                continue;
            }
            if (c != '\\') {
                if (!skip[depth] && c != '\r' && c != '\n') out.append(c);
                continue;
            }
            if (i + 1 >= rtf.length()) break;
            char next = rtf.charAt(++i);
            if (next == '\\' || next == '{' || next == '}') {
                if (!skip[depth]) out.append(next);
                continue;
            }
            if (next == '*') {
                skip[depth] = true;
                continue;
            }
            if (next == '\'') {
                if (i + 2 < rtf.length()) {
                    String hex = rtf.substring(i + 1, i + 3);
                    try {
                        byte b = (byte) Integer.parseInt(hex, 16);
                        if (!skip[depth]) out.append(new String(new byte[]{b}, cp1252));
                    } catch (Exception ignored) { }
                    i += 2;
                }
                continue;
            }
            if (!Character.isLetter(next)) continue;

            int start = i;
            while (i < rtf.length() && Character.isLetter(rtf.charAt(i))) i++;
            String word = rtf.substring(start, i);
            boolean negative = false;
            if (i < rtf.length() && rtf.charAt(i) == '-') { negative = true; i++; }
            int numStart = i;
            while (i < rtf.length() && Character.isDigit(rtf.charAt(i))) i++;
            Integer param = null;
            if (i > numStart) {
                try {
                    param = Integer.parseInt(rtf.substring(numStart, i));
                    if (negative) param = -param;
                } catch (Exception ignored) { }
            }
            if (i < rtf.length() && rtf.charAt(i) != ' ') i--;

            if (word.equals("fonttbl") || word.equals("colortbl") || word.equals("stylesheet") ||
                    word.equals("info") || word.equals("pict") || word.equals("object") ||
                    word.equals("header") || word.equals("footer")) {
                skip[depth] = true;
                continue;
            }
            if (skip[depth]) continue;

            switch (word) {
                case "par":
                case "line": out.append('\n'); break;
                case "tab": out.append('\t'); break;
                case "emdash": out.append('—'); break;
                case "endash": out.append('–'); break;
                case "bullet": out.append('•'); break;
                case "lquote": case "rquote": out.append('\''); break;
                case "ldblquote": case "rdblquote": out.append('"'); break;
                case "u":
                    if (param != null) {
                        int v = param;
                        if (v < 0) v += 65536;
                        out.append((char) v);
                        if (i + 1 < rtf.length() && rtf.charAt(i + 1) != '\\' &&
                                rtf.charAt(i + 1) != '{' && rtf.charAt(i + 1) != '}') i++;
                    }
                    break;
            }
        }
        return out.toString();
    }

    private String cleanText(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = text.replace("\u00AD", "").replace("\r\n", "\n").replace('\r', '\n');
        s = s.replaceAll("(?m)([\\p{L}])[-‐‑]\n([\\p{Ll}])", "$1$2");
        s = s.replaceAll("[ \\t]+\n", "\n");
        s = s.replaceAll("\n[ \\t]+", "\n");
        s = s.replaceAll("\n{4,}", "\n\n\n");
        return s.trim();
    }

    private void appendText(BufferedWriter out, String text, boolean clean) throws Exception {
        appendRaw(out, clean ? cleanText(text) : text);
    }

    private void appendRaw(BufferedWriter out, String text) throws Exception {
        if (text == null || text.isEmpty()) return;
        out.write(text);
        extractedChars += text.length();
        if (preview.length() < PREVIEW_LIMIT) {
            int room = PREVIEW_LIMIT - preview.length();
            preview.append(text, 0, Math.min(room, text.length()));
            if (preview.length() == PREVIEW_LIMIT) {
                preview.append("\n\n[Preview ends here. Full output continues beyond this point.]\n");
            }
        }
    }

    private void copyAll() {
        if (!hasExtraction()) return;
        if (extractedFile.length() <= DIRECT_CLIPBOARD_UTF8_LIMIT) {
            copyDirect();
        } else {
            copyAsTextStream();
        }
    }

    private void copyDirect() {
        setBusy(true, "Loading complete document into Android clipboard…");
        worker.execute(() -> {
            try {
                String all = readUtf8File(extractedFile);
                runOnUiThread(() -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText(selectedName + " • full text", all));
                        chunkCursorChars = 0;
                        setBusy(false, "Copied ALL " + formatCount(all.length()) + " characters as text.");
                        toast("Whole document copied");
                    } catch (Exception e) {
                        setBusy(false, "Clipboard rejected direct text. Switching to stream copy…");
                        copyAsTextStream();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> setBusy(false, "Copy failed: " + shortError(e)));
            }
        });
    }

    private void copyAsTextStream() {
        if (!hasExtraction()) return;
        setBusy(true, "Preparing giant-document text stream…");
        worker.execute(() -> {
            try {
                File stream = new File(getCacheDir(), safeFileBase(selectedName) + "_OMEGA_FULL_TEXT.txt");
                try (InputStream in = new FileInputStream(extractedFile);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(stream))) {
                    copyBytes(in, out);
                }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", stream);
                ClipDescription desc = new ClipDescription(selectedName + " • full text stream",
                        new String[]{"text/plain"});
                ClipData clip = new ClipData(desc, new ClipData.Item(uri));
                runOnUiThread(() -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(clip);
                        chunkCursorChars = 0;
                        setBusy(false, "Copied ALL via text stream • " + formatCount(extractedChars) +
                                " characters • avoids giant inline clipboard payloads.");
                        toast("Whole document stream copied");
                    } catch (Exception e) {
                        setBusy(false, "Stream clipboard was rejected. COPY CHUNK is the compatibility fallback.");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> setBusy(false, "Stream copy failed: " + shortError(e)));
            }
        });
    }

    private void copyNextChunk() {
        if (!hasExtraction()) return;
        final long start = chunkCursorChars;
        if (start >= extractedChars) chunkCursorChars = 0;
        final long actualStart = chunkCursorChars;
        setBusy(true, "Preparing safe clipboard chunk…");
        worker.execute(() -> {
            try {
                String chunk = readCharChunk(extractedFile, actualStart, CHUNK_CHARS);
                if (chunk.isEmpty()) {
                    chunkCursorChars = 0;
                    runOnUiThread(() -> setBusy(false, "Chunk sequence reset. Tap COPY CHUNK again."));
                    return;
                }
                long next = actualStart + chunk.length();
                int chunkNo = (int) (actualStart / CHUNK_CHARS) + 1;
                int total = (int) Math.max(1, (extractedChars + CHUNK_CHARS - 1) / CHUNK_CHARS);
                runOnUiThread(() -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText(
                                selectedName + " • chunk " + chunkNo + "/" + total, chunk));
                        chunkCursorChars = next >= extractedChars ? 0 : next;
                        setBusy(false, "Copied chunk " + chunkNo + "/" + total +
                                (chunkCursorChars == 0 ? " • sequence complete" : " • tap again for next"));
                    } catch (Exception e) {
                        setBusy(false, "Chunk copy failed: " + shortError(e));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> setBusy(false, "Chunk read failed: " + shortError(e)));
            }
        });
    }

    private String readCharChunk(File file, long charOffset, int maxChars) throws Exception {
        try (Reader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8), 64 * 1024)) {
            long left = charOffset;
            while (left > 0) {
                long n = r.skip(left);
                if (n <= 0) {
                    if (r.read() == -1) return "";
                    n = 1;
                }
                left -= n;
            }
            char[] buf = new char[maxChars];
            int total = 0;
            while (total < maxChars) {
                int n = r.read(buf, total, maxChars - total);
                if (n == -1) break;
                total += n;
            }
            return new String(buf, 0, total);
        }
    }

    private void saveTxt() {
        if (!hasExtraction()) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, safeFileBase(selectedName) + "_OMEGA_FULL_TEXT.txt");
        startActivityForResult(i, SAVE_TEXT);
    }

    private void shareTxt() {
        if (!hasExtraction()) return;
        worker.execute(() -> {
            try {
                File share = new File(getCacheDir(), safeFileBase(selectedName) + "_OMEGA_FULL_TEXT.txt");
                try (InputStream in = new FileInputStream(extractedFile);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(share))) {
                    copyBytes(in, out);
                }
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", share);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, selectedName + " • complete extracted text");
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "Share complete document text")));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Share failed: " + shortError(e)));
            }
        });
    }

    private boolean hasExtraction() {
        return extractedFile != null && extractedFile.exists() && extractedFile.length() > 0 && extractedChars > 0;
    }

    private void restoreLastExtraction() {
        long savedChars = getSharedPreferences("omega", MODE_PRIVATE).getLong("chars", 0);
        String savedName = getSharedPreferences("omega", MODE_PRIVATE).getString("name", "document");
        if (extractedFile.exists() && extractedFile.length() > 0 && savedChars > 0) {
            extractedChars = savedChars;
            selectedName = savedName == null ? "document" : savedName;
            try {
                preview.setLength(0);
                preview.append(readCharChunk(extractedFile, 0, PREVIEW_LIMIT));
                if (extractedChars > preview.length()) {
                    preview.append("\n\n[Preview ends here. Full output continues beyond this point.]\n");
                }
                previewView.setText(preview.toString());
                setActionsEnabled(true);
                statusView.setText("Restored last extraction • " + formatCount(extractedChars) + " characters");
            } catch (Exception ignored) { }
        }
    }

    private void saveLastState() {
        getSharedPreferences("omega", MODE_PRIVATE).edit()
                .putLong("chars", extractedChars)
                .putString("name", selectedName)
                .apply();
    }

    private String buildReport() {
        StringBuilder b = new StringBuilder();
        b.append("Ready • ").append(documentsDone).append(documentsDone == 1 ? " document" : " documents")
                .append(" • ").append(formatCount(extractedChars)).append(" chars");
        if (pdfPagesSeen > 0) {
            b.append(" • PDF pages ").append(pdfPagesSeen)
                    .append(" • native ").append(pdfNativePages)
                    .append(" • OCR ").append(pdfOcrPages);
            if (pdfLowConfidencePages > 0) b.append(" • low-confidence ").append(pdfLowConfidencePages);
        }
        if (extractedFile.length() > DIRECT_CLIPBOARD_UTF8_LIMIT) {
            b.append(" • giant-copy mode armed");
        }
        return b.toString();
    }

    private void resetStats() {
        pdfPagesSeen = 0;
        pdfNativePages = 0;
        pdfOcrPages = 0;
        pdfLowConfidencePages = 0;
        documentsDone = 0;
    }

    private File copyUriToTemp(Uri uri, String suffix) throws Exception {
        File f = File.createTempFile("doccopy_", suffix, getCacheDir());
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
            if (in == null) throw new IllegalStateException("Cannot open document");
            copyBytes(in, out);
        }
        return f;
    }

    private String readZipEntry(ZipFile zip, ZipEntry entry) throws Exception {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(readAllBytes(in), StandardCharsets.UTF_8);
        }
    }

    private String readEntry(ZipInputStream zip) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = zip.read(buf)) != -1) b.write(buf, 0, n);
        return b.toString(StandardCharsets.UTF_8.name());
    }

    private byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private String readUtf8File(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return new String(readAllBytes(in), StandardCharsets.UTF_8);
        }
    }

    private void copyBytes(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private void skipFully(InputStream in, long bytes) throws Exception {
        long left = bytes;
        while (left > 0) {
            long n = in.skip(left);
            if (n <= 0) {
                if (in.read() == -1) break;
                n = 1;
            }
            left -= n;
        }
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
            progressBar.setIndeterminate(false);
            progressBar.setProgress(pct);
            statusView.setText(msg);
        });
    }

    private void updateStatus(String msg) {
        runOnUiThread(() -> statusView.setText(msg));
    }

    private void setBusy(boolean busy, String message) {
        progressBar.setIndeterminate(busy && progressBar.getProgress() == 0);
        statusView.setText(message);
        cancelButton.setEnabled(busy);
        if (busy) {
            setActionsEnabled(false);
        } else {
            progressBar.setIndeterminate(false);
            cancelButton.setEnabled(false);
            setActionsEnabled(hasExtraction());
        }
    }

    private void setActionsEnabled(boolean enabled) {
        copyButton.setEnabled(enabled);
        chunkButton.setEnabled(enabled);
        saveButton.setEnabled(enabled);
        shareButton.setEnabled(enabled);
    }

    private void checkCancelled() throws CancelledException {
        if (cancelled) throw new CancelledException();
    }

    private String shortError(Throwable t) {
        String s = t.getMessage();
        if (s == null || s.trim().isEmpty()) s = t.getClass().getSimpleName();
        return s.length() > 180 ? s.substring(0, 180) : s;
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
        synchronized (this) {
            for (TextRecognizer r : recognizers.values()) {
                try { r.close(); } catch (Exception ignored) { }
            }
            recognizers.clear();
        }
        worker.shutdownNow();
    }

    private static class ExtractionOptions {
        final boolean smartOcr;
        final boolean forceOcr;
        final boolean cleanText;
        final int script;
        final String pageRange;
        final String password;

        ExtractionOptions(boolean smartOcr, boolean forceOcr, boolean cleanText,
                          int script, String pageRange, String password) {
            this.smartOcr = smartOcr;
            this.forceOcr = forceOcr;
            this.cleanText = cleanText;
            this.script = script;
            this.pageRange = pageRange;
            this.password = password == null ? "" : password;
        }
    }

    private static class OcrResult {
        final String text;
        final float confidence;
        final int lines;
        OcrResult(String text, float confidence, int lines) {
            this.text = text == null ? "" : text;
            this.confidence = confidence;
            this.lines = lines;
        }
    }

    private static class CharsetInfo {
        final Charset charset;
        final int skipBytes;
        CharsetInfo(Charset charset, int skipBytes) {
            this.charset = charset;
            this.skipBytes = skipBytes;
        }
    }

    private static class CancelledException extends Exception { }
}
