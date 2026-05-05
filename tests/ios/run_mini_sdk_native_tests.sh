#!/bin/bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TEST_ROOT="$REPO_ROOT/tests/ios/native-mini-sdk"
MINI_SDK_ROOT="$REPO_ROOT/../core-service-layers-testing/mini-sdk/ios"
BUILD_DIR="${TMPDIR:-/tmp}/approov-native-mini-sdk-tests"
APP_DIR="$BUILD_DIR/ApproovNativeMiniSDKTests.app"
CONTENTS_DIR="$APP_DIR/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"
BINARY="$MACOS_DIR/ApproovNativeMiniSDKTests"

mkdir -p "$MACOS_DIR"

cat > "$CONTENTS_DIR/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key>
  <string>ApproovNativeMiniSDKTests</string>
  <key>CFBundleIdentifier</key>
  <string>io.approov.reactnative.tests.minisdk</string>
  <key>CFBundleName</key>
  <string>ApproovNativeMiniSDKTests</string>
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
  -I"$TEST_ROOT" \
  -I"$MINI_SDK_ROOT/Sources/Approov/include" \
  -I"$REPO_ROOT/tests/ios/native/TestSupport" \
  -I"$MINI_SDK_ROOT/Sources/MiniSDKTestSupport/include" \
  "$TEST_ROOT/ApproovNativeMiniSDKTests.m" \
  "$MINI_SDK_ROOT/Sources/Approov/Approov.m" \
  "$MINI_SDK_ROOT/Sources/MiniSDKTestSupport/MiniSDKTestSupport.m" \
  "$REPO_ROOT/tests/ios/native/TestSupport/ApproovServiceMutatorBridgeStub.m" \
  "$REPO_ROOT/ios/ApproovPinningDelegate.m" \
  "$REPO_ROOT/tests/ios/native/TestSupport/ApproovPropsStub.m" \
  "$REPO_ROOT/ios/ApproovUtils.m" \
  "$REPO_ROOT/ios/ApproovMockURLProtocol.m" \
  "$REPO_ROOT/ios/RSSwizzle.m" \
  "$REPO_ROOT/ios/ApproovRCTInterceptor.m" \
  "$REPO_ROOT/ios/ApproovService.m" \
  -framework Foundation \
  -framework Security \
  -o "$BINARY"

"$BINARY"
