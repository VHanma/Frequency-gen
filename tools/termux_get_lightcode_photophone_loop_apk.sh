#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

REPO="VHanma/Frequency-gen"
WORKFLOW="build-lightcode-photophone-loop-apk.yml"
BRANCH="agent/lightcode-photophone-loop"
DEST="$HOME/storage/shared/Download/LightCode-Photophone-Loop"

pkg update -y
pkg install -y gh git unzip
termux-setup-storage || true

if ! gh auth status >/dev/null 2>&1; then
  echo "GitHub login is required once. Follow the code shown next."
  gh auth login --web --git-protocol https
fi

mkdir -p "$DEST"
RUN_ID="$(gh run list \
  --repo "$REPO" \
  --workflow "$WORKFLOW" \
  --branch "$BRANCH" \
  --status success \
  --limit 1 \
  --json databaseId \
  --jq '.[0].databaseId')"

if [ -z "$RUN_ID" ] || [ "$RUN_ID" = "null" ]; then
  echo "No successful Photophone Loop APK build was found."
  exit 1
fi

rm -rf "$DEST/artifact"
mkdir -p "$DEST/artifact"
gh run download "$RUN_ID" \
  --repo "$REPO" \
  --name LightCode-Photophone-Loop-debug-apk \
  --dir "$DEST/artifact"

APK="$(find "$DEST/artifact" -type f -name '*.apk' | head -n 1)"
if [ -z "$APK" ]; then
  echo "Artifact downloaded, but no APK was found."
  exit 1
fi

cp -f "$APK" "$DEST/LightCode-Photophone-Loop-v1.1-debug.apk"
echo "APK ready: $DEST/LightCode-Photophone-Loop-v1.1-debug.apk"
am start -a android.intent.action.VIEW \
  -d "file://$DEST/LightCode-Photophone-Loop-v1.1-debug.apk" \
  -t application/vnd.android.package-archive 2>/dev/null || true
