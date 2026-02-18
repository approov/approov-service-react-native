//
// MIT License
// 
// Copyright (c) 2016-present, Approov Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
// (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
// publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
// subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
// 
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
// ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
// THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package io.approov.reactnative;

import com.criticalblue.approovsdk.Approov;
import okhttp3.Request;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * ApproovServiceMutator provides an interface for modifying the behavior of
 * the ApproovService class by overriding the default implementations of the
 * defined callbacks. Opportunities to modify behavior are offered at key
 * points in the service and attestation flows.
 *
 * The interface provides default implementations for all methods, so
 * implementing classes can choose to override only the methods they are
 * interested in. The default implementations provide standard behavior
 * that is suitable for most use cases and provides backwards compatibility
 * with previous versions of this Approov service layer.
 */
public interface ApproovServiceMutator {
    /**
     * Default mutator that provides standard behavior with no changes.
     */
    public static final ApproovServiceMutator DEFAULT = new ApproovServiceMutator() {
        @Override
        public String toString() {
            return "ApproovServiceMutator.DEFAULT";
        }
    };

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.precheck() operation.
     *
     * @param approovResults the TokenFetchResult obtained by
     *                       ApproovService.precheck()
     * @throws ApproovException The implementation can either return, taking no
     *                          action or throw an ApproovException encoding
     *                          the cause of the failure.
     */
    @SuppressWarnings("deprecation")
    default void handlePrecheckResult(Approov.TokenFetchResult approovResults) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        String arc = approovResults.getARC();
        String rejectionReasons = approovResults.getRejectionReasons();
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException(
                        "precheck: " + status.toString() + ": " + arc + " " + rejectionReasons, arc, rejectionReasons);
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                throw new ApproovNetworkException(status, "precheck: " + status.toString());
            case SUCCESS:
            case UNKNOWN_KEY:
                break;
            default:
                throw new ApproovFetchStatusException(status, "precheck: " + status.toString());
        }
    }

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.fetchToken() operation.
     *
     * @param approovResults the TokenFetchResult obtained by
     *                       ApproovService.fetchToken()
     * @throws ApproovException The implementation can either return, taking no
     *                          action or throw an ApproovException encoding
     *                          the cause of the failure.
     */
    @SuppressWarnings("deprecation")
    default void handleFetchTokenResult(Approov.TokenFetchResult approovResults) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (status) {
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                throw new ApproovNetworkException(status, "fetchToken: " + status.toString());
            case SUCCESS:
                break;
            default:
                throw new ApproovFetchStatusException(status, "fetchToken: " + status.toString());
        }
    }

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.fetchSecureString() operation.
     *
     * @param approovResults the TokenFetchResult obtained by
     *                       ApproovService.fetchSecureString()
     * @param operation      the operation type ("lookup" or "definition"); "lookup"
     *                       indicates that an existing value was requested, while
     *                       "definition" indicates that a new value was being added
     *                       or set
     * @param key            the secure string key
     * @throws ApproovException The implementation can either return, taking no
     *                          action or throw an ApproovException encoding
     *                          the cause of the failure
     */
    @SuppressWarnings("deprecation")
    default void handleFetchSecureStringResult(Approov.TokenFetchResult approovResults, String operation, String key)
            throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        String arc = approovResults.getARC();
        String rejectionReasons = approovResults.getRejectionReasons();
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException("fetchSecureString " + operation + " for " + key + ": "
                        + status.toString() + ": " + arc + " " + rejectionReasons, arc, rejectionReasons);
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                throw new ApproovNetworkException(status,
                        "fetchSecureString " + operation + " for " + key + ": " + status.toString());
            case SUCCESS:
            case UNKNOWN_KEY:
                break;
            default:
                throw new ApproovFetchStatusException(status,
                        "fetchSecureString " + operation + " for " + key + ": " + status.toString());
        }
    }

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.fetchCustomJWT() operation.
     *
     * @param approovResults the TokenFetchResult obtained by
     *                       ApproovService.fetchCustomJWT()
     * @throws ApproovException The implementation can either return, taking no
     *                          action or throw an ApproovException encoding
     *                          the cause of the failure
     */
    @SuppressWarnings("deprecation")
    default void handleFetchCustomJWTResult(Approov.TokenFetchResult approovResults) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        String arc = approovResults.getARC();
        String rejectionReasons = approovResults.getRejectionReasons();
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException(
                        "fetchCustomJWT: " + status.toString() + ": " + arc + " " + rejectionReasons, arc,
                        rejectionReasons);
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                throw new ApproovNetworkException(status, "fetchCustomJWT: " + status.toString());
            case SUCCESS:
                break;
            default:
                throw new ApproovFetchStatusException(status, "fetchCustomJWT: " + status.toString());
        }
    }

    /**
     * Decides whether a request should be processed in the interceptor or not.
     * Called at the start of the ApproovService interceptor processing.
     *
     * @param service the ApproovService instance
     * @param request the request property extracted from the interceptor chain
     * @return true if the request should be processed by the Approov interceptor,
     *         false if it should be issued unchanged
     * @throws ApproovException The implementation can either return to indicate the
     *                          action described above or throw an ApproovException
     *                          encoding the cause of the failure
     */
    default boolean handleInterceptorShouldProcessRequest(ApproovService service, Request request)
            throws ApproovException {
        if (request == null)
            throw new ApproovException(
                    "handleInterceptorShouldProcessRequest method was passed a request that is null!");

        // check if the URL matches one of the exclusion regexs and skip interceptor
        // processing in these cases
        String url = request.url().toString();
        for (Pattern pattern : service.getExclusionURLRegexs().values()) {
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decides how to handle the token fetch result from a call to
     * Approov.fetchApproovTokenAndWait() from within the interceptor.
     *
     * @param service        the ApproovService instance
     * @param approovResults the TokenFetchResult from Approov
     * @param url            the URL string for which the token was requested
     * @return true if processing should continue, false if request should proceed
     *         even though no token was obtained from the fetch
     * @throws ApproovException The implementation can either return to indicate the
     *                          action described above or throw an ApproovException
     *                          encoding the cause of the failure
     */
    @SuppressWarnings("deprecation")
    default boolean handleInterceptorFetchTokenResult(ApproovService service, Approov.TokenFetchResult approovResults,
            String url)
            throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (status) {
            case SUCCESS:
                return true;
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                if (service.getUseApproovStatusIfNoToken())
                    return true;
                if (!service.isProceedOnNetworkFail())
                    throw new ApproovNetworkException(status,
                            "Approov token fetch for " + url + ": " + status.toString());
                return false;
            case NO_APPROOV_SERVICE:
            case UNKNOWN_URL:
            case UNPROTECTED_URL: // Continue without token for unprotected URLs
                return false;
            default:
                throw new ApproovFetchStatusException(status,
                        "Approov token fetch for " + url + ": " + status.toString());
        }
    }

    /**
     * Decides how to handle the token fetch result while substituting headers from
     * within the interceptor. The passed fetch result to process is associated with
     * a preceding call to Approov.fetchSecureStringAndWait which passed in the
     * current header value (minus a prefix) as the key. This method is called once
     * per header being processed for substitution.
     *
     * @param service        the ApproovService instance
     * @param approovResults the TokenFetchResult from Approov
     * @param header         the header being substituted
     * @return true if substitution should proceed, false if it should be skipped
     * @throws ApproovException The implementation can either return to indicate the
     *                          action described above or throw an ApproovException
     *                          encoding the cause of the failure
     */
    @SuppressWarnings("deprecation")
    default boolean handleInterceptorHeaderSubstitutionResult(ApproovService service,
            Approov.TokenFetchResult approovResults, String header)
            throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        String arc = approovResults.getARC();
        String rejectionReasons = approovResults.getRejectionReasons();
        switch (status) {
            case SUCCESS:
                return true;
            case REJECTED:
                throw new ApproovRejectionException("Header substitution for " + header + ": " + status.toString()
                        + ": " + arc + " " + rejectionReasons, arc, rejectionReasons);
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                if (!service.isProceedOnNetworkFail())
                    throw new ApproovNetworkException(status,
                            "Header substitution for " + header + ": " + status.toString());
                return false;
            case UNKNOWN_KEY:
                return false;
            default:
                throw new ApproovFetchStatusException(status,
                        "Header substitution for " + header + ": " + status.toString());
        }
    }

    /**
     * Decides how to handle the token fetch result while substituting query params
     * from within the interceptor. The passed fetch result to process is associated
     * with a preceding call to Approov.fetchSecureStringAndWait which passed in the
     * query value of a matching query key. This method is called once for each
     * matched
     * query parameter being processed for substitution.
     *
     * @param service        the ApproovService instance
     * @param approovResults the TokenFetchResult from Approov
     * @param queryKey       the query parameter key being substituted
     * @return true if substitution should proceed, false if it should be skipped
     * @throws ApproovException The implementation can either return to indicate the
     *                          action described above or throw an ApproovException
     *                          encoding the cause of the failure
     */
    @SuppressWarnings("deprecation")
    default boolean handleInterceptorQueryParamSubstitutionResult(ApproovService service,
            Approov.TokenFetchResult approovResults,
            String queryKey) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        String arc = approovResults.getARC();
        String rejectionReasons = approovResults.getRejectionReasons();
        switch (status) {
            case SUCCESS:
                return true;
            case REJECTED:
                throw new ApproovRejectionException("Query parameter substitution for " + queryKey + ": "
                        + status.toString() + ": " + arc + " " + rejectionReasons, arc, rejectionReasons);
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                if (!service.isProceedOnNetworkFail())
                    throw new ApproovNetworkException(status,
                            "Query parameter substitution for " + queryKey + ": " + status.toString());
                return false;
            case UNKNOWN_KEY:
                return false;
            default:
                throw new ApproovFetchStatusException(status,
                        "Query parameter substitution for " + queryKey + ": " + status.toString());
        }
    }

    /**
     * Called after Approov has processed a network request, allowing further
     * modifications.
     *
     * @param service the ApproovService instance
     * @param request the processed request
     * @param changes the mutations applied to the request by Approov
     * @return the final request to use to complete the Approov interceptor step.
     * @throws ApproovException The implementation can either return as described
     *                          above or throw an ApproovException encoding the
     *                          cause of the failure
     */
    default Request handleInterceptorProcessedRequest(ApproovService service, Request request,
            ApproovRequestMutations changes)
            throws ApproovException {
        // No further changes to the request are required
        return request;
    }

    /**
     * Decides whether certificate pinning should be applied to a request or not.
     * Called at the start of the ApproovService pinning processing.
     *
     * @param request the request being processed
     * @return true if pinning should be applied, false to skip it
     */
    default boolean handlePinningShouldProcessRequest(Request request) {
        // By default do not skip pinning for any requests
        return true;
    }
}
