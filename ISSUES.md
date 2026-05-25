# Known Issues

### Token binding may retain stale SDK state when the binding header is missing

**Severity:** P2

`TESTING_REQUIREMENTS.md` requires:
- if the configured binding header is present, pass its value to `Approov.setDataHashInToken` (or equivalent method).
- if it is present but empty, pass the empty value.
- if it is absent, no data hash should be set and no `pay` claim should be present.

Currently, if the token binding header is configured but physically missing from an incoming request, the service layer skips calling `Approov.setDataHashInToken` entirely. 

Because the native Approov SDK is stateful, skipping this call means the SDK silently retains the data hash from the *previous* request. This causes the service layer to mistakenly generate an Approov token that contains a stale `pay` claim for the new request, violating the testing requirement that no `pay` claim should be present.

**Note:** This issue was recently identified and fixed in the `approov-service-httpsurlconn` repository. The fix involves explicitly passing `null` / `nil` to the SDK method when the header is absent, which forces the SDK to securely clear its stale state.
