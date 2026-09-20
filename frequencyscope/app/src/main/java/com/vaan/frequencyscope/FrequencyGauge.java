package com.vaan.frequencyscope;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.util.Locale;

public class FrequencyGauge extends View {
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path needlePath = new Path();
    private double hz = 0;
    private double maxHz = 20000;
    private boolean logScale = true;
    private String mode = "AUDIO";

    public FrequencyGauge(Context c) {
        super(c);
        setBackgroundColor(Color.rgb(5, 8, 12));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(22f);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(Color.rgb(0, 220, 180));
        tick.setStrokeWidth(3f);
        tick.setColor(Color.rgb(120, 155, 165));
        text.setColor(Color.rgb(228, 242, 246));
        text.setTextAlign(Paint.Align.CENTER);
        needle.setColor(Color.rgb(255, 245, 235));
        needle.setStyle(Paint.Style.FILL);
        hub.setColor(Color.rgb(0, 255, 200));
    }

    public synchronized void setReading(double valueHz, double max, boolean logarithmic, String label) {
        hz = Math.max(0, valueHz);
        maxHz = Math.max(1, max);
        logScale = logarithmic;
        mode = label == null ? "" : label;
        postInvalidate();
    }

    @Override protected synchronized void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f;
        float cy = h * .70f;
        float radius = Math.min(w * .43f, h * .56f);

        c.drawArc(cx-radius, cy-radius, cx+radius, cy+radius, 200, 140, false, arc);

        int ticks = logScale ? 10 : 8;
        for (int i=0;i<=ticks;i++) {
            float p = i/(float)ticks;
            double deg = 200 + 140*p;
            double rad = Math.toRadians(deg);
            float x1 = cx + (float)Math.cos(rad)*(radius-28);
            float y1 = cy + (float)Math.sin(rad)*(radius-28);
            float x2 = cx + (float)Math.cos(rad)*(radius-2);
            float y2 = cy + (float)Math.sin(rad)*(radius-2);
            c.drawLine(x1,y1,x2,y2,tick);
        }

        String[] labels;
        double[] labelHz;
        if (logScale) {
            labels = new String[]{"20","100","500","2k","10k","20k"};
            labelHz = new double[]{20,100,500,2000,10000,20000};
        } else {
            labels = new String[]{"0", fmt(maxHz*.25), fmt(maxHz*.50), fmt(maxHz*.75), fmt(maxHz)};
            labelHz = new double[]{0,maxHz*.25,maxHz*.50,maxHz*.75,maxHz};
        }
        text.setTextSize(24f);
        for (int i=0;i<labels.length;i++) {
            float p = position(labelHz[i]);
            double rad = Math.toRadians(200 + 140*p);
            float x = cx + (float)Math.cos(rad)*(radius-58);
            float y = cy + (float)Math.sin(rad)*(radius-58) + 8;
            c.drawText(labels[i],x,y,text);
        }

        float p = position(hz);
        double a = Math.toRadians(200 + 140*p);
        float tipX = cx + (float)Math.cos(a)*(radius-52);
        float tipY = cy + (float)Math.sin(a)*(radius-52);
        double sideA = a + Math.PI/2;
        float sx = (float)Math.cos(sideA)*8;
        float sy = (float)Math.sin(sideA)*8;
        needlePath.reset();
        needlePath.moveTo(cx+sx,cy+sy);
        needlePath.lineTo(tipX,tipY);
        needlePath.lineTo(cx-sx,cy-sy);
        needlePath.close();
        c.drawPath(needlePath,needle);
        c.drawCircle(cx,cy,14,hub);

        text.setTextSize(52f);
        text.setColor(Color.WHITE);
        c.drawText(hz > 0 ? String.format(Locale.US,"%.2f Hz",hz) : "— Hz",cx,cy-45,text);
        text.setTextSize(22f);
        text.setColor(Color.rgb(140,180,190));
        c.drawText(mode + " • DOMINANT",cx,cy-10,text);
    }

    private float position(double f) {
        if (logScale) {
            double lo = 20.0;
            double hi = Math.max(lo+1, maxHz);
            double clamped = Math.max(lo, Math.min(hi, f <= 0 ? lo : f));
            return (float)((Math.log(clamped)-Math.log(lo))/(Math.log(hi)-Math.log(lo)));
        }
        return (float)Math.max(0, Math.min(1, f/maxHz));
    }

    private String fmt(double f) {
        if (f >= 1000) return String.format(Locale.US,"%.1fk",f/1000.0);
        if (f >= 100) return String.format(Locale.US,"%.0f",f);
        if (f >= 10) return String.format(Locale.US,"%.1f",f);
        return String.format(Locale.US,"%.2f",f);
    }
}
