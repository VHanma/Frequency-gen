#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

REPO="VHanma/Frequency-gen"
WORKFLOW="build-lightcode-apk.yml"
BRANCH="${1:-agent/lightcode-jar}"
DEST="$HOME/storage/shared/Download/LightCode-Jar"

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
  echo "No successful APK build was found on branch: $BRANCH"
  exit 1
fi

rm -rf "$DEST/artifact"
mkdir -p "$DEST/artifact"
gh run download "$RUN_ID" \
  --repo "$REPO" \
  --name LightCode-Jar-debug-apk \
  --dir "$DEST/artifact"

APK="$(find "$DEST/artifact" -type f -name '*.apk' | head -n 1)"
if [ -z "$APK" ]; then
  echo "Artifact downloaded, but no APK was found."
  exit 1
fi

cp -f "$APK" "$DEST/LightCode-Jar-debug.apk"
echo "APK ready: $DEST/LightCode-Jar-debug.apk"
am start -a android.intent.action.VIEW \
  -d "file://$DEST/LightCode-Jar-debug.apk" \
  -t application/vnd.android.package-archive 2>/dev/null || true
