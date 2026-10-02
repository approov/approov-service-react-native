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

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import okhttp3.Request;

/**
 * Records, as a request tag, the host Approov protected a request for and the names of the
 * headers it added or changed (token, trace ID, substituted secrets, signature).
 *
 * OkHttp follows redirects below the application interceptors and builds the follow-up from the
 * previous request's headers, so everything Approov added for one host would be sent to the
 * redirect target. Tags survive Request.newBuilder(), so the network interceptor can remove those
 * headers from any attempt to another host. Nothing is restored and nothing is protected again:
 * a follow-up to another host carries no Approov credentials at all, and one to the same host is
 * left as it is.
 */
final class ApproovIssuedHeaders {
    // logging tag
    private static final String TAG = "ApproovService";

    // the host the credentials were issued for
    private final String host;

    // names of the headers Approov added or changed, compared case insensitively
    private final Set<String> names;

    private ApproovIssuedHeaders(String host, Set<String> names) {
        this.host = host;
        this.names = Collections.unmodifiableSet(names);
    }

    /**
     * Tags a processed request with the headers Approov added or changed while processing it.
     * Headers recorded by an earlier pass for the same host are kept, so a new call built from a
     * processed request (such as Response.request()) still has the earlier credentials removed if
     * it is redirected to another host.
     *
     * @param original the request before processing
     * @param applied  the request after processing
     * @return the applied request carrying the record, or as it is if Approov changed nothing
     */
    static Request tag(Request original, Request applied) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ApproovIssuedHeaders earlier = original.tag(ApproovIssuedHeaders.class);
        if ((earlier != null) && earlier.host.equalsIgnoreCase(applied.url().host()))
            names.addAll(earlier.names);
        Set<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        all.addAll(original.headers().names());
        all.addAll(applied.headers().names());
        for (String name : all) {
            if (!original.headers(name).equals(applied.headers(name)))
                names.add(name);
        }
        if (names.isEmpty())
            return applied;
        return applied.newBuilder()
                .tag(ApproovIssuedHeaders.class, new ApproovIssuedHeaders(applied.url().host(), names))
                .build();
    }

    /**
     * Removes every recorded header, all of its values, from a request to a host other than the
     * one the credentials were issued for. A request to that host is returned unchanged.
     *
     * @param request the request about to be sent or processed
     * @return the request without Approov credentials if it targets another host
     */
    Request stripIfOtherHost(Request request) {
        if (request.url().host().equalsIgnoreCase(host))
            return request;
        Request.Builder stripped = request.newBuilder();
        for (String name : names)
            stripped.removeHeader(name);
        ApproovService.log(ApproovService.LOG_DEBUG, TAG,
                "Approov headers issued for " + host + " removed from a request to " + request.url().host());
        return stripped.build();
    }
}
