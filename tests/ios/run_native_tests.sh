#!/bin/bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# Worker-backed mini-sdk suite: exercises real protected/unprotected request
# flows, pinning, substitution, and service-layer contract tests.
bash "$REPO_ROOT/tests/ios/run_mini_sdk_native_tests.sh"

# Legacy RN-specific regression suite: exercises swizzled NSURLSession task
# creation paths, mock completion handlers, and recursion guards that are
# only reachable through the interceptor's dataTaskWithURL:/dataTaskWithRequest:
# swizzle hooks — infrastructure the mini-sdk suite does not touch.
bash "$REPO_ROOT/tests/ios/run_legacy_native_tests.sh"

bash "$REPO_ROOT/tests/ios/run_message_signing_tests.sh"

# Swift PolicyMutator suite: exercises the per-status proceed/forward/block
# bitmask policy, the sign/unsigned request handling, and the bridge helpers
# (setPolicyMutator / resetToDefault) added for setServiceMutatorType parity.
bash "$REPO_ROOT/tests/ios/run_policy_mutator_tests.sh"

# Real-SDK typecheck: every suite above compiles Swift against ApproovStub.swift,
# so none of them can catch an ApproovTokenFetchStatus case name that does not
# exist in the shipping SDK. This gate compiles the same sources against the real
# Approov module instead. It runs last because it may need to download the SDK.
bash "$REPO_ROOT/tests/ios/run_real_sdk_typecheck.sh"
