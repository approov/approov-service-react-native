#!/bin/bash

# Typechecks the iOS Swift sources against the REAL Approov SDK module.
#
# Why this exists: every other iOS Swift suite compiles against
# tests/ios/swift/TestSupport/ApproovStub.swift, a hand-written stand-in for the
# Approov module. A stub cannot catch a wrong-but-plausible spelling of an
# ApproovTokenFetchStatus case — PolicyMutator.bitFor/decideFor both end in
# `default:`, so a case name that does not exist in the real SDK would compile
# against the stub and then silently degrade to "no bit -> BLOCK" at runtime.
# Swift's ObjC importer renaming (ApproovTokenFetchStatusMITMDetected ->
# .mitmDetected, ...BadURL -> .badURL) is exactly the kind of thing that is easy
# to get wrong and impossible for the stub to verify.
#
# This script resolves the real Approov.xcframework and runs `swiftc -typecheck`
# against it. It compiles nothing else and produces no binary — it is a fast
# correctness gate, not a test run.
#
# SDK resolution order:
#   1. $APPROOV_XCFRAMEWORK  — explicit path to an Approov.xcframework
#   2. the local CocoaPods cache
#   3. download the public release zip (cached under the build dir)
#
# Exit codes: 0 typecheck clean, 1 typecheck failed or SDK unavailable.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD_DIR="${TMPDIR:-/tmp}/approov-real-sdk-typecheck"
STUB_ROOT="$REPO_ROOT/tests/ios/swift/TestSupport"

# Keep in sync with the `approov-ios-sdk` dependency in
# approov-service-react-native.podspec.
SDK_VERSION="${APPROOV_SDK_VERSION:-3.5.3}"
SDK_URL="https://github.com/approov/approov-ios-sdk/releases/download/${SDK_VERSION}/Approov.xcframework.zip"

mkdir -p "$BUILD_DIR"

resolve_xcframework() {
  # 1. Explicit override.
  if [[ -n "${APPROOV_XCFRAMEWORK:-}" ]]; then
    if [[ -d "$APPROOV_XCFRAMEWORK" ]]; then
      echo "$APPROOV_XCFRAMEWORK"
      return 0
    fi
    echo "APPROOV_XCFRAMEWORK is set but not a directory: $APPROOV_XCFRAMEWORK" >&2
    return 1
  fi

  # 2. CocoaPods cache (any build of the pinned version).
  local cached
  cached="$(find "$HOME/Library/Caches/CocoaPods/Pods/Release/approov-ios-sdk" \
    -maxdepth 2 -type d -name 'Approov.xcframework' 2>/dev/null \
    | grep "/${SDK_VERSION}" | head -1 || true)"
  if [[ -n "$cached" ]]; then
    echo "$cached"
    return 0
  fi

  # 3. Download the public release.
  local downloaded="$BUILD_DIR/Approov.xcframework"
  if [[ -d "$downloaded" ]]; then
    echo "$downloaded"
    return 0
  fi
  echo "==> Downloading Approov SDK ${SDK_VERSION}..." >&2
  if ! curl -sSL --fail --max-time 300 "$SDK_URL" -o "$BUILD_DIR/Approov.xcframework.zip"; then
    echo "Failed to download $SDK_URL" >&2
    return 1
  fi
  unzip -q -o "$BUILD_DIR/Approov.xcframework.zip" -d "$BUILD_DIR"
  if [[ ! -d "$downloaded" ]]; then
    echo "Downloaded archive did not contain Approov.xcframework" >&2
    return 1
  fi
  echo "$downloaded"
}

XCFRAMEWORK="$(resolve_xcframework)" || {
  echo "ERROR: could not resolve a real Approov.xcframework." >&2
  echo "       Set APPROOV_XCFRAMEWORK=/path/to/Approov.xcframework to override." >&2
  exit 1
}

# Pick the simulator slice — it carries the same headers as the device slice and
# needs no code signing.
SLICE="$(find "$XCFRAMEWORK" -maxdepth 1 -type d -name 'ios-*-simulator' | head -1)"
if [[ -z "$SLICE" ]]; then
  echo "ERROR: no iOS simulator slice found in $XCFRAMEWORK" >&2
  exit 1
fi

echo "==> Typechecking against real Approov SDK"
echo "    xcframework: $XCFRAMEWORK"
echo "    slice:       $(basename "$SLICE")"

# RawStructuredFieldValues is a genuine third-party dependency of the message
# signing code, not a stand-in for Approov. It stays stubbed: the point of this
# script is to check the Approov module surface specifically.
RAW_SFV_MODULE_DIR="$BUILD_DIR/RawStructuredFieldValues"
mkdir -p "$RAW_SFV_MODULE_DIR"

SDK_PATH="$(xcrun --sdk iphonesimulator --show-sdk-path)"
TARGET="arm64-apple-ios13.0-simulator"

xcrun swiftc \
  -parse-as-library \
  -emit-module \
  -emit-object \
  -module-name RawStructuredFieldValues \
  -target "$TARGET" \
  -sdk "$SDK_PATH" \
  "$STUB_ROOT/RawStructuredFieldValuesStub.swift" \
  -emit-module-path "$RAW_SFV_MODULE_DIR/RawStructuredFieldValues.swiftmodule" \
  -o "$RAW_SFV_MODULE_DIR/RawStructuredFieldValuesStub.o"

# Note: ApproovStub.swift is deliberately NOT compiled here — `import Approov`
# resolves to the real framework module via -F.
xcrun swiftc -typecheck \
  -target "$TARGET" \
  -sdk "$SDK_PATH" \
  -F "$SLICE" \
  -I "$RAW_SFV_MODULE_DIR" \
  "$STUB_ROOT/ApproovServiceStub.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/ApproovRequestMutations.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/ApproovServiceError.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/ApproovServiceMutator.swift" \
  "$REPO_ROOT/ios/ApproovServiceMutatorBridge.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/ApproovDefaultMessageSigning.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/PolicyMutator.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/util/http-sfv/SFV.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/util/http-sfv/StringItem.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/util/sig/ComponentProvider.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/util/sig/SignatureBaseBuilder.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/util/sig/SignatureParameters.swift"

echo "iOS Swift sources typecheck cleanly against the real Approov SDK"
