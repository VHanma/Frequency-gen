package com.vaan.frequencyscope;
import android.content.*;import android.graphics.*;import android.view.*;import java.util.*;
public class SpectrumView extends View{
 private final Paint grid=new Paint(1),line=new Paint(1),txt=new Paint(1); private final Path p=new Path(); private float[] db=new float[0]; private double sr=48000,max=20000;
 SpectrumView(Context c){super(c);grid.setColor(Color.rgb(35,50,58));line.setColor(Color.rgb(0,255,200));line.setStyle(Paint.Style.STROKE);line.setStrokeWidth(3);txt.setColor(Color.LTGRAY);txt.setTextSize(25);setBackgroundColor(Color.rgb(4,8,12));}
 synchronized void set(float[] x,double s,double m){db=Arrays.copyOf(x,x.length);sr=s;max=m;postInvalidate();}
 protected synchronized void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),l=60,r=w-10,t=15,b=h-35;for(int i=0;i<=4;i++){float y=t+(b-t)*i/4;c.drawLine(l,y,r,y,grid);c.drawText((-20*i)+" dB",3,y+8,txt);}for(int i=0;i<=4;i++){float x=l+(r-l)*i/4;c.drawLine(x,t,x,b,grid);c.drawText(axis(max*i/4),x-20,h-7,txt);}if(db.length<2)return;int mb=Math.max(1,Math.min(db.length-1,(int)Math.round(max*db.length*2/sr)));p.reset();for(int i=0;i<=mb;i++){float x=l+(r-l)*i/mb;float q=Math.max(0,Math.min(1,(db[i]+90)/90));float y=b-q*(b-t);if(i==0)p.moveTo(x,y);else p.lineTo(x,y);}c.drawPath(p,line);}
 private String axis(double f){return f>=1000?String.format(java.util.Locale.US,"%.1fk",f/1000):String.format(java.util.Locale.US,"%.0f",f);}
}
