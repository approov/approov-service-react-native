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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import okhttp3.Headers;
import okhttp3.Request;

/**
 * Records what ApproovInterceptor applied to a request, carried as a request tag.
 *
 * OkHttp follows redirects and retries below the application interceptors: it builds the
 * follow-up from the previous request, keeping its headers, so the token, trace ID,
 * substituted secrets and signature added for the first URL would be sent to the next one,
 * including a host that is not Approov-protected. Tags survive Request.newBuilder(), so the
 * network interceptor sees this record on every attempt and can tell a rebuilt one apart.
 */
final class ApproovAppliedRequest {
    // logging tag
    private static final String TAG = "ApproovService";

    // the interceptor that processed the request, used to reprocess a rebuilt attempt
    private final ApproovInterceptor processor;

    // the request before and after Approov processing
    private final Request original;
    private final Request applied;

    // headers of the first network attempt, which adds OkHttp's transport headers to the
    // applied request; later attempts with other headers have been rebuilt
    private volatile Headers networkBaseline;

    ApproovAppliedRequest(ApproovInterceptor processor, Request original, Request applied) {
        this.processor = processor;
        this.original = original;
        this.applied = applied;
    }

    /**
     * Returns the request to send for a network attempt. The first attempt for the applied URL
     * is sent as it is. An attempt OkHttp rebuilt since (a redirect to another URL, a changed
     * method, or changed headers) has every header Approov added or changed put back to the
     * app's values, and is then processed again for its own URL.
     *
     * @param attempt the request reaching the network interceptor
     * @return the request to send, for the same host and port as the attempt
     * @throws IOException if reprocessing must fail the request
     */
    Request reclassifyIfRebuilt(Request attempt) throws IOException {
        boolean sameTarget = attempt.url().equals(applied.url()) && attempt.method().equals(applied.method());
        if (sameTarget) {
            if (networkBaseline == null) {
                networkBaseline = attempt.headers();
                return attempt;
            }
            if (networkBaseline.equals(attempt.headers()))
                return attempt;
        }

        // take back what Approov applied, leaving headers someone else changed since
        Request.Builder clean = attempt.newBuilder();
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(original.headers().names());
        names.addAll(applied.headers().names());
        for (String name : names) {
            List<String> appValues = original.headers(name);
            List<String> appliedValues = applied.headers(name);
            if (appValues.equals(appliedValues) || !attempt.headers(name).equals(appliedValues))
                continue;
            clean.removeHeader(name);
            for (String value : appValues)
                clean.addHeader(name, value);
        }

        // an unchanged URL may carry substituted query parameters, so restore the app's URL
        // (the same host and port, as OkHttp requires of network interceptors)
        if (attempt.url().equals(applied.url()))
            clean.url(original.url());

        ApproovService.log(ApproovService.LOG_DEBUG, TAG,
                "request rebuilt after Approov processing (redirect or retry), reprocessing: " + attempt.url());
        Request reprocessed = processor.protect(clean.build());
        ApproovAppliedRequest record = reprocessed.tag(ApproovAppliedRequest.class);
        if (record != null)
            record.networkBaseline = reprocessed.headers();
        return reprocessed;
    }
}
