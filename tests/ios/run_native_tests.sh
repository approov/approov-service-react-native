#!/bin/bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

# Prefer the worker-backed mini-sdk suite for the default iOS path. It exercises
# the real protected/unprotected request flow and is the most stable CI signal.
bash "$REPO_ROOT/tests/ios/run_mini_sdk_native_tests.sh"

bash "$REPO_ROOT/tests/ios/run_message_signing_tests.sh"
