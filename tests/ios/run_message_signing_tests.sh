#!/bin/bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TEST_ROOT="$REPO_ROOT/tests/ios/swift"
BUILD_DIR="${TMPDIR:-/tmp}/approov-swift-message-signing-tests"
APPROOV_MODULE_DIR="$BUILD_DIR/Approov"
RAW_SFV_MODULE_DIR="$BUILD_DIR/RawStructuredFieldValues"
BINARY="$BUILD_DIR/ApproovMessageSigningTests"

mkdir -p "$APPROOV_MODULE_DIR"
mkdir -p "$RAW_SFV_MODULE_DIR"

xcrun swiftc \
  -parse-as-library \
  -emit-module \
  -emit-object \
  -module-name Approov \
  "$TEST_ROOT/TestSupport/ApproovStub.swift" \
  -emit-module-path "$APPROOV_MODULE_DIR/Approov.swiftmodule" \
  -o "$APPROOV_MODULE_DIR/ApproovStub.o"

xcrun swiftc \
  -parse-as-library \
  -emit-module \
  -emit-object \
  -module-name RawStructuredFieldValues \
  "$TEST_ROOT/TestSupport/RawStructuredFieldValuesStub.swift" \
  -emit-module-path "$RAW_SFV_MODULE_DIR/RawStructuredFieldValues.swiftmodule" \
  -o "$RAW_SFV_MODULE_DIR/RawStructuredFieldValuesStub.o"

xcrun swiftc \
  -I "$APPROOV_MODULE_DIR" \
  -I "$RAW_SFV_MODULE_DIR" \
  "$APPROOV_MODULE_DIR/ApproovStub.o" \
  "$RAW_SFV_MODULE_DIR/RawStructuredFieldValuesStub.o" \
  "$TEST_ROOT/TestSupport/ApproovServiceStub.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/ApproovRequestMutations.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/ApproovServiceError.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/ApproovServiceMutator.swift" \
  "$REPO_ROOT/ios/ApproovServiceMutatorBridge.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/ApproovDefaultMessageSigning.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/util/http-sfv/SFV.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/util/http-sfv/StringItem.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/util/sig/ComponentProvider.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/util/sig/SignatureBaseBuilder.swift" \
  "$REPO_ROOT/ios/ApproovURLSession/util/sig/SignatureParameters.swift" \
  "$TEST_ROOT/ApproovMessageSigningTests.swift" \
  -o "$BINARY"

"$BINARY"
