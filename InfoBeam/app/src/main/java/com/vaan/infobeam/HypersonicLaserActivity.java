package com.vaan.infobeam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

public final class HypersonicLaserActivity extends Activity {
    private static final int PICK_AUDIO = 2201;
    private static final int BG=Color.rgb(2,6,9), CARD=Color.rgb(11,19,25), TEXT=Color.rgb(238,251,255);
    private static final int CYAN=Color.rgb(70,240,255), GOLD=Color.rgb(255,203,92), RED=Color.rgb(255,102,112), MUTED=Color.rgb(145,171,181);

    private enum Pending { NONE, SINGLE_TTS, DUAL_TTS }

    private final UnifiedHypersonicEngine engine = new UnifiedHypersonicEngine();
    private AudioManager audioManager;
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private volatile Pending pending = Pending.NONE;
    private volatile String pendingId;
    private volatile File pendingFile;

    private LinearLayout singlePanel, dualPanel;
    private Button tabSingle, tabDual;
    private TextView status, routeInfo, chosenAudioLabel;
    private Spinner singleSource, singleRate, dualRate;
    private EditText singleText, singleCarrier, dualText, carrierA, carrierB, elfA, elfB;
    private SeekBar singleMod, singleBeam, singleElf, dualMod, dualBeam, dualElf;
    private TextView singleModLabel, singleBeamLabel, singleElfLabel, dualModLabel, dualBeamLabel, dualElfLabel;
    private Uri chosenAudio;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        audioManager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        initTts();
        scanRoute();
    }

    private View buildUi() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(14),dp(14),dp(14),dp(34));
        scroll.addView(root,new ScrollView.LayoutParams(-1,-2));

        root.addView(text("INFOBEAM Ω • HYPERSONIC LASER",24,CYAN,true));
        TextView sub=text("External high-rate parametric output • every carrier + ELF component runs in one synchronized signal",13,MUTED,false);
        sub.setPadding(0,dp(4),0,dp(10)); root.addView(sub);
        status=text("ENGINE STARTING",14,GOLD,true); status.setPadding(dp(12),dp(10),dp(12),dp(10)); status.setBackground(round(Color.rgb(25,34,39),11,GOLD)); root.addView(status,full());

        LinearLayout tabs=new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL); tabs.setPadding(0,dp(10),0,0);
        tabSingle=button("LASER + 7.83",CYAN,Color.rgb(4,34,39));
        tabDual=button("DUAL MATRIX",TEXT,Color.rgb(21,27,32));
        tabs.addView(tabSingle,new LinearLayout.LayoutParams(0,-2,1));
        tabs.addView(tabDual,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(tabs,full());

        singlePanel=buildSingle(); dualPanel=buildDual();
        root.addView(singlePanel,top(8)); root.addView(dualPanel,top(8));
        dualPanel.setVisibility(View.GONE);
        tabSingle.setOnClickListener(v->showTab(true)); tabDual.setOnClickListener(v->showTab(false));

        LinearLayout hw=card(root,"EXTERNAL OUTPUT ROUTE","The app outputs one unified high-rate waveform mirrored to both channels. Your external DAC, amplifier, parametric array, ultrasonic transducers, and ELF-capable stage determine the physical output.");
        routeInfo=text("Scanning…",12,MUTED,false); hw.addView(routeInfo);
        Button scan=button("RESCAN HARDWARE",TEXT,Color.rgb(22,34,40)); scan.setOnClickListener(v->scanRoute()); hw.addView(scan,top(7));

        Button stop=button("STOP ALL",RED,Color.rgb(43,9,15)); stop.setOnClickListener(v->{engine.stop(); if(tts!=null)try{tts.stop();}catch(Throwable ignored){} setStatus("Stopped.",GOLD);});
        root.addView(stop,top(12));
        return scroll;
    }

    private LinearLayout buildSingle() {
        LinearLayout p=new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setPadding(dp(14),dp(14),dp(14),dp(14)); p.setBackground(round(CARD,14,Color.rgb(37,61,67)));
        p.addView(text("TAB 1 • HYPERSONIC + EXACT 7.83 Hz",17,CYAN,true));
        TextView note=text("Choose TTS or an audio file. The voice modulation, chosen hypersonic carrier, and exact 7.830 Hz component are summed into one waveform from one sample clock and run at the exact same time.",12,MUTED,false); note.setPadding(0,dp(4),0,dp(8)); p.addView(note);

        singleSource=spinner(new String[]{"Text → TTS","Chosen audio file"}); p.addView(singleSource);
        singleText=field("Text to beam",4); p.addView(singleText,top(7));
        Button choose=button("CHOOSE AUDIO",GOLD,Color.rgb(39,29,5)); choose.setOnClickListener(v->pickAudio()); p.addView(choose,top(7));
        chosenAudioLabel=text("No audio selected",12,MUTED,false); p.addView(chosenAudioLabel,top(4));

        p.addView(text("Hypersonic carrier (Hz)",12,TEXT,true),top(9));
        singleCarrier=numberField("40000",false); p.addView(singleCarrier);
        TextView elfFixed=text("ELF: 7.830 Hz • fixed exact target • mixed into same signal",13,GOLD,true); elfFixed.setPadding(0,dp(8),0,0); p.addView(elfFixed);

        singleRate=spinner(new String[]{"192000 Hz preferred","96000 Hz preferred"}); p.addView(singleRate,top(7));

        singleModLabel=text("Voice modulation depth 88%",12,TEXT,true); p.addView(singleModLabel,top(8));
        singleMod=slider(88,CYAN,v->singleModLabel.setText("Voice modulation depth "+v+"%")); p.addView(singleMod);
        singleBeamLabel=text("Hypersonic line level 34%",12,TEXT,true); p.addView(singleBeamLabel);
        singleBeam=slider(34,GOLD,v->singleBeamLabel.setText("Hypersonic line level "+v+"%")); p.addView(singleBeam);
        singleElfLabel=text("ELF line level 16%",12,TEXT,true); p.addView(singleElfLabel);
        singleElf=slider(16,GOLD,v->singleElfLabel.setText("ELF line level "+v+"%")); p.addView(singleElf);

        Button play=button("FIRE ALL TOGETHER",CYAN,Color.rgb(3,39,45)); play.setOnClickListener(v->startSingle()); p.addView(play,top(9));
        return p;
    }

    private LinearLayout buildDual() {
        LinearLayout p=new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setPadding(dp(14),dp(14),dp(14),dp(14)); p.setBackground(round(CARD,14,Color.rgb(37,61,67)));
        p.addView(text("TAB 2 • 2 HYPERSONIC + 2 ELF + TTS",17,CYAN,true));
        TextView note=text("The TTS source modulates both chosen hypersonic carriers while both chosen ELF frequencies run simultaneously. TTS + Carrier A + Carrier B + ELF A + ELF B are all summed into the same waveform, not split between channels.",12,MUTED,false); note.setPadding(0,dp(4),0,dp(8)); p.addView(note);
        dualText=field("Text for dual hypersonic matrix",4); p.addView(dualText);

        p.addView(text("Hypersonic A (Hz)",12,TEXT,true),top(8)); carrierA=numberField("40000",false); p.addView(carrierA);
        p.addView(text("ELF A (Hz)",12,TEXT,true),top(5)); elfA=numberField("7.83",true); p.addView(elfA);
        p.addView(text("Hypersonic B (Hz)",12,TEXT,true),top(8)); carrierB=numberField("42000",false); p.addView(carrierB);
        p.addView(text("ELF B (Hz)",12,TEXT,true),top(5)); elfB=numberField("10.00",true); p.addView(elfB);
        dualRate=spinner(new String[]{"192000 Hz preferred","96000 Hz preferred"}); p.addView(dualRate,top(7));

        dualModLabel=text("TTS modulation depth 88%",12,TEXT,true); p.addView(dualModLabel,top(8));
        dualMod=slider(88,CYAN,v->dualModLabel.setText("TTS modulation depth "+v+"%")); p.addView(dualMod);
        dualBeamLabel=text("Each hypersonic carrier level 30%",12,TEXT,true); p.addView(dualBeamLabel);
        dualBeam=slider(30,GOLD,v->dualBeamLabel.setText("Each hypersonic carrier level "+v+"%")); p.addView(dualBeam);
        dualElfLabel=text("Each ELF level 12%",12,TEXT,true); p.addView(dualElfLabel);
        dualElf=slider(12,GOLD,v->dualElfLabel.setText("Each ELF level "+v+"%")); p.addView(dualElf);

        Button play=button("FIRE ALL 5 TOGETHER",CYAN,Color.rgb(3,39,45)); play.setOnClickListener(v->startDual()); p.addView(play,top(9));
        return p;
    }

    private void showTab(boolean single) {
        singlePanel.setVisibility(single?View.VISIBLE:View.GONE); dualPanel.setVisibility(single?View.GONE:View.VISIBLE);
        tabSingle.setTextColor(single?CYAN:MUTED); tabDual.setTextColor(single?MUTED:CYAN);
        tabSingle.setBackgroundTintList(ColorStateList.valueOf(single?Color.rgb(4,34,39):Color.rgb(21,27,32)));
        tabDual.setBackgroundTintList(ColorStateList.valueOf(single?Color.rgb(21,27,32):Color.rgb(4,34,39)));
    }

    private void startSingle() {
        engine.stop();
        if(singleSource.getSelectedItemPosition()==1) {
            if(chosenAudio==null){setStatus("Choose an audio file first.",RED);return;}
            setStatus("Decoding chosen audio…",GOLD);
            Uri uri=chosenAudio;
            new Thread(()->{
                try { PcmWav.Data pcm=AudioSourceDecoder.decode(this,uri,600); runOnUiThread(()->playSinglePcm(pcm)); }
                catch(Throwable t){runOnUiThread(()->setStatus("Audio decode error: "+safe(t),RED));}
            },"InfoBeam-AudioDecode").start();
        } else {
            String s=singleText.getText().toString().trim(); if(s.isEmpty()){setStatus("Type text first.",RED);return;} synth(Pending.SINGLE_TTS,s);
        }
    }

    private void startDual() {
        String s=dualText.getText().toString().trim(); if(s.isEmpty()){setStatus("Type text first.",RED);return;} synth(Pending.DUAL_TTS,s);
    }

    private void playSinglePcm(PcmWav.Data pcm) {
        try {
            double carrier=parse(singleCarrier,40000); int rate=rate(singleRate);
            UnifiedHypersonicEngine.SingleConfig cfg=new UnifiedHypersonicEngine.SingleConfig(carrier,7.83,pct(singleMod),pct(singleBeam),pct(singleElf),rate,findExternal());
            engine.playSingle(pcm,cfg,listener);
        } catch(Throwable t){setStatus("Config error: "+safe(t),RED);}
    }

    private void playDualPcm(PcmWav.Data pcm) {
        try {
            UnifiedHypersonicEngine.DualConfig cfg=new UnifiedHypersonicEngine.DualConfig(parse(carrierA,40000),parse(carrierB,42000),parse(elfA,7.83),parse(elfB,10.0),pct(dualMod),pct(dualBeam),pct(dualElf),rate(dualRate),findExternal());
            engine.playDual(pcm,cfg,listener);
        } catch(Throwable t){setStatus("Config error: "+safe(t),RED);}
    }

    private final UnifiedHypersonicEngine.Listener listener=new UnifiedHypersonicEngine.Listener(){
        @Override public void onStatus(String s){runOnUiThread(()->setStatus(s,s.toLowerCase(Locale.US).contains("error")?RED:CYAN));}
        @Override public void onStopped(){}
    };

    private void initTts() {
        tts=new TextToSpeech(getApplicationContext(),r->{
            if(r==TextToSpeech.SUCCESS){ttsReady=true; int lang=tts.setLanguage(Locale.US); if(lang==TextToSpeech.LANG_MISSING_DATA||lang==TextToSpeech.LANG_NOT_SUPPORTED)tts.setLanguage(Locale.getDefault()); tts.setSpeechRate(0.98f); tts.setPitch(0.92f); runOnUiThread(()->setStatus("READY • all requested frequencies will run together",CYAN));}
            else runOnUiThread(()->setStatus("TTS unavailable",RED));
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){}
            @Override public void onDone(String id){if(id==null||!id.equals(pendingId))return;File f=pendingFile;Pending k=pending;pendingId=null;pendingFile=null;pending=Pending.NONE;if(f!=null)dispatchTts(f,k);}
            @Override @Deprecated public void onError(String id){onError(id,TextToSpeech.ERROR);}
            @Override public void onError(String id,int code){if(id!=null&&id.equals(pendingId))runOnUiThread(()->setStatus("TTS synthesis error: "+code,RED));}
        });
    }

    private void synth(Pending kind,String text) {
        if(!ttsReady||tts==null){setStatus("Voice engine is still initializing.",RED);return;}
        engine.stop(); try{tts.stop();}catch(Throwable ignored){}
        File f=new File(getCacheDir(),"hypersonic_"+System.nanoTime()+".wav");
        pending=kind;pendingFile=f;pendingId="hyper-"+System.nanoTime();setStatus("Synthesizing beam source…",GOLD);
        int r=tts.synthesizeToFile(text,new Bundle(),f,pendingId);if(r!=TextToSpeech.SUCCESS)setStatus("TTS rejected synthesis.",RED);
    }

    private void dispatchTts(File f,Pending kind) {
        new Thread(()->{
            try { PcmWav.Data pcm=PcmWav.read(f); runOnUiThread(()->{if(kind==Pending.SINGLE_TTS)playSinglePcm(pcm);else if(kind==Pending.DUAL_TTS)playDualPcm(pcm);}); }
            catch(Throwable t){runOnUiThread(()->setStatus("TTS audio error: "+safe(t),RED));}
            finally{f.delete();}
        },"InfoBeam-Hypersonic-TTS").start();
    }

    private void pickAudio() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/*");startActivityForResult(i,PICK_AUDIO);
    }

    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(req==PICK_AUDIO&&res==RESULT_OK&&data!=null&&data.getData()!=null){chosenAudio=data.getData();try{getContentResolver().takePersistableUriPermission(chosenAudio,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));}catch(Throwable ignored){}chosenAudioLabel.setText("Selected: "+displayName(chosenAudio));chosenAudioLabel.setTextColor(CYAN);singleSource.setSelection(1);}
    }

    private String displayName(Uri uri){
        try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Throwable ignored){}return uri.getLastPathSegment()==null?"audio":uri.getLastPathSegment();
    }

    private AudioDeviceInfo findExternal(){
        AudioDeviceInfo best=null;int score=-1;
        for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
            int s=0;switch(d.getType()){
                case AudioDeviceInfo.TYPE_USB_DEVICE:s=100;break;case AudioDeviceInfo.TYPE_USB_HEADSET:s=95;break;case AudioDeviceInfo.TYPE_LINE_DIGITAL:s=90;break;case AudioDeviceInfo.TYPE_HDMI:s=80;break;case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:s=60;break;case AudioDeviceInfo.TYPE_WIRED_HEADSET:s=55;break;default:s=10;
            }if(s>score){score=s;best=d;}
        }return best;
    }

    private void scanRoute(){
        StringBuilder b=new StringBuilder();AudioDeviceInfo chosen=findExternal();
        for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){b.append("• ").append(d.getProductName()).append(" • type ").append(d.getType());int[]r=d.getSampleRates();if(r!=null&&r.length>0)b.append(" • ").append(Arrays.toString(r)).append(" Hz");b.append('\n');}
        if(chosen!=null)b.append("\nPreferred route: ").append(chosen.getProductName());
        b.append("\nUnified mode: identical full mix on L + R");
        routeInfo.setText(b.toString());
    }

    @Override protected void onDestroy(){engine.stop();if(tts!=null)try{tts.shutdown();}catch(Throwable ignored){}super.onDestroy();}

    private int rate(Spinner s){return s.getSelectedItemPosition()==0?192000:96000;}
    private double parse(EditText e,double fallback){try{return Double.parseDouble(e.getText().toString().trim());}catch(Throwable t){return fallback;}}
    private double pct(SeekBar s){return s.getProgress()/100.0;}
    private void setStatus(String s,int c){status.setText(s);status.setTextColor(c);}
    private EditText field(String hint,int lines){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(Color.rgb(100,126,134));e.setTextColor(TEXT);e.setTextSize(16);e.setMinLines(lines);e.setGravity(Gravity.TOP|Gravity.START);e.setPadding(dp(11),dp(10),dp(11),dp(10));e.setBackground(round(Color.rgb(5,12,16),9,Color.rgb(44,71,77)));return e;}
    private EditText numberField(String value,boolean decimal){EditText e=field("",1);e.setText(value);e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|(decimal?android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL:0));return e;}
    private Spinner spinner(String[] values){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,values){@Override public View getView(int p,View c,android.view.ViewGroup parent){TextView v=(TextView)super.getView(p,c,parent);v.setTextColor(TEXT);v.setTextSize(14);return v;}};s.setAdapter(a);return s;}
    private SeekBar slider(int p,int color,IntConsumer f){SeekBar s=new SeekBar(this);s.setMax(100);s.setProgress(p);s.setProgressTintList(ColorStateList.valueOf(color));s.setThumbTintList(ColorStateList.valueOf(color));s.setOnSeekBarChangeListener(new Seek(f));return s;}
    private LinearLayout card(LinearLayout root,String title,String sub){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(13),dp(13),dp(13),dp(13));c.setBackground(round(CARD,13,Color.rgb(37,61,67)));root.addView(c,top(12));c.addView(text(title,16,CYAN,true));TextView t=text(sub,12,MUTED,false);t.setPadding(0,dp(4),0,dp(7));c.addView(t);return c;}
    private TextView text(String s,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setLineSpacing(0,1.08f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private Button button(String s,int tc,int bg){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(tc);b.setTextSize(14);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackgroundTintList(ColorStateList.valueOf(bg));return b;}
    private GradientDrawable round(int fill,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(radius));g.setStroke(dp(1),stroke);return g;}
    private LinearLayout.LayoutParams full(){return new LinearLayout.LayoutParams(-1,-2);}
    private LinearLayout.LayoutParams top(int n){LinearLayout.LayoutParams p=full();p.topMargin=dp(n);return p;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private static String safe(Throwable t){String m=t.getMessage();return m==null||m.trim().isEmpty()?t.getClass().getSimpleName():m;}
    private interface IntConsumer{void accept(int v);}
    private static final class Seek implements SeekBar.OnSeekBarChangeListener{private final IntConsumer f;Seek(IntConsumer f){this.f=f;}@Override public void onProgressChanged(SeekBar b,int p,boolean u){f.accept(p);}@Override public void onStartTrackingTouch(SeekBar b){}@Override public void onStopTrackingTouch(SeekBar b){}}
}
