#!/usr/bin/env bash
# Downloads the prebuilt sherpa-onnx Android AAR (Kotlin API + native libs for
# all ABIs) used by the on-device Parakeet transcriber. The AAR is gitignored
# because it's ~54 MB; run this once after cloning to enable a local build.
set -euo pipefail

VERSION="1.13.3"
DEST_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/libs"
DEST="$DEST_DIR/sherpa-onnx-${VERSION}.aar"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar"

mkdir -p "$DEST_DIR"
if [ -f "$DEST" ]; then
  echo "Already present: $DEST"
  exit 0
fi

echo "Downloading sherpa-onnx ${VERSION} AAR…"
curl -fL --progress-bar -o "$DEST" "$URL"
echo "Saved to $DEST"
