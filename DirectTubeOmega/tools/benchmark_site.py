#!/usr/bin/env python3
import json, os, re, secrets
from urllib.parse import urljoin
import requests
from bs4 import BeautifulSoup

VIDEO_ID = os.environ.get("VIDEO_ID", "A7cfV5rwZdk")
BASE = "https://www.youtube-transcript.io/"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:139.0) Gecko/20100101 Firefox/139.0"
s = requests.Session(); s.headers.update({"User-Agent":UA,"Accept-Language":"en-US,en;q=0.9"})

def scripts_from(url):
    r=s.get(url,timeout=30); print("GET",url,r.status_code,"bytes",len(r.content)); r.raise_for_status()
    soup=BeautifulSoup(r.text,"html.parser")
    return r.text,[urljoin(url,x.get("src")) for x in soup.select("script[src]") if x.get("src")]

def fetch_scripts(urls):
    out=[]
    for u in urls:
        try:
            r=s.get(u,timeout=30)
            if r.ok: out.append((u,r.text))
        except Exception as e: print("script error",u,e)
    return out

def firebase_config():
    _,urls=scripts_from(BASE)
    for u,js in fetch_scripts(urls):
        if "apiKey" not in js or "appId" not in js: continue
        api=re.search(r'apiKey\s*:\s*["\']([^"\']+)',js); app=re.search(r'appId\s*:\s*["\']([^"\']+)',js)
        proj=re.search(r'projectId\s*:\s*["\']([^"\']+)',js)
        if api and app:
            print("Firebase config source:",u)
            return {"apiKey":api.group(1),"appId":app.group(1),"projectId":proj.group(1) if proj else ""}
    raise RuntimeError("Firebase config not found")

def anonymous_auth(cfg):
    url="https://identitytoolkit.googleapis.com/v1/accounts:signUp?key="+cfg["apiKey"]
    gmpid=cfg["appId"].split(":",2)[-1] if ":" in cfg["appId"] else cfg["appId"]
    h={"Content-Type":"application/json","X-Client-Version":"Firefox/JsCore/10.14.1/FirebaseCore-web","X-Firebase-gmpid":gmpid}
    r=s.post(url,headers=h,json={"returnSecureToken":True},timeout=30); print("Firebase auth",r.status_code); r.raise_for_status()
    return r.json()["idToken"]

def discover_context():
    page=urljoin(BASE,"videos/"+VIDEO_ID)
    html,urls=scripts_from(page)
    scripts=fetch_scripts(urls)
    # New site: page bundle calls [(0,U.E)().header] with value (0,U.E)().value.
    transcript_bundle=None
    for u,js in scripts:
        if "/api/transcripts/v2" in js or "/api/transcripts" in js:
            transcript_bundle=(u,js); break
    if not transcript_bundle: raise RuntimeError("Transcript bundle not found")
    u,js=transcript_bundle
    print("Transcript bundle:",u)
    i=js.find("/api/transcripts")
    excerpt=js[max(0,i-1800):i+1800]
    print("bundle excerpt:",excerpt)

    # Old literal header form.
    pats=[
      re.compile(r'["\']([^"\']+)["\']\s*:\s*["\']([^"\']+)["\']\s*\}\s*,?\s*body\s*:\s*JSON\.stringify'),
      re.compile(r'header\s*:\s*["\']([^"\']+)["\'][^}]{0,300}value\s*:\s*["\']([^"\']+)["\']')
    ]
    for _,blob in scripts:
        for p in pats:
            m=p.search(blob)
            if m:
                print("Literal context header:",m.group(1))
                return m.group(1),m.group(2)

    # Resolve the imported module used as U.E in the current minified page.
    # Search a wide prefix because webpack imports are declared near the module start.
    prefix=js[:i] if i>=0 else js
    mids=re.findall(r'\bU=t\((\d+)\)',prefix)
    if mids:
        mid=mids[-1]; print("U module id:",mid)
        needles=[mid+":",mid+":(",mid+":function"]
        for su,blob in scripts:
            for needle in needles:
                k=blob.find(needle)
                if k>=0:
                    chunk=blob[max(0,k-500):k+5000]
                    print("U module source:",su)
                    print("U module excerpt:",chunk)
                    # Common return object forms.
                    for p in [
                        re.compile(r'header\s*:\s*["\']([^"\']+)["\'][^}]{0,800}value\s*:\s*["\']([^"\']+)["\']'),
                        re.compile(r'value\s*:\s*["\']([^"\']+)["\'][^}]{0,800}header\s*:\s*["\']([^"\']+)["\']')
                    ]:
                        m=p.search(chunk)
                        if m:
                            if chunk[m.start():m.end()].lstrip().startswith("value"):
                                return m.group(2),m.group(1)
                            return m.group(1),m.group(2)
    # Broad diagnostics: static x-* strings near objects with header/value.
    found=set()
    for su,blob in scripts:
        for m in re.finditer(r'["\'](x-[a-z0-9-]{3,80})["\']',blob,re.I):
            val=m.group(1)
            if val.lower() not in found:
                found.add(val.lower()); print("candidate x-header",val,"from",su)
    raise RuntimeError("Current dynamic transcript context header not decoded")

def call_site(token,ctx):
    name,value=ctx
    h={"Authorization":"Bearer "+token,name:value,"Content-Type":"application/json","Origin":"https://www.youtube-transcript.io","Referer":"https://www.youtube-transcript.io/"}
    body={"ids":[VIDEO_ID],"source":"singleVideoUI"}
    r=s.post(urljoin(BASE,"api/transcripts/v2"),headers=h,json=body,timeout=90)
    print("Site API",r.status_code,"bytes",len(r.content)); print(r.text[:1000] if not r.ok else "")
    r.raise_for_status(); return r.json()

def all_transcripts(obj):
    roots=[]
    if isinstance(obj,list): roots=obj
    elif isinstance(obj,dict):
        if isinstance(obj.get("success"),list): roots += obj.get("success",[])
        if isinstance(obj.get("failed"),list): roots += obj.get("failed",[])
        if not roots: roots=[obj]
    tracks=[]
    for root in roots:
        if isinstance(root,dict):
            for t in root.get("tracks") or []:
                if isinstance(t,dict) and isinstance(t.get("transcript"),list): tracks.append((root,t))
    return tracks

def stats(track):
    segs=track.get("transcript",[]); texts=[str(x.get("text","")).strip() for x in segs if isinstance(x,dict)]
    text=" ".join(x for x in texts if x); words=re.findall(r"\S+",text); starts=[]; ends=[]
    for x in segs:
        if not isinstance(x,dict): continue
        try:
            st=float(x.get("start",0) or 0); du=float(x.get("dur",0) or 0); starts.append(st); ends.append(st+du)
        except: pass
    return {"segments":len(segs),"words":len(words),"chars":len(text),"first":min(starts) if starts else None,"last":max(ends) if ends else None,"text":text}

def main():
    cfg=firebase_config(); token=anonymous_auth(cfg); ctx=discover_context(); print("CTX",ctx[0],"value-length",len(ctx[1]))
    data=call_site(token,ctx); os.makedirs("benchmark-output",exist_ok=True)
    json.dump(data,open("benchmark-output/site.json","w",encoding="utf-8"),ensure_ascii=False,indent=2)
    tracks=all_transcripts(data)
    if not tracks: raise RuntimeError("Site response contained no transcript tracks")
    summary=[]
    for _,t in tracks:
        st=stats(t); label=t.get("language") or t.get("name") or t.get("languageCode") or "unknown"
        row={k:v for k,v in st.items() if k!="text"}; row["label"]=label; summary.append(row)
        print("TRACK",label,row); print("FIRST:",st["text"][:600]); print("LAST:",st["text"][-600:])
    json.dump(summary,open("benchmark-output/site-summary.json","w",encoding="utf-8"),ensure_ascii=False,indent=2)
    print("BENCHMARK_OK",VIDEO_ID)

if __name__=="__main__": main()
