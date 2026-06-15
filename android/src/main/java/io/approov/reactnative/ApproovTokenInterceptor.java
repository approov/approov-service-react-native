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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import android.content.Context;
import android.util.Log;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import com.criticalblue.approovsdk.Approov;

// interceptor to add Approov tokens or substitute headers and query parameters. This is an
// application interceptor; certificate pinning is handled separately by ApproovPinningInterceptor,
// which runs as a network interceptor.
public class ApproovTokenInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovService";

    // service wrapping Approov SDK
    private ApproovService approovService;

    /**
     * Creates a new ApproovTokenInterceptor for adding Approov protection to requests.
     *
     * @param approovService the Approov service being used
     */
    public ApproovTokenInterceptor(ApproovService approovService) {
        this.approovService = approovService;
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
        // if there are any accesses to localhost then they are just passed through
        Request request = chain.request();
        String url = request.url().toString();
        String host = request.url().host();
        if (host.equals("localhost")) {
            if (!approovService.isSuppressLoggingUnknownURL())
                Log.d(TAG, "localhost forwarded: " + url);
            return chain.proceed(request);
        }

        // obtain the mutator to use for this request - this is always non-null
        ApproovServiceMutator mutator = approovService.getServiceMutator();

        // check if we should intercept this request
        try {
            if (!mutator.handleInterceptorShouldProcessRequest(approovService, request))
                return chain.proceed(request);
        } catch (ApproovException e) {
            throw new IOException(e);
        }

        // if Approov is not initialized then perform any necessary synchronization to
        // avoid a race on any
        // initial network fetches
        if (!approovService.isInitialized()) {
            // mark the earliest network fetch request time prior to initialization - it is
            // only updated
            // if it is currently unset
            approovService.setEarliestNetworkRequestTime();

            // wait until any initial fetch time is reached
            boolean waitForReady = true;
            boolean hasLoggedInitWarning = false;
            while (waitForReady) {
                // if initialization has completed then we can proceed
                if (approovService.isInitialized()) {
                    waitForReady = false;
                    break;
                }
                
                long currentTime = System.currentTimeMillis();
                long earliestTime = approovService.getEarliestNetworkRequestTime();
                if (currentTime >= earliestTime)
                    waitForReady = false;
                else {
                    if (!hasLoggedInitWarning) {
                        Log.e(TAG, "Approov initialization is delaying a network request! A native thread is sleeping. You MUST await ApproovService.initialize() or use the useApproov() hook before calling fetch().");
                        hasLoggedInitWarning = true;
                    }
                    // sleep for a short period to block this request thread
                    Log.d(TAG, "request paused: " + url);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Log.d(TAG, "request pause interrupted: " + url);
                    }
                }
            }

            // if Approov is still not initialized then forward the request unchanged
            if (!approovService.isInitialized()) {
                Log.d(TAG, "uninitialized forwarded: " + url);
                return chain.proceed(request);
            }
        }

        if (!approovService.isApproovEnabled()) {
            Log.d(TAG, "approov disabled, forwarded: " + url);
            return chain.proceed(request);
        }

        // update the data hash based on any token binding header (presence is optional)
        String bindingHeader = approovService.getBindingHeader();
        if ((bindingHeader != null) && !bindingHeader.equals("") && request.headers().names().contains(bindingHeader)) {
            Approov.setDataHashInToken(request.header(bindingHeader));
            Log.d(TAG, "setting data hash for binding header " + bindingHeader);
        }

        // request an Approov token for the domain and log unless suppressed
        Approov.TokenFetchResult approovResults = approovService.fetchApproovTokenAndWait(url);
        if (!approovService.isSuppressLoggingUnknownURL()
                || (approovResults.getStatus() != Approov.TokenFetchStatus.UNKNOWN_URL))
            Log.d(TAG, "token for " + url + ": " + approovResults.getLoggableToken());

        // force a pinning change if there is any dynamic config update, calling
        // fetchConfig to
        // clear the update flag
        if (approovResults.isConfigChanged()) {
            Log.d(TAG, "dynamic config update received");
            Approov.fetchConfig();
            approovService.rebuildPins();
        }

        // we cannot proceed if the pins need to be updated. We notify any certificate
        // pinners that
        // they need to update. This might occur on first use after initial app install
        // if the
        // initial network fetch was unable to obtain the dynamic configuration for the
        // account if
        // there was poor network connectivity at that point.
        if (approovResults.isForceApplyPins()) {
            Log.d(TAG, "force apply pins asserted so aborting request");
            approovService.rebuildPins();
            throw new IOException("Approov pins need to be updated");
        }

        // check if the request should proceed based on the token fetch result
        try {
            if (!mutator.handleInterceptorFetchTokenResult(approovService, approovResults, url))
                return chain.proceed(request);
        } catch (ApproovException e) {
            throw new IOException(e);
        }

        // capture pre-mutation header state for diagnostics
        String tokenHeaderKey = approovService.getTokenHeader();
        String tokenBefore = request.header(tokenHeaderKey);

        // we successfully obtained a token so add it to the header for the request
        String addedTokenHeader = null;
        String addedTokenPrefix = null;
        String addedTokenValue = null;
        String addedTraceIDHeader = null;
        if ((approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS)
                && (approovResults.getToken() != null) && !approovResults.getToken().isEmpty()) {
            addedTokenHeader = approovService.getTokenHeader();
            addedTokenPrefix = approovService.getTokenPrefix();
            addedTokenValue = approovResults.getToken();
            request = request.newBuilder().header(addedTokenHeader, addedTokenPrefix + addedTokenValue).build();
        } else if ((approovResults.getToken() == null || approovResults.getToken().isEmpty())
                && approovService.getUseApproovStatusIfNoToken()) {
            addedTokenHeader = approovService.getTokenHeader();
            addedTokenPrefix = approovService.getTokenPrefix();
            addedTokenValue = approovResults.getStatus().toString();
            request = request.newBuilder().header(addedTokenHeader, addedTokenPrefix + addedTokenValue).build();
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
        approovService.logInfo(TAG, "request mutation " + url
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
                Log.d(TAG, "substituting header: " + header + ", " + approovResults.getStatus().toString());

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
                Log.d(TAG, "substituting query parameter: " + queryKey + ", " + approovResults.getStatus().toString());

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
        }

        // proceed with the rest of the chain
        return chain.proceed(request);
    }
}
