/*
 * MIT License
 *
 * Copyright (c) 2016-present, CriticalBlue Ltd.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
 * OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package io.approov.reactnative;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import com.criticalblue.approovsdk.Approov;

// interceptor to add Approov tokens or substitute headers and query parameters
public class ApproovInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovService";

    // loopback and emulator aliases for the development machine, forwarded untouched only in
    // debuggable apps: 10.0.2.2 is the Android emulator, 10.0.3.2 is Genymotion
    static final Set<String> DEBUG_DEVELOPMENT_HOSTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("127.0.0.1", "::1", "10.0.2.2", "10.0.3.2")));

    // The service this interceptor works for, held weakly: a client built before a React Native
    // reload (for example a cached or image-loading client) must not keep the old service and
    // its ReactContext alive.
    private volatile WeakReference<ApproovService> serviceRef;

    /**
     * Creates a new ApproovInterceptor for adding Approov protection to requests.
     *
     * @param approovService the Approov service being used
     */
    public ApproovInterceptor(ApproovService approovService) {
        this.serviceRef = new WeakReference<>(approovService);
    }

    /**
     * Returns the service to use for a request: the one this interceptor was created for, or the
     * newest service once that one has been replaced by a React Native reload. A client built
     * before the reload then uses the configuration and pins of the live service.
     *
     * @return the service, or null if there is none
     */
    ApproovService service() {
        ApproovService service = serviceRef.get();
        if ((service == null) || service.isSuperseded()) {
            ApproovService latest = ApproovService.latest();
            if (latest != null) {
                serviceRef = new WeakReference<>(latest);
                service = latest;
            }
        }
        return service;
    }

    /**
     * Returns a diagnostic string describing the state of a header value.
     * Matches the iOS headerStateForValue: format.
     *
     * @param value the header value (may be null)
     * @return "missing", "empty", or "present(len=N)"
     */
    private static String headerState(String value) {
        if (value == null) return "missing";
        if (value.isEmpty()) return "empty";
        return "present(len=" + value.length() + ")";
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // WebSockets are not supported. OkHttp runs application interceptors on the upgrade
        // request (it sets "Upgrade: websocket" before the call starts) but skips network
        // interceptors, so ApproovPinningInterceptor never checks the connection. Forward the
        // upgrade untouched: no token, no secure string substitution and no signature, so
        // nothing Approov issues travels over a connection Approov has not pinned.
        if ("websocket".equalsIgnoreCase(request.header("Upgrade"))) {
            ApproovService.log(ApproovService.LOG_DEBUG, TAG,
                    "WebSocket upgrade forwarded without Approov processing (not supported): " + request.url());
            return chain.proceed(request);
        }

        // proceed with the rest of the chain
        return chain.proceed(protect(request));
    }

    /**
     * Applies Approov processing to a request and records which headers it added or changed, so
     * that ApproovPinningInterceptor can remove them from a redirect to another origin (scheme,
     * host or port). A request built from one Approov already processed for another origin (such
     * as a new call from Response.request()) first has those credentials removed.
     *
     * @param original the request as the app presents it
     * @return the request to send
     * @throws IOException if processing must fail the request
     */
    private Request protect(Request original) throws IOException {
        ApproovIssuedHeaders earlier = original.tag(ApproovIssuedHeaders.class);
        if (earlier != null)
            original = earlier.stripIfOtherOrigin(original);
        return ApproovIssuedHeaders.tag(original, process(original));
    }

    // Adds the Approov token, trace ID, secure string substitutions and any signature to a
    // request, or returns it unchanged if it must not be protected.
    private Request process(Request request) throws IOException {
        ApproovService approovService = service();
        if (approovService == null)
            return request;

        // if there are any accesses to localhost then they are just passed through
        String url = request.url().toString();
        String host = request.url().host();

        if (host.equals("localhost")) {
            if (!approovService.isSuppressLoggingUnknownURL())
                ApproovService.log(ApproovService.LOG_DEBUG, TAG, "localhost forwarded: " + url);
            return request;
        }

        // In a debug build, also forward the other addresses used to reach the development
        // machine, such as Metro on the emulator at 10.0.2.2. They are never Approov-protected
        // and, being cleartext, would otherwise cost a token fetch that returns BAD_URL.
        if (DEBUG_DEVELOPMENT_HOSTS.contains(host) && approovService.isAppDebuggable()) {
            if (!approovService.isSuppressLoggingUnknownURL())
                ApproovService.log(ApproovService.LOG_DEBUG, TAG, "development host forwarded: " + url);
            return request;
        }

        // obtain the mutator to use for this request - this is always non-null
        ApproovServiceMutator mutator = approovService.getServiceMutator();

        // check if we should intercept this request
        try {
            if (!mutator.handleInterceptorShouldProcessRequest(approovService, request))
                return request;
        } catch (ApproovException e) {
            throw new IOException(e);
        }

        // Protected request paths MUST await initialize(). There is no startup grace period.
        if (!approovService.isInitialized()) {
            approovService.logUninitializedForward(TAG, url);
            return request;
        }

        if (!approovService.isApproovEnabled()) {
            // INFO (was DEBUG): bypass mode is security-relevant and should be visible in
            // production logs, matching the iOS layer. Message kept identical across platforms.
            ApproovService.log(ApproovService.LOG_INFO, TAG, "Approov disabled (bypass mode) - forwarding request unprotected: " + url);
            return request;
        }

        // update the data hash based on any token binding header (presence is optional)
        String bindingHeader = approovService.getBindingHeader();
        if ((bindingHeader != null) && !bindingHeader.equals("") && request.headers().names().contains(bindingHeader)) {
            Approov.setDataHashInToken(request.header(bindingHeader));
            ApproovService.log(ApproovService.LOG_DEBUG, TAG, "setting data hash for binding header " + bindingHeader);
        }

        // request an Approov token for the domain and log unless suppressed
        Approov.TokenFetchResult approovResults = approovService.fetchApproovTokenAndWait(url);
        if (!approovService.isSuppressLoggingUnknownURL()
                || (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_URL))
            ApproovService.log(ApproovService.LOG_DEBUG, TAG, "token for " + url + ": " + approovResults.getLoggableToken());

        // Acknowledge configuration changes before reading the latest pins. Refresh only once
        // if both flags are set. The shared network interceptor will check this same request
        // against the new pins, so no client rebuild or forced retry is needed.
        boolean configChanged = approovResults.isConfigChanged();
        if (configChanged) {
            ApproovService.log(ApproovService.LOG_DEBUG, TAG, "dynamic config update received");
            Approov.fetchConfig();
        }
        if (configChanged || approovResults.isForceApplyPins()) {
            ApproovService.log(ApproovService.LOG_DEBUG, TAG, "refreshing shared pins before network verification");
            try {
                approovService.rebuildPins();
            } catch (RuntimeException e) {
                // OkHttp rethrows an unchecked exception from an interceptor on its dispatcher
                // thread, which ends the app. Fail only this request.
                throw new ApproovException("Approov pins could not be refreshed", e);
            }
        }

        // check if the request should proceed based on the token fetch result
        try {
            if (!mutator.handleInterceptorFetchTokenResult(approovService, approovResults, url))
                return request;
        } catch (ApproovException e) {
            throw new IOException(e);
        }

        // capture pre-mutation header state for diagnostics
        String tokenHeaderKey = approovService.getTokenHeader();
        String tokenBefore = request.header(tokenHeaderKey);

        // we successfully obtained a token so add it to the header for the request
        String addedTokenHeader = null;
        String addedTraceIDHeader = null;
        if ((approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS)
                && (approovResults.getToken() != null) && !approovResults.getToken().isEmpty()) {
            addedTokenHeader = approovService.getTokenHeader();
            request = request.newBuilder()
                    .header(addedTokenHeader, approovService.getTokenPrefix() + approovResults.getToken())
                    .build();
        } else if ((approovResults.getToken() == null || approovResults.getToken().isEmpty())
                && approovService.getUseApproovStatusIfNoToken()) {
            addedTokenHeader = approovService.getTokenHeader();
            request = request.newBuilder()
                    .header(addedTokenHeader, approovService.getTokenPrefix() + approovResults.getStatus().toString())
                    .build();
        }

        String traceIDHeader = approovService.getTraceIDHeader();
        String traceID = approovResults.getTraceID();
        if ((traceIDHeader != null) && (traceID != null) && !traceID.isEmpty()) {
            addedTraceIDHeader = traceIDHeader;
            request = request.newBuilder().header(traceIDHeader, traceID).build();
        }

        // log the request mutation result (matches iOS "task mutation" log at INFO level).
        // Routed through the service's level-gated logger so it honours setLogLevel and is
        // suppressed below INFO, matching the iOS ApproovLogI behaviour — rather than
        // writing to android.util.Log unconditionally for every request.
        String tokenAfter = request.header(tokenHeaderKey);
        String traceAfter = (traceIDHeader != null) ? request.header(traceIDHeader) : null;
        ApproovService.log(ApproovService.LOG_INFO, TAG, "request mutation " + url
                + " token=" + headerState(tokenBefore) + "->" + headerState(tokenAfter)
                + " trace=" + headerState(traceAfter));

        // we now deal with any header substitutions
        Map<String, String> subsHeaders = approovService.getSubstitutionHeaders();
        List<String> substitutedHeaders = new ArrayList<>();
        for (Map.Entry<String, String> entry : subsHeaders.entrySet()) {
            String header = entry.getKey();
            String prefix = entry.getValue();
            String value = request.header(header);
            if ((value != null) && value.startsWith(prefix) && (value.length() > prefix.length())) {
                approovResults = Approov.fetchSecureStringAndWait(value.substring(prefix.length()), null);
                ApproovService.log(ApproovService.LOG_DEBUG, TAG, "substituting header: " + header + ", " + approovResults.getStatus().toString());

                // check if the substitution should proceed
                try {
                    if (!mutator.handleInterceptorHeaderSubstitutionResult(approovService, approovResults, header))
                        continue;
                } catch (ApproovException e) {
                    throw new IOException(e);
                }

                // substitute the header
                if (approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS) {
                    request = request.newBuilder().header(header, prefix + approovResults.getSecureString()).build();
                    substitutedHeaders.add(header);
                }
            }
        }

        // we now deal with any query parameter substitutions
        String currentURL = request.url().toString();
        List<String> substitutedQueryParams = new ArrayList<>();
        Map<String, Pattern> queryParams = approovService.getSubstitutionQueryParams();
        for (Map.Entry<String, Pattern> entry : queryParams.entrySet()) {
            String queryKey = entry.getKey();
            Pattern pattern = entry.getValue();
            Matcher matcher = pattern.matcher(currentURL);
            if (matcher.find()) {
                // we have found an occurrence of the query parameter to be replaced so we look
                // up the existing
                // value as a key for a secure string
                // Note: we can only support one occurrence of the query parameter
                String queryValue = matcher.group(1);
                approovResults = Approov.fetchSecureStringAndWait(queryValue, null);
                ApproovService.log(ApproovService.LOG_DEBUG, TAG, "substituting query parameter: " + queryKey + ", " + approovResults.getStatus().toString());

                // check if the substitution should proceed
                try {
                    if (!mutator.handleInterceptorQueryParamSubstitutionResult(approovService, approovResults,
                            queryKey))
                        continue;
                } catch (ApproovException e) {
                    throw new IOException(e);
                }

                // substitute the query parameter
                if (approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS) {
                    currentURL = new StringBuilder(currentURL).replace(matcher.start(1),
                            matcher.end(1), approovResults.getSecureString()).toString();
                    request = request.newBuilder().url(currentURL).build();
                    substitutedQueryParams.add(queryKey);
                }
            }
        }

        // allow the mutator to perform any final modifications to the request,
        // including signing
        try {
            ApproovRequestMutations mutations = new ApproovRequestMutations();
            mutations.setTokenHeaderKey(addedTokenHeader);
            mutations.setTraceIDHeaderKey(addedTraceIDHeader);
            mutations.setSubstitutionHeaderKeys(substitutedHeaders);
            mutations.setSubstitutionQueryParamResults(currentURL, substitutedQueryParams);
            request = mutator.handleInterceptorProcessedRequest(approovService, request, mutations);
        } catch (ApproovException e) {
            throw new IOException(e);
        } catch (IllegalStateException e) {
            // strict signing failures (unsupported algorithm, required body digest) must
            // fail closed as a clean network error rather than an unchecked crash
            throw new IOException(e);
        }

        return request;
    }
}
