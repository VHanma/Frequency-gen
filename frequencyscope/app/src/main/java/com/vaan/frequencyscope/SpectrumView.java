package com.vaan.frequencyscope;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Arrays;
import java.util.Locale;

public class SpectrumView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float[] mag = new float[0];
    private double sampleRate = 48000;
    private double maxHz = 20000;
    private boolean logX = true;

    SpectrumView(Context c) {
        super(c);
        grid.setColor(Color.rgb(35, 50, 58));
        line.setColor(Color.rgb(0, 255, 200));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3);
        txt.setColor(Color.LTGRAY);
        txt.setTextSize(22);
        setBackgroundColor(Color.rgb(4, 8, 12));
    }

    synchronized void setSpectrum(float[] x, double sr, double max, boolean logarithmicX) {
        mag = Arrays.copyOf(x, x.length);
        sampleRate = Math.max(1, sr);
        maxHz = Math.max(1, max);
        logX = logarithmicX;
        postInvalidate();
    }

    @Override protected synchronized void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        float left = 18, right = w - 10, top = 15, bottom = h - 42;

        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            c.drawLine(left, y, right, y, grid);
        }
        for (int i = 0; i <= 4; i++) {
            float x = left + (right - left) * i / 4f;
            c.drawLine(x, top, x, bottom, grid);
        }

        drawXAxis(c, left, right, h);
        if (mag.length < 3) return;

        int maxBin = Math.max(2, Math.min(mag.length - 1,
                (int)Math.round(maxHz * mag.length * 2 / sampleRate)));
        float floor = Float.MAX_VALUE, ceil = -Float.MAX_VALUE;
        for (int i = 1; i <= maxBin; i++) {
            if (mag[i] < floor) floor = mag[i];
            if (mag[i] > ceil) ceil = mag[i];
        }
        float span = Math.max(1.0f, ceil - floor);

        path.reset();
        boolean started = false;
        for (int i = 1; i <= maxBin; i++) {
            double f = i * sampleRate / (mag.length * 2.0);
            float x = xForFrequency(f, left, right);
            float q = Math.max(0, Math.min(1, (mag[i] - floor) / span));
            float y = bottom - q * (bottom - top);
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else path.lineTo(x, y);
        }
        c.drawPath(path, line);
    }

    void drawXAxis(Canvas c, float left, float right, float h) {
        if (!logX) {
            for (int i = 0; i <= 4; i++) {
                double f = maxHz * i / 4.0;
                float x = left + (right - left) * i / 4f;
                c.drawText(axis(f), Math.max(2, x - 28), h - 12, txt);
            }
        } else {
            double[] marks = {5, 20, 100, 500, 2000, 10000, 20000, 40000};
            for (double f : marks) {
                if (f > maxHz) continue;
                float x = xForFrequency(f, left, right);
                c.drawText(axis(f), Math.max(2, x - 24), h - 12, txt);
            }
            if (maxHz > 20 && maxHz < 40000) {
                float x = xForFrequency(maxHz, left, right);
                c.drawText(axis(maxHz), Math.max(2, x - 28), h - 12, txt);
            }
        }
        c.drawText("Hz", right - 30, h - 12, txt);
    }

    float xForFrequency(double f, float left, float right) {
        if (!logX) return left + (right - left) * (float)Math.max(0, Math.min(1, f / maxHz));
        double lo = 3.0;
        double hi = Math.max(lo + 1, maxHz);
        double clamped = Math.max(lo, Math.min(hi, f));
        double p = (Math.log(clamped) - Math.log(lo)) / (Math.log(hi) - Math.log(lo));
        return left + (right - left) * (float)p;
    }

    private String axis(double f) {
        if (f >= 1000) return String.format(Locale.US, f >= 10000 ? "%.0fk" : "%.1fk", f / 1000.0);
        if (f >= 100) return String.format(Locale.US, "%.0f", f);
        if (f >= 10) return String.format(Locale.US, "%.1f", f);
        return String.format(Locale.US, "%.2f", f);
    }
}
