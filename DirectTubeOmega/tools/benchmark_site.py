#!/usr/bin/env python3
import json, os, re, secrets, sys
from urllib.parse import urljoin
import requests
from bs4 import BeautifulSoup

VIDEO_ID = os.environ.get("VIDEO_ID", "A7cfV5rwZdk")
BASE = "https://www.youtube-transcript.io/"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:139.0) Gecko/20100101 Firefox/139.0"

s = requests.Session()
s.headers.update({"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"})

def scripts_from(url):
    r = s.get(url, timeout=30)
    print("GET", url, r.status_code, "bytes", len(r.content))
    r.raise_for_status()
    soup = BeautifulSoup(r.text, "html.parser")
    out = []
    for tag in soup.select("script[src]"):
        src = tag.get("src")
        if src:
            out.append(urljoin(url, src))
    return r.text, out

def fetch_scripts(urls):
    for u in urls:
        try:
            r = s.get(u, timeout=30)
            if r.ok:
                yield u, r.text
        except Exception as e:
            print("script error", u, e)

def firebase_config():
    html, urls = scripts_from(BASE)
    for u, js in fetch_scripts(urls):
        if "apiKey" not in js or "appId" not in js:
            continue
        # Firebase web config is minified in a JS object. Keep this parser deliberately tolerant.
        api = re.search(r'apiKey\s*:\s*["\']([^"\']+)', js)
        app = re.search(r'appId\s*:\s*["\']([^"\']+)', js)
        proj = re.search(r'projectId\s*:\s*["\']([^"\']+)', js)
        if api and app:
            print("Firebase config source:", u)
            return {"apiKey": api.group(1), "appId": app.group(1), "projectId": proj.group(1) if proj else ""}
    raise RuntimeError("Firebase config not found in current site bundles")

def anonymous_auth(cfg):
    url = "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + cfg["apiKey"]
    gmpid = cfg["appId"].split(":", 2)[-1] if ":" in cfg["appId"] else cfg["appId"]
    headers = {
        "Content-Type": "application/json",
        "X-Client-Version": "Firefox/JsCore/10.14.1/FirebaseCore-web",
        "X-Firebase-Client": json.dumps({"version":2,"heartbeats":[{"agent":"fire-core/0.10.13 fire-core-esm2017/0.10.13 fire-js/ fire-js-all-app/10.14.1 fire-auth/1.7.9 fire-auth-esm2017/1.7.9","dates":[]}]}),
        "X-Firebase-gmpid": gmpid,
    }
    r = s.post(url, headers=headers, json={"returnSecureToken": True}, timeout=30)
    print("Firebase auth", r.status_code)
    r.raise_for_status()
    j = r.json()
    if not j.get("idToken"):
        raise RuntimeError("Firebase returned no idToken")
    return j["idToken"]

def discover_context():
    page = urljoin(BASE, "videos/" + VIDEO_ID)
    html, urls = scripts_from(page)
    patterns = [
        re.compile(r'["\']([^"\']+)["\']\s*:\s*["\']([^"\']+)["\']\s*\}\s*,?\s*body\s*:\s*JSON\.stringify\(\{ids:\[t\]\}\)'),
        re.compile(r'["\']([^"\']+)["\']\s*:\s*["\']([^"\']+)["\'][^\n]{0,500}/api/transcripts'),
    ]
    for u, js in fetch_scripts(urls):
        if "/api/transcripts" not in js:
            continue
        for p in patterns:
            m = p.search(js)
            if m:
                print("Context header source:", u)
                print("Context header name:", m.group(1))
                return m.group(1), m.group(2)
        # Diagnostic excerpt so future changes are easy to repair without dumping the whole bundle.
        i = js.find("/api/transcripts")
        print("Transcript bundle:", u)
        print("bundle excerpt:", js[max(0,i-1200):i+1200])
    raise RuntimeError("Dynamic transcript context header not found")

def call_site(token, context_header):
    name, value = context_header
    headers = {
        "Authorization": "Bearer " + token,
        name: value,
        "X-Hash": secrets.token_hex(32),
        "Content-Type": "application/json",
        "Origin": "https://www.youtube-transcript.io",
        "Referer": "https://www.youtube-transcript.io/",
    }
    r = s.post(urljoin(BASE, "api/transcripts"), headers=headers, json={"ids":[VIDEO_ID]}, timeout=60)
    print("Site API", r.status_code, "bytes", len(r.content))
    if not r.ok:
        print(r.text[:3000])
    r.raise_for_status()
    return r.json()

def all_transcripts(obj):
    roots = obj if isinstance(obj, list) else [obj]
    tracks = []
    for root in roots:
        if not isinstance(root, dict):
            continue
        ts = root.get("tracks") or []
        if isinstance(ts, list):
            for t in ts:
                if isinstance(t, dict) and isinstance(t.get("transcript"), list):
                    tracks.append((root, t))
    return tracks

def stats(track):
    segs = track.get("transcript", [])
    texts = [str(x.get("text", "")).strip() for x in segs if isinstance(x, dict)]
    text = " ".join(x for x in texts if x)
    words = re.findall(r"\S+", text)
    starts = []
    ends = []
    for x in segs:
        if not isinstance(x, dict):
            continue
        try:
            st = float(x.get("start", 0) or 0)
            du = float(x.get("dur", 0) or 0)
            starts.append(st); ends.append(st+du)
        except Exception:
            pass
    return {"segments":len(segs), "words":len(words), "chars":len(text), "first":min(starts) if starts else None, "last":max(ends) if ends else None, "text":text}

def main():
    cfg = firebase_config()
    token = anonymous_auth(cfg)
    ctx = discover_context()
    data = call_site(token, ctx)
    os.makedirs("benchmark-output", exist_ok=True)
    with open("benchmark-output/site.json", "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    tracks = all_transcripts(data)
    if not tracks:
        raise RuntimeError("Site response contained no transcript tracks")
    summary = []
    for _, t in tracks:
        st = stats(t)
        label = t.get("language") or t.get("name") or t.get("languageCode") or "unknown"
        summary.append({k:v for k,v in st.items() if k != "text"} | {"label":label})
        print("TRACK", label, {k:v for k,v in st.items() if k != "text"})
        print("FIRST:", st["text"][:500])
        print("LAST:", st["text"][-500:])
    with open("benchmark-output/site-summary.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    print("BENCHMARK_OK", VIDEO_ID)

if __name__ == "__main__":
    main()
