package com.vaan.infobeam;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fully unified high-rate output. Every requested carrier, ELF component, and
 * voice modulation is summed into one sample-synchronous signal and mirrored
 * identically to both output channels. Nothing is split between channels.
 */
public final class UnifiedHypersonicEngine {
    public interface Listener { void onStatus(String text); void onStopped(); }

    public static final class SingleConfig {
        public final double carrierHz, elfHz, modulationDepth, beamGain, elfGain;
        public final int requestedRate;
        public final AudioDeviceInfo preferredDevice;
        public SingleConfig(double carrierHz,double elfHz,double modulationDepth,double beamGain,double elfGain,int requestedRate,AudioDeviceInfo preferredDevice){
            this.carrierHz=carrierHz; this.elfHz=elfHz;
            this.modulationDepth=clamp(modulationDepth,0.0,0.98);
            this.beamGain=clamp(beamGain,0.0,0.42);
            this.elfGain=clamp(elfGain,0.0,0.28);
            this.requestedRate=requestedRate; this.preferredDevice=preferredDevice;
        }
    }

    public static final class DualConfig {
        public final double carrierAHz,carrierBHz,elfAHz,elfBHz,modulationDepth,beamGain,elfGain;
        public final int requestedRate;
        public final AudioDeviceInfo preferredDevice;
        public DualConfig(double carrierAHz,double carrierBHz,double elfAHz,double elfBHz,double modulationDepth,double beamGain,double elfGain,int requestedRate,AudioDeviceInfo preferredDevice){
            this.carrierAHz=carrierAHz; this.carrierBHz=carrierBHz; this.elfAHz=elfAHz; this.elfBHz=elfBHz;
            this.modulationDepth=clamp(modulationDepth,0.0,0.98);
            this.beamGain=clamp(beamGain,0.0,0.30);
            this.elfGain=clamp(elfGain,0.0,0.18);
            this.requestedRate=requestedRate; this.preferredDevice=preferredDevice;
        }
    }

    private final AtomicBoolean stopped=new AtomicBoolean(true);
    private volatile AudioTrack active;

    public void stop(){
        stopped.set(true); AudioTrack t=active;
        if(t!=null){try{t.pause();}catch(Throwable ignored){} try{t.flush();}catch(Throwable ignored){} try{t.stop();}catch(Throwable ignored){} try{t.release();}catch(Throwable ignored){}}
        active=null;
    }

    public void playSingle(PcmWav.Data source,SingleConfig cfg,Listener listener){stop();stopped.set(false);new Thread(()->runSingle(source,cfg,listener),"InfoBeam-Unified-Single").start();}
    public void playDual(PcmWav.Data source,DualConfig cfg,Listener listener){stop();stopped.set(false);new Thread(()->runDual(source,cfg,listener),"InfoBeam-Unified-Dual").start();}

    private void runSingle(PcmWav.Data source,SingleConfig cfg,Listener listener){
        AudioTrack track=null;
        try{
            TrackChoice ch=createTrack(cfg.requestedRate,cfg.preferredDevice); track=ch.track; active=track;
            validateCarrier(cfg.carrierHz,ch.rate); validateElf(cfg.elfHz);
            double srcStep=source.sampleRate/(double)ch.rate;
            long total=Math.max(1L,(long)Math.ceil(source.samples.length/srcStep));
            int fade=Math.max(1,(int)(ch.rate*0.025)); short[] block=new short[4096];
            OnePoleHighPass hp=new OnePoleHighPass(ch.rate,90.0);
            OnePoleLowPass lp=new OnePoleLowPass(ch.rate,Math.min(6500.0,cfg.carrierHz*0.24));
            double pos=0.0,pc=0.0,pe=0.0; long frame=0;
            track.play();
            listener.onStatus("ALL TOGETHER • voice + "+Math.round(cfg.carrierHz)+" Hz + "+fmt(cfg.elfHz)+" Hz • "+ch.rate+" Hz clock");
            while(frame<total&&!stopped.get()){
                int frames=(int)Math.min(2048,total-frame);
                for(int i=0;i<frames;i++){
                    long n=frame+i; float raw=interp(source.samples,pos); pos+=srcStep;
                    double voice=Math.tanh(lp.process(hp.process(raw))*2.2);
                    double env=Math.sqrt(clamp(1.0+cfg.modulationDepth*voice,0.025,1.975));
                    double carrier=Math.sin(pc)*env*cfg.beamGain;
                    double elf=Math.sin(pe)*cfg.elfGain;
                    double f=Math.max(0.0,Math.min(Math.min(1.0,n/(double)fade),Math.min(1.0,(total-1-n)/(double)fade)));
                    double mix=(carrier+elf)*0.90*f;
                    short q=toShort(mix); block[i*2]=q; block[i*2+1]=q;
                    pc=wrap(pc+2*Math.PI*cfg.carrierHz/ch.rate); pe=wrap(pe+2*Math.PI*cfg.elfHz/ch.rate);
                }
                write(track,block,frames*2); frame+=frames;
            }
            if(!stopped.get())listener.onStatus("Unified hypersonic + ELF complete.");
        }catch(Throwable t){listener.onStatus("Unified engine error: "+safe(t));}finally{release(track,listener);}
    }

    private void runDual(PcmWav.Data source,DualConfig cfg,Listener listener){
        AudioTrack track=null;
        try{
            TrackChoice ch=createTrack(cfg.requestedRate,cfg.preferredDevice); track=ch.track; active=track;
            validateCarrier(cfg.carrierAHz,ch.rate); validateCarrier(cfg.carrierBHz,ch.rate); validateElf(cfg.elfAHz); validateElf(cfg.elfBHz);
            double srcStep=source.sampleRate/(double)ch.rate;
            long total=Math.max(1L,(long)Math.ceil(source.samples.length/srcStep));
            int fade=Math.max(1,(int)(ch.rate*0.025)); short[] block=new short[4096];
            OnePoleHighPass hp=new OnePoleHighPass(ch.rate,90.0);
            OnePoleLowPass lp=new OnePoleLowPass(ch.rate,Math.min(6000.0,Math.min(cfg.carrierAHz,cfg.carrierBHz)*0.22));
            double pos=0,pa=0,pb=0,pea=0,peb=0; long frame=0;
            track.play();
            listener.onStatus("ALL 5 TOGETHER • TTS + "+Math.round(cfg.carrierAHz)+" + "+Math.round(cfg.carrierBHz)+" Hz + "+fmt(cfg.elfAHz)+" + "+fmt(cfg.elfBHz)+" Hz");
            while(frame<total&&!stopped.get()){
                int frames=(int)Math.min(2048,total-frame);
                for(int i=0;i<frames;i++){
                    long n=frame+i; float raw=interp(source.samples,pos); pos+=srcStep;
                    double voice=Math.tanh(lp.process(hp.process(raw))*2.2);
                    double env=Math.sqrt(clamp(1.0+cfg.modulationDepth*voice,0.025,1.975));
                    double cA=Math.sin(pa)*env*cfg.beamGain;
                    double cB=Math.sin(pb)*env*cfg.beamGain;
                    double eA=Math.sin(pea)*cfg.elfGain;
                    double eB=Math.sin(peb)*cfg.elfGain;
                    double f=Math.max(0.0,Math.min(Math.min(1.0,n/(double)fade),Math.min(1.0,(total-1-n)/(double)fade)));
                    double mix=(cA+cB+eA+eB)*0.72*f;
                    short q=toShort(mix); block[i*2]=q; block[i*2+1]=q;
                    pa=wrap(pa+2*Math.PI*cfg.carrierAHz/ch.rate); pb=wrap(pb+2*Math.PI*cfg.carrierBHz/ch.rate);
                    pea=wrap(pea+2*Math.PI*cfg.elfAHz/ch.rate); peb=wrap(peb+2*Math.PI*cfg.elfBHz/ch.rate);
                }
                write(track,block,frames*2); frame+=frames;
            }
            if(!stopped.get())listener.onStatus("Unified dual matrix complete.");
        }catch(Throwable t){listener.onStatus("Unified dual error: "+safe(t));}finally{release(track,listener);}
    }

    private static TrackChoice createTrack(int requestedRate,AudioDeviceInfo device){
        int[] rates=unique(requestedRate,192000,176400,96000,88200,48000); Throwable last=null;
        for(int rate:rates){if(rate<48000||rate>192000)continue;try{
            int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT); if(min<=0)continue;
            AudioTrack t=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(min*3,65536)).setTransferMode(AudioTrack.MODE_STREAM).build();
            if(device!=null)try{t.setPreferredDevice(device);}catch(Throwable ignored){} if(t.getState()==AudioTrack.STATE_INITIALIZED)return new TrackChoice(t,rate); t.release();
        }catch(Throwable t){last=t;}}
        throw new IllegalStateException("No usable 48–192 kHz stereo output route.",last);
    }
    private static void validateCarrier(double hz,int rate){if(!Double.isFinite(hz)||hz<20000)throw new IllegalArgumentException("Carrier must be at least 20 kHz.");double max=rate*0.44;if(hz>max)throw new IllegalArgumentException("Carrier "+Math.round(hz)+" Hz needs a higher rate. Safe digital limit at this rate is "+Math.round(max)+" Hz.");}
    private static void validateElf(double hz){if(!Double.isFinite(hz)||hz<=0||hz>100)throw new IllegalArgumentException("ELF must be >0 and <=100 Hz.");}
    private static void write(AudioTrack t,short[] d,int count){int off=0;while(off<count){int n=t.write(d,off,count-off,AudioTrack.WRITE_BLOCKING);if(n<0)throw new IllegalStateException("AudioTrack write failed: "+n);off+=n;}}
    private void release(AudioTrack t,Listener l){if(t!=null){try{t.stop();}catch(Throwable ignored){}try{t.release();}catch(Throwable ignored){}}active=null;stopped.set(true);l.onStopped();}
    private static float interp(float[] d,double p){if(d==null||d.length==0)return 0;int i=(int)p;if(i<=0)return d[0];if(i>=d.length-1)return d[d.length-1];double f=p-i;return(float)(d[i]+(d[i+1]-d[i])*f);}
    private static short toShort(double x){return(short)Math.round(clamp(x,-0.965,0.965)*32767.0);}
    private static double wrap(double p){while(p>=Math.PI*2)p-=Math.PI*2;return p;}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    private static String fmt(double v){return String.format(Locale.US,"%.3f",v);}
    private static String safe(Throwable t){String m=t.getMessage();return m==null||m.trim().isEmpty()?t.getClass().getSimpleName():m;}
    private static int[] unique(int...v){int[]o=new int[v.length];int n=0;for(int x:v){boolean s=false;for(int i=0;i<n;i++)if(o[i]==x){s=true;break;}if(!s)o[n++]=x;}int[]r=new int[n];System.arraycopy(o,0,r,0,n);return r;}
    private static final class TrackChoice{final AudioTrack track;final int rate;TrackChoice(AudioTrack t,int r){track=t;rate=r;}}
    private static final class OnePoleLowPass{private final double a;private float y;OnePoleLowPass(int r,double hz){double dt=1.0/r,rc=1.0/(2*Math.PI*hz);a=dt/(rc+dt);}float process(float x){y+=(float)(a*(x-y));return y;}}
    private static final class OnePoleHighPass{private final double a;private float y,last;OnePoleHighPass(int r,double hz){double dt=1.0/r,rc=1.0/(2*Math.PI*hz);a=rc/(rc+dt);}float process(float x){y=(float)(a*(y+x-last));last=x;return y;}}
}
