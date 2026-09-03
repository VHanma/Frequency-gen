package com.vaan.infobeam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioMixerAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class HypersonicV4Activity extends Activity {
    private static final int PICK_AUDIO = 4401;
    private static final int BG=Color.rgb(2,6,9), CARD=Color.rgb(10,19,25), TEXT=Color.rgb(239,253,255);
    private static final int CYAN=Color.rgb(67,242,255), GOLD=Color.rgb(255,204,91), VIOLET=Color.rgb(194,143,255);
    private static final int RED=Color.rgb(255,103,114), MUTED=Color.rgb(145,174,184), GREEN=Color.rgb(102,255,175);

    private enum Pending { NONE, SINGLE_TTS, DUAL_TTS }

    private final HypersonicV4Engine engine = new HypersonicV4Engine();
    private AudioManager audioManager;
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private volatile Pending pending=Pending.NONE;
    private volatile String pendingId;
    private volatile File pendingFile;
    private Uri chosenAudio;

    private LinearLayout singlePanel, dualPanel;
    private Button tabSingle, tabDual;
    private TextView status, routeInfo, audioFileLabel, pairAnalysis;

    private Spinner singleSource, singleModulation, singleElfMode, singleRate;
    private EditText singleText, singleCarrier, singleEmitterBw, singleAudioBw;
    private CheckBox singlePrecision;
    private SeekBar singleDepth, singleBeam, singleElfDirect, singleElfEnvelope;
    private TextView singleDepthLabel, singleBeamLabel, singleElfDirectLabel, singleElfEnvelopeLabel;

    private Spinner dualModulation, dualElfMode, dualRate;
    private EditText dualText, carrierA, carrierB, elfA, elfB, dualEmitterBw, dualAudioBw;
    private CheckBox dualPrecision;
    private SeekBar dualDepth, dualBeam, dualElfDirect, dualElfEnvelope;
    private TextView dualDepthLabel, dualBeamLabel, dualElfDirectLabel, dualElfEnvelopeLabel;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        audioManager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        initTts();
        scanRoutes();
    }

    private View buildUi() {
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(14),dp(14),dp(36));
        scroll.addView(root,new ScrollView.LayoutParams(-1,-2));

        root.addView(text("INFOBEAM Ω • PARAMETRIC LAB v4",25,CYAN,true));
        TextView sub=text("Research-driven PAL modulation • exact shared clock • fused ELF envelope • USB precision negotiation",13,MUTED,false);
        sub.setPadding(0,dp(4),0,dp(10)); root.addView(sub);
        status=text("ENGINE STARTING",14,GOLD,true);
        status.setPadding(dp(12),dp(10),dp(12),dp(10));
        status.setBackground(round(Color.rgb(24,35,40),12,GOLD));
        root.addView(status,full());

        LinearLayout tabs=new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL); tabs.setPadding(0,dp(10),0,0);
        tabSingle=button("LASER + 7.83",CYAN,Color.rgb(3,37,42));
        tabDual=button("DUAL MATRIX",MUTED,Color.rgb(20,27,31));
        tabs.addView(tabSingle,new LinearLayout.LayoutParams(0,-2,1));
        tabs.addView(tabDual,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(tabs);

        singlePanel=buildSingle(); dualPanel=buildDual();
        root.addView(singlePanel,top(8)); root.addView(dualPanel,top(8));
        dualPanel.setVisibility(View.GONE);
        tabSingle.setOnClickListener(v->showTab(true));
        tabDual.setOnClickListener(v->showTab(false));

        LinearLayout hw=card(root,"PRECISION OUTPUT","Android 14+ USB routes are queried for supported mixer formats. When the device/HAL exposes matching PCM16 mixer attributes, v4 requests that exact USB mixer and prefers bit-perfect behavior when advertised.");
        routeInfo=text("Scanning outputs…",12,MUTED,false); hw.addView(routeInfo);
        Button rescan=button("RESCAN OUTPUT HARDWARE",TEXT,Color.rgb(22,35,41)); rescan.setOnClickListener(v->scanRoutes()); hw.addView(rescan,top(7));

        LinearLayout research=card(root,"WHY v4 IS DIFFERENT","Hybrid PAL uses low-band square-root AM plus high-band single-sideband. Audio bandwidth is automatically constrained by the emitter bandwidth and carrier/Nyquist headroom. ELF can be injected directly and simultaneously into the ultrasonic envelope. Dual mode also calculates the carrier difference frequency because nonlinear air propagation can convert two primary carriers into that difference component.");
        research.addView(text("For a narrow 40 kHz ceramic array, set the real measured emitter bandwidth instead of leaving a huge audio sideband that the hardware cannot reproduce.",12,GOLD,false));

        Button stop=button("STOP ALL",RED,Color.rgb(44,9,15)); stop.setOnClickListener(v->stopAll()); root.addView(stop,top(12));
        return scroll;
    }

    private LinearLayout buildSingle() {
        LinearLayout p=panel();
        p.addView(text("TAB 1 • ONE HYPERSONIC BEAM + EXACT 7.830 Hz",17,CYAN,true));
        TextView n=text("Your TTS or chosen audio modulates the selected ultrasonic carrier while 7.830 Hz runs from the same sample clock. Fused ELF mode puts 7.830 Hz in the direct waveform and in the carrier envelope at the same time.",12,MUTED,false); n.setPadding(0,dp(4),0,dp(9)); p.addView(n);

        singleSource=spinner(new String[]{"Text → TTS","Chosen audio file"}); p.addView(singleSource);
        singleText=field("Text to transmit hypersonically",4); p.addView(singleText,top(7));
        Button choose=button("CHOOSE AUDIO FILE",GOLD,Color.rgb(40,29,4)); choose.setOnClickListener(v->pickAudio()); p.addView(choose,top(7));
        audioFileLabel=text("No audio selected",12,MUTED,false); p.addView(audioFileLabel,top(4));

        p.addView(label("Carrier frequency (Hz)"),top(9)); singleCarrier=number("40000",false); p.addView(singleCarrier);
        p.addView(text("ELF frequency: 7.830 Hz • fixed",13,GOLD,true),top(7));
        p.addView(label("Emitter usable bandwidth, full width (Hz)"),top(8)); singleEmitterBw=number("8000",false); p.addView(singleEmitterBw);
        p.addView(label("Requested audible message bandwidth (Hz)"),top(6)); singleAudioBw=number("4200",false); p.addView(singleAudioBw);

        singleModulation=enumSpinner(HypersonicV4Engine.Modulation.values()); p.addView(singleModulation,top(8));
        singleModulation.setSelection(HypersonicV4Engine.Modulation.HYBRID_PAL.ordinal());
        singleElfMode=enumSpinner(HypersonicV4Engine.ElfMode.values()); p.addView(singleElfMode,top(6));
        singleRate=spinner(new String[]{"192000 Hz preferred","176400 Hz preferred","96000 Hz preferred","88200 Hz preferred","48000 Hz fallback"}); p.addView(singleRate,top(6));
        singlePrecision=check("USB precision mixer / bit-perfect when available",true); p.addView(singlePrecision,top(5));

        singleDepthLabel=text("Audio modulation depth 86%",12,TEXT,true); p.addView(singleDepthLabel,top(7));
        singleDepth=slider(86,CYAN,v->singleDepthLabel.setText("Audio modulation depth "+v+"%")); p.addView(singleDepth);
        singleBeamLabel=text("Ultrasonic line level 34%",12,TEXT,true); p.addView(singleBeamLabel);
        singleBeam=slider(34,GOLD,v->singleBeamLabel.setText("Ultrasonic line level "+v+"%")); p.addView(singleBeam);
        singleElfDirectLabel=text("Direct ELF level 9%",12,TEXT,true); p.addView(singleElfDirectLabel);
        singleElfDirect=slider(9,VIOLET,v->singleElfDirectLabel.setText("Direct ELF level "+v+"%")); p.addView(singleElfDirect);
        singleElfEnvelopeLabel=text("ELF → ultrasonic envelope 18%",12,TEXT,true); p.addView(singleElfEnvelopeLabel);
        singleElfEnvelope=slider(18,VIOLET,v->singleElfEnvelopeLabel.setText("ELF → ultrasonic envelope "+v+"%")); p.addView(singleElfEnvelope);

        Button fire=button("FIRE PAL + 7.830 Hz TOGETHER",CYAN,Color.rgb(2,42,48)); fire.setOnClickListener(v->startSingle()); p.addView(fire,top(9));
        return p;
    }

    private LinearLayout buildDual() {
        LinearLayout p=panel();
        p.addView(text("TAB 2 • TWO HYPERSONIC CARRIERS + TWO ELF",17,CYAN,true));
        TextView n=text("One TTS source modulates BOTH selected hypersonic carriers. BOTH chosen ELF signals run simultaneously, and in Fused mode each ELF also modulates the ultrasonic envelope. Everything is summed into one unified waveform and mirrored identically to L/R.",12,MUTED,false); n.setPadding(0,dp(4),0,dp(9)); p.addView(n);

        dualText=field("Text for dual hypersonic TTS",4); p.addView(dualText);
        p.addView(label("Carrier A (Hz)"),top(8)); carrierA=number("40000",false); p.addView(carrierA);
        p.addView(label("Carrier B (Hz)"),top(6)); carrierB=number("65000",false); p.addView(carrierB);
        p.addView(label("ELF A (Hz)"),top(6)); elfA=number("7.83",true); p.addView(elfA);
        p.addView(label("ELF B (Hz)"),top(6)); elfB=number("10.00",true); p.addView(elfB);

        pairAnalysis=text("Carrier-pair analysis appears here.",13,GOLD,true); pairAnalysis.setPadding(dp(10),dp(9),dp(10),dp(9)); pairAnalysis.setBackground(round(Color.rgb(29,25,13),10,Color.rgb(89,70,25))); p.addView(pairAnalysis,top(8));
        Button analyze=button("ANALYZE CARRIER INTERMOD",GOLD,Color.rgb(42,30,4)); analyze.setOnClickListener(v->analyzePair()); p.addView(analyze,top(6));

        p.addView(label("Emitter usable bandwidth, full width (Hz)"),top(8)); dualEmitterBw=number("30000",false); p.addView(dualEmitterBw);
        p.addView(label("Requested audible message bandwidth (Hz)"),top(6)); dualAudioBw=number("4200",false); p.addView(dualAudioBw);
        dualModulation=enumSpinner(HypersonicV4Engine.Modulation.values()); dualModulation.setSelection(HypersonicV4Engine.Modulation.HYBRID_PAL.ordinal()); p.addView(dualModulation,top(8));
        dualElfMode=enumSpinner(HypersonicV4Engine.ElfMode.values()); p.addView(dualElfMode,top(6));
        dualRate=spinner(new String[]{"192000 Hz preferred","176400 Hz preferred","96000 Hz preferred","88200 Hz preferred","48000 Hz fallback"}); p.addView(dualRate,top(6));
        dualPrecision=check("USB precision mixer / bit-perfect when available",true); p.addView(dualPrecision,top(5));

        dualDepthLabel=text("Audio modulation depth 82%",12,TEXT,true); p.addView(dualDepthLabel,top(7));
        dualDepth=slider(82,CYAN,v->dualDepthLabel.setText("Audio modulation depth "+v+"%")); p.addView(dualDepth);
        dualBeamLabel=text("Combined carrier line level 31%",12,TEXT,true); p.addView(dualBeamLabel);
        dualBeam=slider(31,GOLD,v->dualBeamLabel.setText("Combined carrier line level "+v+"%")); p.addView(dualBeam);
        dualElfDirectLabel=text("Direct dual-ELF level 8%",12,TEXT,true); p.addView(dualElfDirectLabel);
        dualElfDirect=slider(8,VIOLET,v->dualElfDirectLabel.setText("Direct dual-ELF level "+v+"%")); p.addView(dualElfDirect);
        dualElfEnvelopeLabel=text("Each ELF → its carrier envelope 15%",12,TEXT,true); p.addView(dualElfEnvelopeLabel);
        dualElfEnvelope=slider(15,VIOLET,v->dualElfEnvelopeLabel.setText("Each ELF → its carrier envelope "+v+"%")); p.addView(dualElfEnvelope);

        Button fire=button("FIRE ALL 5 COMPONENTS TOGETHER",CYAN,Color.rgb(2,42,48)); fire.setOnClickListener(v->startDual()); p.addView(fire,top(9));
        analyzePair();
        return p;
    }

    private void showTab(boolean single) {
        singlePanel.setVisibility(single?View.VISIBLE:View.GONE); dualPanel.setVisibility(single?View.GONE:View.VISIBLE);
        tabSingle.setTextColor(single?CYAN:MUTED); tabDual.setTextColor(single?MUTED:CYAN);
        tabSingle.setBackgroundTintList(ColorStateList.valueOf(single?Color.rgb(3,37,42):Color.rgb(20,27,31)));
        tabDual.setBackgroundTintList(ColorStateList.valueOf(single?Color.rgb(20,27,31):Color.rgb(3,37,42)));
        if(!single) analyzePair();
    }

    private void startSingle() {
        engine.stop();
        if(singleSource.getSelectedItemPosition()==1) {
            if(chosenAudio==null){setStatus("Choose an audio file first.",RED);return;}
            setStatus("Decoding chosen audio…",GOLD);
            Uri uri=chosenAudio;
            new Thread(()->{
                try { PcmWav.Data pcm=AudioSourceDecoder.decode(this,uri,900); runOnUiThread(()->playSingle(pcm)); }
                catch(Throwable t){runOnUiThread(()->setStatus("Audio decode error: "+safe(t),RED));}
            },"InfoBeam-v4-Decode").start();
        } else {
            String s=singleText.getText().toString().trim(); if(s.isEmpty()){setStatus("Type text first.",RED);return;} synth(Pending.SINGLE_TTS,s);
        }
    }

    private void startDual() {
        String s=dualText.getText().toString().trim(); if(s.isEmpty()){setStatus("Type text first.",RED);return;}
        analyzePair(); synth(Pending.DUAL_TTS,s);
    }

    private HypersonicV4Engine.Common common(boolean dual) {
        Spinner mod=dual?dualModulation:singleModulation;
        Spinner elf=dual?dualElfMode:singleElfMode;
        SeekBar depth=dual?dualDepth:singleDepth, beam=dual?dualBeam:singleBeam;
        SeekBar direct=dual?dualElfDirect:singleElfDirect, env=dual?dualElfEnvelope:singleElfEnvelope;
        EditText emitter=dual?dualEmitterBw:singleEmitterBw, audio=dual?dualAudioBw:singleAudioBw;
        Spinner rate=dual?dualRate:singleRate;
        CheckBox precision=dual?dualPrecision:singlePrecision;
        return new HypersonicV4Engine.Common(
                (HypersonicV4Engine.Modulation)mod.getSelectedItem(),
                (HypersonicV4Engine.ElfMode)elf.getSelectedItem(),
                pct(depth), pct(beam), pct(direct), pct(env),
                parse(audio,4200), parse(emitter,8000), requestedRate(rate),
                precision.isChecked(), findBestOutput());
    }

    private void playSingle(PcmWav.Data pcm) {
        try {
            HypersonicV4Engine.SingleConfig cfg=new HypersonicV4Engine.SingleConfig(parse(singleCarrier,40000),7.830,common(false));
            engine.playSingle(this,pcm,cfg,engineListener);
        } catch(Throwable t){setStatus("Configuration error: "+safe(t),RED);}
    }

    private void playDual(PcmWav.Data pcm) {
        try {
            HypersonicV4Engine.DualConfig cfg=new HypersonicV4Engine.DualConfig(
                    parse(carrierA,40000),parse(carrierB,65000),parse(elfA,7.83),parse(elfB,10.0),common(true));
            engine.playDual(this,pcm,cfg,engineListener);
        } catch(Throwable t){setStatus("Configuration error: "+safe(t),RED);}
    }

    private final HypersonicV4Engine.Listener engineListener=new HypersonicV4Engine.Listener(){
        @Override public void onStatus(String s){runOnUiThread(()->setStatus(s,s.toLowerCase(Locale.US).contains("error")?RED:GREEN));}
        @Override public void onStopped(){}
    };

    private void initTts() {
        tts=new TextToSpeech(getApplicationContext(),r->{
            if(r==TextToSpeech.SUCCESS){
                ttsReady=true;
                int lang=tts.setLanguage(Locale.US);
                if(lang==TextToSpeech.LANG_MISSING_DATA||lang==TextToSpeech.LANG_NOT_SUPPORTED)tts.setLanguage(Locale.getDefault());
                tts.setSpeechRate(0.98f); tts.setPitch(0.92f);
                runOnUiThread(()->setStatus("READY • Hybrid PAL + fused ELF is the v4 default",CYAN));
            } else runOnUiThread(()->setStatus("TTS unavailable",RED));
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){}
            @Override public void onDone(String id){
                if(id==null||!id.equals(pendingId))return;
                File f=pendingFile; Pending k=pending; pendingId=null; pendingFile=null; pending=Pending.NONE;
                if(f!=null) dispatchTts(f,k);
            }
            @Override @Deprecated public void onError(String id){onError(id,TextToSpeech.ERROR);}
            @Override public void onError(String id,int code){if(id!=null&&id.equals(pendingId))runOnUiThread(()->setStatus("TTS synthesis error: "+code,RED));}
        });
    }

    private void synth(Pending kind,String s) {
        if(!ttsReady||tts==null){setStatus("Voice engine is still initializing.",RED);return;}
        engine.stop(); try{tts.stop();}catch(Throwable ignored){}
        File f=new File(getCacheDir(),"palv4_"+System.nanoTime()+".wav");
        pending=kind; pendingFile=f; pendingId="palv4-"+System.nanoTime();
        setStatus("Synthesizing PAL source…",GOLD);
        int r=tts.synthesizeToFile(s,new Bundle(),f,pendingId);
        if(r!=TextToSpeech.SUCCESS)setStatus("TTS rejected synthesis.",RED);
    }

    private void dispatchTts(File f,Pending kind) {
        new Thread(()->{
            try {
                PcmWav.Data pcm=PcmWav.read(f);
                runOnUiThread(()->{if(kind==Pending.SINGLE_TTS)playSingle(pcm);else if(kind==Pending.DUAL_TTS)playDual(pcm);});
            } catch(Throwable t){runOnUiThread(()->setStatus("TTS audio error: "+safe(t),RED));}
            finally{f.delete();}
        },"InfoBeam-v4-TTS").start();
    }

    private void analyzePair() {
        if(pairAnalysis==null||carrierA==null||carrierB==null)return;
        try {
            double a=parse(carrierA,40000), b=parse(carrierB,65000), d=Math.abs(a-b);
            String zone;
            int color;
            if(d>=20000){zone="difference stays ultrasonic";color=GREEN;}
            else if(d>=20){zone="difference lands in audible band";color=RED;}
            else {zone="difference is infrasonic";color=GOLD;}
            pairAnalysis.setText(String.format(Locale.US,"|A − B| = %.3f Hz • %s\nNonlinear air/transducer stages can generate this difference component.",d,zone));
            pairAnalysis.setTextColor(color);
        } catch(Throwable t){pairAnalysis.setText("Enter valid carrier frequencies.");pairAnalysis.setTextColor(RED);}
    }

    private void pickAudio() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("audio/*"); startActivityForResult(i,PICK_AUDIO);
    }

    @Override protected void onActivityResult(int req,int res,Intent data) {
        super.onActivityResult(req,res,data);
        if(req==PICK_AUDIO&&res==RESULT_OK&&data!=null&&data.getData()!=null){
            chosenAudio=data.getData();
            try{getContentResolver().takePersistableUriPermission(chosenAudio,data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Throwable ignored){}
            audioFileLabel.setText("Selected: "+displayName(chosenAudio)); audioFileLabel.setTextColor(CYAN); singleSource.setSelection(1);
        }
    }

    private String displayName(Uri uri) {
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getString(0);
        } catch(Throwable ignored){}
        return uri.getLastPathSegment()==null?"audio":uri.getLastPathSegment();
    }

    private AudioDeviceInfo findBestOutput() {
        AudioDeviceInfo wired=null,hdmi=null;
        for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
            if(d.getType()==AudioDeviceInfo.TYPE_USB_DEVICE||d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET)return d;
            if(d.getType()==AudioDeviceInfo.TYPE_LINE_DIGITAL)wired=d;
            if(d.getType()==AudioDeviceInfo.TYPE_HDMI)hdmi=d;
        }
        return wired!=null?wired:hdmi;
    }

    private void scanRoutes() {
        StringBuilder s=new StringBuilder();
        AudioDeviceInfo best=findBestOutput();
        for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
            s.append("• ").append(d.getProductName()).append(" • type ").append(d.getType()).append('\n');
            if(d.getSampleRates()!=null&&d.getSampleRates().length>0)s.append("  rates ").append(Arrays.toString(d.getSampleRates())).append('\n');
            if(d.getChannelCounts()!=null&&d.getChannelCounts().length>0)s.append("  channels ").append(Arrays.toString(d.getChannelCounts())).append('\n');
            if(Build.VERSION.SDK_INT>=34&&(d.getType()==AudioDeviceInfo.TYPE_USB_DEVICE||d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET)){
                try{
                    List<AudioMixerAttributes> m=audioManager.getSupportedMixerAttributes(d);
                    s.append("  USB mixer profiles: ").append(m==null?0:m.size()).append('\n');
                    if(m!=null)for(AudioMixerAttributes a:m){
                        AudioFormat f=a.getFormat();
                        s.append("    ").append(f.getSampleRate()).append(" Hz / ").append(f.getChannelCount()).append("ch / enc ").append(f.getEncoding());
                        if(a.getMixerBehavior()==AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)s.append(" / BIT-PERFECT");
                        s.append('\n');
                    }
                }catch(Throwable ignored){}
            }
        }
        s.append("\nPreferred PAL route: ").append(best==null?"none detected; Android default will be tried":best.getProductName());
        routeInfo.setText(s.toString());
    }

    private int requestedRate(Spinner s) {
        switch(s.getSelectedItemPosition()){
            case 1:return 176400;
            case 2:return 96000;
            case 3:return 88200;
            case 4:return 48000;
            default:return 192000;
        }
    }

    private void stopAll(){engine.stop();if(tts!=null)try{tts.stop();}catch(Throwable ignored){}setStatus("Stopped.",GOLD);}
    @Override protected void onDestroy(){stopAll();if(tts!=null)try{tts.shutdown();}catch(Throwable ignored){}super.onDestroy();}

    private LinearLayout panel(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(14),dp(14),dp(14),dp(14));p.setBackground(round(CARD,14,Color.rgb(38,63,69)));return p;}
    private LinearLayout card(LinearLayout root,String title,String sub){LinearLayout p=panel();root.addView(p,top(12));p.addView(text(title,16,CYAN,true));TextView n=text(sub,12,MUTED,false);n.setPadding(0,dp(4),0,dp(8));p.addView(n);return p;}
    private TextView label(String s){return text(s,12,TEXT,true);}
    private TextView text(String s,int sp,int c,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(c);v.setLineSpacing(0,1.08f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private Button button(String s,int tc,int bg){Button b=new Button(this);b.setText(s);b.setTextColor(tc);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackgroundTintList(ColorStateList.valueOf(bg));return b;}
    private EditText field(String hint,int lines){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(Color.rgb(104,132,141));e.setTextColor(TEXT);e.setTextSize(16);e.setGravity(Gravity.TOP|Gravity.START);e.setMinLines(lines);e.setMaxLines(Math.max(lines,7));e.setPadding(dp(11),dp(10),dp(11),dp(10));e.setBackground(round(Color.rgb(4,12,16),10,Color.rgb(48,78,84)));return e;}
    private EditText number(String value,boolean decimal){EditText e=field("",1);e.setMinLines(1);e.setMaxLines(1);e.setText(value);e.setInputType(InputType.TYPE_CLASS_NUMBER|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));return e;}
    private Spinner spinner(String[] a){Spinner s=new Spinner(this);ArrayAdapter<String> ad=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,a){@Override public View getView(int p,View c,android.view.ViewGroup parent){TextView v=(TextView)super.getView(p,c,parent);v.setTextColor(TEXT);return v;}};s.setAdapter(ad);return s;}
    private <T> Spinner enumSpinner(T[] a){Spinner s=new Spinner(this);ArrayAdapter<T> ad=new ArrayAdapter<T>(this,android.R.layout.simple_spinner_dropdown_item,a){@Override public View getView(int p,View c,android.view.ViewGroup parent){TextView v=(TextView)super.getView(p,c,parent);v.setTextColor(TEXT);return v;}};s.setAdapter(ad);return s;}
    private CheckBox check(String s,boolean on){CheckBox c=new CheckBox(this);c.setText(s);c.setTextColor(TEXT);c.setChecked(on);c.setButtonTintList(ColorStateList.valueOf(CYAN));return c;}
    private SeekBar slider(int p,int color,IntConsumer f){SeekBar b=new SeekBar(this);b.setMax(100);b.setProgress(p);b.setProgressTintList(ColorStateList.valueOf(color));b.setThumbTintList(ColorStateList.valueOf(color));b.setOnSeekBarChangeListener(new Seek(f));return b;}
    private GradientDrawable round(int fill,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(radius));g.setStroke(dp(1),stroke);return g;}
    private LinearLayout.LayoutParams full(){return new LinearLayout.LayoutParams(-1,-2);}
    private LinearLayout.LayoutParams top(int d){LinearLayout.LayoutParams p=full();p.topMargin=dp(d);return p;}
    private int dp(int d){return Math.round(d*getResources().getDisplayMetrics().density);}
    private float pct(SeekBar b){return b.getProgress()/100f;}
    private double parse(EditText e,double fallback){try{return Double.parseDouble(e.getText().toString().trim());}catch(Throwable ignored){return fallback;}}
    private void setStatus(String s,int c){if(status!=null){status.setText(s);status.setTextColor(c);}}
    private static String safe(Throwable t){String m=t.getMessage();return m==null||m.trim().isEmpty()?t.getClass().getSimpleName():m;}
    private interface IntConsumer{void accept(int v);}
    private static final class Seek implements SeekBar.OnSeekBarChangeListener{final IntConsumer f;Seek(IntConsumer f){this.f=f;}@Override public void onProgressChanged(SeekBar b,int p,boolean u){f.accept(p);}@Override public void onStartTrackingTouch(SeekBar b){}@Override public void onStopTrackingTouch(SeekBar b){}}
}
