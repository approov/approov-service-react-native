#!/bin/bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TEST_ROOT="$REPO_ROOT/tests/ios/native"
BUILD_DIR="${TMPDIR:-/tmp}/approov-native-tests"
APP_DIR="$BUILD_DIR/ApproovNativeTests.app"
CONTENTS_DIR="$APP_DIR/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"
BINARY="$MACOS_DIR/ApproovNativeTests"

mkdir -p "$MACOS_DIR"

cat > "$CONTENTS_DIR/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key>
  <string>ApproovNativeTests</string>
  <key>CFBundleIdentifier</key>
  <string>io.approov.reactnative.tests</string>
  <key>CFBundleName</key>
  <string>ApproovNativeTests</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>1.0</string>
  <key>CFBundleVersion</key>
  <string>1</string>
</dict>
</plist>
EOF

xcrun clang \
  -fobjc-arc \
  -fblocks \
  -fmodules \
  -DDEBUG=1 \
  -I"$REPO_ROOT" \
  -I"$REPO_ROOT/ios" \
  -I"$TEST_ROOT/TestSupport" \
  "$TEST_ROOT/ApproovNativeTests.m" \
  "$TEST_ROOT/TestSupport/Approov/Approov.m" \
  "$TEST_ROOT/TestSupport/ApproovServiceMutatorBridgeStub.m" \
  "$TEST_ROOT/TestSupport/ApproovPinningDelegateStub.m" \
  "$TEST_ROOT/TestSupport/ApproovPropsStub.m" \
  "$REPO_ROOT/ios/ApproovUtils.m" \
  "$REPO_ROOT/ios/ApproovMockURLProtocol.m" \
  "$REPO_ROOT/ios/RSSwizzle.m" \
  "$REPO_ROOT/ios/ApproovRCTInterceptor.m" \
  "$REPO_ROOT/ios/ApproovService.m" \
  -framework Foundation \
  -framework Security \
  -o "$BINARY"

"$BINARY"

bash "$REPO_ROOT/tests/ios/run_message_signing_tests.sh"
