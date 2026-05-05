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
