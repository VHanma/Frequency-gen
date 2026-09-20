package com.vaan.frequencyscope;
final class FFT {
  static void run(double[] r,double[] im){int n=r.length,j=0;for(int i=1;i<n;i++){int b=n>>1;while((j&b)!=0){j^=b;b>>=1;}j^=b;if(i<j){double t=r[i];r[i]=r[j];r[j]=t;t=im[i];im[i]=im[j];im[j]=t;}}
    for(int l=2;l<=n;l<<=1){double a=-2*Math.PI/l,lr=Math.cos(a),li=Math.sin(a);for(int i=0;i<n;i+=l){double wr=1,wi=0;for(int k=0;k<l/2;k++){int e=i+k,o=e+l/2;double or=r[o]*wr-im[o]*wi,oi=r[o]*wi+im[o]*wr,er=r[e],ei=im[e];r[e]=er+or;im[e]=ei+oi;r[o]=er-or;im[o]=ei-oi;double nw=wr*lr-wi*li;wi=wr*li+wi*lr;wr=nw;}}}}
}
