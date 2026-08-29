package com.vaan.infobeam;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.Locale;

public final class NeuroBeamActivity extends Activity {
    private static final int REQ_MIC = 81;
    private static final int BG = Color.rgb(3,7,10), CARD = Color.rgb(12,22,29), TEXT = Color.rgb(239,255,252);
    private static final int MUTED = Color.rgb(153,181,181), AQUA = Color.rgb(76,245,211), GOLD = Color.rgb(255,207,99);
    private static final int VIOLET = Color.rgb(190,139,255), RED = Color.rgb(255,103,112);

    private enum Pending { NONE, THOUGHT, PHONE_BEAM, EXTERNAL_BEAM }

    private final UltrasonicFsk fsk = new UltrasonicFsk();
    private final ParametricAudio parametric = new ParametricAudio();
    private final ThoughtAudio thought = new ThoughtAudio();
    private AudioManager audioManager;
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private volatile Pending pending = Pending.NONE;
    private volatile String pendingId;
    private volatile File pendingFile;

    private EditText input;
    private Spinner thoughtProfile, dataProfile;
    private SeekBar intensity, bone, whisper, depth, impact, phoneCarrier;
    private TextView status, decoded, intensityLabel, boneLabel, whisperLabel, depthLabel, impactLabel, carrierLabel, hardware;
    private CheckBox autoThought;
    private volatile String lastDecoded = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        initTts();
        scanHardware();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(16),dp(16),dp(34));
        scroll.addView(root, new ScrollView.LayoutParams(-1,-2));

        root.addView(text("INFO BEAM Ω • CORTEX LOCK",25,AQUA,true));
        TextView sub = text("High-impact head-locked inner speech + ultrasonic information beam",13,MUTED,false);
        sub.setPadding(0,dp(4),0,dp(12));
        root.addView(sub);
        status = text("ENGINE STARTING",14,GOLD,true);
        status.setPadding(dp(12),dp(10),dp(12),dp(10));
        status.setBackground(round(Color.rgb(27,38,35),12,GOLD));
        root.addView(status, full());

        LinearLayout thoughtCard = card(root,"CORTEX LOCK LAB","v1.3 is intentionally denser and more immediate. Headphones/earbuds give the strongest in-head center image; the phone earpiece is the private fallback.");
        input = new EditText(this);
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.rgb(102,128,129));
        input.setHint("Type the information / thought here");
        input.setTextSize(17);
        input.setGravity(Gravity.TOP|Gravity.START);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setPadding(dp(12),dp(12),dp(12),dp(12));
        input.setBackground(round(Color.rgb(5,13,18),10,Color.rgb(48,78,80)));
        thoughtCard.addView(input,full());

        thoughtProfile = new Spinner(this);
        ArrayAdapter<ThoughtAudio.Profile> tp = new ArrayAdapter<ThoughtAudio.Profile>(this, android.R.layout.simple_spinner_dropdown_item, ThoughtAudio.Profile.values()) {
            @Override public View getView(int p, View c, ViewGroup parent) {
                TextView v=(TextView)super.getView(p,c,parent);
                v.setTextColor(TEXT);
                v.setTextSize(15);
                return v;
            }
        };
        thoughtProfile.setAdapter(tp);
        thoughtProfile.setSelection(ThoughtAudio.Profile.CORTEX_LOCK_EXTREME.ordinal());
        thoughtCard.addView(thoughtProfile, top(8));

        intensityLabel = text("Intensity 100%",13,VIOLET,true);
        thoughtCard.addView(intensityLabel, top(8));
        intensity = slider(100,VIOLET,p -> intensityLabel.setText("Intensity "+p+"%"));
        thoughtCard.addView(intensity,full());

        boneLabel = text("Skull / body 90%",13,TEXT,true);
        thoughtCard.addView(boneLabel);
        bone = slider(90,AQUA,p -> boneLabel.setText("Skull / body "+p+"%"));
        thoughtCard.addView(bone,full());

        whisperLabel = text("Whisper trace 14%",13,TEXT,true);
        thoughtCard.addView(whisperLabel);
        whisper = slider(14,GOLD,p -> whisperLabel.setText("Whisper trace "+p+"%"));
        thoughtCard.addView(whisper,full());

        depthLabel = text("Internal depth 100%",13,TEXT,true);
        thoughtCard.addView(depthLabel);
        depth = slider(100,VIOLET,p -> depthLabel.setText("Internal depth "+p+"%"));
        thoughtCard.addView(depth,full());

        impactLabel = text("Impact / density 100%",13,RED,true);
        thoughtCard.addView(impactLabel);
        impact = slider(100,RED,p -> impactLabel.setText("Impact / density "+p+"%"));
        thoughtCard.addView(impact,full());

        Button max = button("MAX CORTEX LOCK",RED,Color.rgb(52,8,17));
        max.setOnClickListener(v -> {
            applyMaxPreset();
            synthesize(Pending.THOUGHT, input.getText().toString().trim());
        });
        thoughtCard.addView(max,top(8));

        Button preview = button("HEAR THOUGHT NOW",VIOLET,Color.rgb(31,15,50));
        preview.setOnClickListener(v -> synthesize(Pending.THOUGHT, input.getText().toString().trim()));
        thoughtCard.addView(preview,top(7));

        TextView note = text("The engine now uses media-volume routing on headphones, dense upward compression, parallel skull/body bands, formant reinforcement, two sub-millisecond cranial delays, speech-gated breath texture, and a final hard ceiling.",12,MUTED,false);
        note.setPadding(0,dp(8),0,0);
        thoughtCard.addView(note);

        LinearLayout info = card(root,"ULTRASONIC INFORMATION LINK","Send coded information to another InfoBeam receiver. Verified messages can automatically become Cortex Lock audio on the receiving phone.");
        dataProfile = new Spinner(this);
        ArrayAdapter<UltrasonicFsk.Profile> dpA = new ArrayAdapter<UltrasonicFsk.Profile>(this,android.R.layout.simple_spinner_dropdown_item,UltrasonicFsk.Profile.values()) {
            @Override public View getView(int p, View c, ViewGroup parent) {
                TextView v=(TextView)super.getView(p,c,parent);
                v.setTextColor(TEXT);
                return v;
            }
        };
        dataProfile.setAdapter(dpA);
        info.addView(dataProfile,full());
        Button tx=button("TRANSMIT INFO",AQUA,Color.rgb(1,31,28));
        tx.setOnClickListener(v->transmit());
        info.addView(tx,top(6));
        Button rx=button("RECEIVE + CORTEX LOCK",GOLD,Color.rgb(38,29,4));
        rx.setOnClickListener(v->listen());
        info.addView(rx,top(7));
        autoThought = new CheckBox(this);
        autoThought.setText("Auto-render every verified message as Cortex Lock");
        autoThought.setTextColor(TEXT);
        autoThought.setChecked(true);
        autoThought.setButtonTintList(ColorStateList.valueOf(VIOLET));
        info.addView(autoThought,top(6));
        decoded = text("No verified beam received yet.",16,TEXT,false);
        decoded.setPadding(dp(10),dp(10),dp(10),dp(10));
        decoded.setBackground(round(Color.rgb(6,15,18),10,Color.rgb(45,73,75)));
        info.addView(decoded,top(8));
        Button replay=button("REPLAY LAST CORTEX LOCK",VIOLET,Color.rgb(31,15,50));
        replay.setOnClickListener(v->synthesize(Pending.THOUGHT,lastDecoded));
        info.addView(replay,top(7));

        LinearLayout beam = card(root,"DIRECT THOUGHT BEAM","Phone mode remains hardware-dependent and searches the upper speaker band. External mode is the stronger true parametric-array path.");
        carrierLabel=text("Phone carrier center 16.8 kHz • adaptive dual sweep",13,GOLD,true);
        beam.addView(carrierLabel);
        phoneCarrier = new SeekBar(this);
        phoneCarrier.setMax(94);
        phoneCarrier.setProgress(46);
        phoneCarrier.setProgressTintList(ColorStateList.valueOf(GOLD));
        phoneCarrier.setThumbTintList(ColorStateList.valueOf(GOLD));
        phoneCarrier.setOnSeekBarChangeListener(new Seek(progress -> {
            double hz=14500+progress*50.0;
            carrierLabel.setText(String.format(Locale.US,"Phone carrier center %.2f kHz • adaptive dual sweep",hz/1000.0));
        }));
        beam.addView(phoneCarrier,full());
        Button phone=button("MAX PHONE THOUGHT BEAM",GOLD,Color.rgb(39,29,4));
        phone.setOnClickListener(v->synthesize(Pending.PHONE_BEAM,input.getText().toString().trim()));
        beam.addView(phone,top(7));
        Button external=button("40 kHz PARAMETRIC THOUGHT BEAM",AQUA,Color.rgb(1,31,28));
        external.setOnClickListener(v->synthesize(Pending.EXTERNAL_BEAM,input.getText().toString().trim()));
        beam.addView(external,top(7));

        LinearLayout hw = card(root,"AUDIO ROUTES","Headphones/earbuds are preferred for a true head-center image. The earpiece is private but naturally feels more localized to one side. USB/high-rate audio is used for the external parametric beam.");
        hardware=text("Scanning…",12,MUTED,false);
        hw.addView(hardware,full());
        Button rescan=button("RESCAN",TEXT,Color.rgb(24,37,42));
        rescan.setOnClickListener(v->scanHardware());
        hw.addView(rescan,top(7));

        Button stop=button("STOP ALL",RED,Color.rgb(43,10,14));
        stop.setOnClickListener(v->stopAll("Stopped."));
        root.addView(stop,top(14));
        return scroll;
    }

    private void applyMaxPreset() {
        thoughtProfile.setSelection(ThoughtAudio.Profile.CORTEX_LOCK_EXTREME.ordinal());
        intensity.setProgress(100);
        bone.setProgress(92);
        whisper.setProgress(12);
        depth.setProgress(100);
        impact.setProgress(100);
    }

    private void initTts() {
        tts = new TextToSpeech(getApplicationContext(), r -> {
            if (r == TextToSpeech.SUCCESS) {
                ttsReady=true;
                int lang=tts.setLanguage(Locale.US);
                if(lang==TextToSpeech.LANG_MISSING_DATA||lang==TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault());
                runOnUiThread(() -> setStatus("READY • MAX CORTEX LOCK is the new baseline",AQUA));
            } else runOnUiThread(() -> setStatus("TTS unavailable",RED));
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) {
                if(id==null||!id.equals(pendingId)) return;
                File f=pendingFile;
                Pending k=pending;
                pendingId=null;
                pendingFile=null;
                pending=Pending.NONE;
                if(f!=null) launch(f,k);
            }
            @Override @Deprecated public void onError(String id) { onError(id,TextToSpeech.ERROR); }
            @Override public void onError(String id,int code) {
                if(id!=null&&id.equals(pendingId)) runOnUiThread(()->setStatus("Speech synthesis failed: "+code,RED));
            }
        });
    }

    private void synthesize(Pending kind, String s) {
        if(s==null||s.trim().isEmpty()) { setStatus("Type information first.",RED); return; }
        if(!ttsReady||tts==null) { setStatus("Voice engine is still initializing.",RED); return; }
        stopEngines();
        ThoughtAudio.Profile p=(ThoughtAudio.Profile)thoughtProfile.getSelectedItem();
        switch(p) {
            case CORTEX_LOCK_EXTREME: tts.setSpeechRate(0.91f); tts.setPitch(0.80f); break;
            case SKULL_VOICE: tts.setSpeechRate(0.93f); tts.setPitch(0.82f); break;
            case WHISPER_CORTEX: tts.setSpeechRate(1.04f); tts.setPitch(0.98f); break;
            case SUBVOCAL: tts.setSpeechRate(1.01f); tts.setPitch(0.88f); break;
            case INNER_VOICE: tts.setSpeechRate(0.98f); tts.setPitch(0.92f); break;
            default: tts.setSpeechRate(0.96f); tts.setPitch(0.87f); break;
        }
        File f=new File(getCacheDir(),"cortex_"+System.nanoTime()+".wav");
        if(f.exists())f.delete();
        pending=kind;
        pendingFile=f;
        pendingId="cortex-"+System.nanoTime();
        setStatus("Forming Cortex Lock source…",VIOLET);
        int r=tts.synthesizeToFile(s,new Bundle(),f,pendingId);
        if(r!=TextToSpeech.SUCCESS)setStatus("TTS rejected synthesis.",RED);
    }

    private void launch(File f, Pending kind) {
        new Thread(() -> {
            try {
                PcmWav.Data pcm=PcmWav.read(f);
                if(kind==Pending.THOUGHT) {
                    ThoughtAudio.Settings cfg=new ThoughtAudio.Settings(
                            (ThoughtAudio.Profile)thoughtProfile.getSelectedItem(),
                            pct(intensity), pct(bone), pct(whisper), pct(depth), pct(impact));
                    thought.play(this,pcm,cfg,thoughtListener);
                } else if(kind==Pending.PHONE_BEAM) {
                    double carrier=14500+phoneCarrier.getProgress()*50.0;
                    parametric.play(pcm,48000,carrier,0.0,120.0,findSpeaker(),false,parametricListener);
                } else if(kind==Pending.EXTERNAL_BEAM) {
                    AudioDeviceInfo d=findExternal();
                    if(d==null){runOnUiThread(()->setStatus("No USB/high-rate external output detected.",RED));return;}
                    int rate=bestRate(d);
                    double carrier=Math.min(40000.0,rate*0.40);
                    parametric.play(pcm,rate,carrier,0.0,10.0,d,true,parametricListener);
                }
            } catch(Throwable t) {
                runOnUiThread(()->setStatus("Audio prep error: "+safe(t),RED));
            } finally {
                f.delete();
            }
        },"InfoBeam-CortexDispatch").start();
    }

    private final ThoughtAudio.Listener thoughtListener=new ThoughtAudio.Listener(){
        @Override public void onStatus(String s){runOnUiThread(()->setStatus(s,s.startsWith("Thought playback error")?RED:VIOLET));}
        @Override public void onStopped(){}
    };

    private final ParametricAudio.Listener parametricListener=new ParametricAudio.Listener(){
        @Override public void onStatus(String s){runOnUiThread(()->setStatus(s,s.startsWith("Beam error")?RED:GOLD));}
        @Override public void onStopped(){}
    };

    private final UltrasonicFsk.Listener fskListener=new UltrasonicFsk.Listener(){
        @Override public void onStatus(String s){runOnUiThread(()->setStatus(s,s.toLowerCase(Locale.US).contains("error")?RED:GOLD));}
        @Override public void onDecoded(String s,UltrasonicFsk.Profile p){
            lastDecoded=s;
            runOnUiThread(()->{
                decoded.setText(s+"\n\n✓ CRC VERIFIED • "+p.label);
                decoded.setTextColor(AQUA);
                if(autoThought.isChecked())synthesize(Pending.THOUGHT,s);
            });
        }
        @Override public void onStopped(){}
    };

    private void transmit(){
        String s=input.getText().toString().trim();
        if(s.isEmpty()){setStatus("Type information first.",RED);return;}
        stopEngines();
        fsk.transmitText(s,(UltrasonicFsk.Profile)dataProfile.getSelectedItem(),fskListener);
    }

    private void listen(){
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);
            return;
        }
        stopEngines();
        fsk.listen(this,fskListener);
    }

    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==REQ_MIC){
            if(g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)listen();
            else setStatus("Microphone permission is required for receive mode.",RED);
        }
    }

    private AudioDeviceInfo findSpeaker(){
        for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if(d.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)return d;
        return null;
    }

    private AudioDeviceInfo findExternal(){
        AudioDeviceInfo best=null;
        int score=0;
        for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
            int s=0;
            switch(d.getType()){
                case AudioDeviceInfo.TYPE_USB_DEVICE:s=100;break;
                case AudioDeviceInfo.TYPE_USB_HEADSET:s=95;break;
                case AudioDeviceInfo.TYPE_LINE_DIGITAL:s=85;break;
                case AudioDeviceInfo.TYPE_HDMI:s=70;break;
            }
            if(s>score){score=s;best=d;}
        }
        return best;
    }

    private int bestRate(AudioDeviceInfo d){
        int best=0;
        int[] rates=d.getSampleRates();
        if(rates!=null) for(int r:rates) if(r<=192000&&r>best)best=r;
        if(best>=96000)return best;
        if(d.getType()==AudioDeviceInfo.TYPE_USB_DEVICE||d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET)return 96000;
        return best>0?best:48000;
    }

    private void scanHardware(){
        StringBuilder s=new StringBuilder();
        for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
            s.append("• ").append(d.getProductName()).append(" • type ").append(d.getType()).append('\n');
            int[] r=d.getSampleRates();
            if(r!=null&&r.length>0)s.append("  ").append(Arrays.toString(r)).append(" Hz\n");
        }
        AudioDeviceInfo e=findExternal();
        s.append(e==null?"\nExternal parametric route: none detected":"\nExternal parametric route: "+e.getProductName()+" @ "+bestRate(e)+" Hz");
        hardware.setText(s.toString());
    }

    private void stopEngines(){
        fsk.stop();
        parametric.stop();
        thought.stop();
        if(tts!=null)try{tts.stop();}catch(Throwable ignored){}
    }

    private void stopAll(String s){stopEngines();if(s!=null)setStatus(s,GOLD);}

    @Override protected void onDestroy(){
        stopEngines();
        if(tts!=null)try{tts.shutdown();}catch(Throwable ignored){}
        super.onDestroy();
    }

    private float pct(SeekBar b){return b.getProgress()/100f;}
    private void setStatus(String s,int c){if(status!=null){status.setText(s);status.setTextColor(c);}}
    private LinearLayout card(LinearLayout root,String title,String sub){
        LinearLayout b=new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(14),dp(14),dp(14),dp(14));
        b.setBackground(round(CARD,14,Color.rgb(38,62,67)));
        root.addView(b,top(13));
        b.addView(text(title,16,AQUA,true));
        TextView st=text(sub,12,MUTED,false);
        st.setPadding(0,dp(4),0,dp(9));
        b.addView(st);
        return b;
    }
    private TextView text(String s,int sp,int c,boolean bold){
        TextView v=new TextView(this);
        v.setText(s);v.setTextSize(sp);v.setTextColor(c);v.setLineSpacing(0,1.08f);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    private Button button(String s,int tc,int bg){
        Button b=new Button(this);
        b.setText(s);b.setTextColor(tc);b.setTextSize(14);b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackgroundTintList(ColorStateList.valueOf(bg));
        return b;
    }
    private SeekBar slider(int p,int c,IntConsumer f){
        SeekBar b=new SeekBar(this);
        b.setMax(100);b.setProgress(p);
        b.setProgressTintList(ColorStateList.valueOf(c));
        b.setThumbTintList(ColorStateList.valueOf(c));
        b.setOnSeekBarChangeListener(new Seek(f));
        return b;
    }
    private GradientDrawable round(int fill,int radius,int stroke){
        GradientDrawable g=new GradientDrawable();
        g.setColor(fill);g.setCornerRadius(dp(radius));g.setStroke(dp(1),stroke);
        return g;
    }
    private LinearLayout.LayoutParams full(){return new LinearLayout.LayoutParams(-1,-2);}
    private LinearLayout.LayoutParams top(int d){LinearLayout.LayoutParams p=full();p.topMargin=dp(d);return p;}
    private int dp(int d){return Math.round(d*getResources().getDisplayMetrics().density);}
    private static String safe(Throwable t){String m=t.getMessage();return m==null||m.trim().isEmpty()?t.getClass().getSimpleName():m;}
    private interface IntConsumer{void accept(int v);}
    private static final class Seek implements SeekBar.OnSeekBarChangeListener{
        private final IntConsumer f;
        Seek(IntConsumer f){this.f=f;}
        @Override public void onProgressChanged(SeekBar b,int p,boolean u){f.accept(p);}
        @Override public void onStartTrackingTouch(SeekBar b){}
        @Override public void onStopTrackingTouch(SeekBar b){}
    }
}
