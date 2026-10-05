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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import okhttp3.HttpUrl;
import okhttp3.Request;

/**
 * Records, as a request tag, the origin (scheme, host and port) Approov protected a request for
 * and the header values it added or changed (token, trace ID, substituted secrets, signature).
 *
 * OkHttp follows redirects below the application interceptors and builds the follow-up from the
 * previous request's headers, so everything Approov added for one origin would be sent to the
 * redirect target. Tags survive Request.newBuilder(), so the network interceptor can remove those
 * headers from any attempt to another origin. A change of scheme or port counts as another
 * origin, so a redirect from https to http on the same host does not send the credentials in
 * cleartext. Nothing is restored and nothing is protected again: a follow-up to another origin
 * carries no Approov credentials at all, and one to the same origin is left as it is.
 */
final class ApproovIssuedHeaders {
    // logging tag
    private static final String TAG = "ApproovService";

    // the origin the credentials were issued for
    private final String scheme;
    private final String host;
    private final int port;

    // header name (compared case insensitively) to the values Approov set for it
    private final Map<String, Set<String>> values;

    private ApproovIssuedHeaders(HttpUrl url, Map<String, Set<String>> values) {
        this.scheme = url.scheme();
        this.host = url.host();
        this.port = url.port();
        this.values = Collections.unmodifiableMap(values);
    }

    private boolean isSameOrigin(HttpUrl url) {
        return scheme.equals(url.scheme()) && host.equalsIgnoreCase(url.host()) && (port == url.port());
    }

    /**
     * Tags a processed request with the header values Approov added or changed while processing
     * it. Values recorded by an earlier pass for the same origin are kept, so a new call built from
     * a processed request (such as Response.request()) still has the earlier credentials removed
     * if it is redirected to another origin. A record for another origin is dropped.
     *
     * @param original the request before processing
     * @param applied  the request after processing
     * @return the applied request carrying the record, or without a record if Approov changed
     *         nothing
     */
    static Request tag(Request original, Request applied) {
        Map<String, Set<String>> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ApproovIssuedHeaders earlier = original.tag(ApproovIssuedHeaders.class);
        if ((earlier != null) && earlier.isSameOrigin(applied.url())) {
            for (Map.Entry<String, Set<String>> entry : earlier.values.entrySet())
                values.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        Set<String> names = new HashSet<>();
        names.addAll(original.headers().names());
        names.addAll(applied.headers().names());
        for (String name : names) {
            List<String> after = applied.headers(name);
            if (!original.headers(name).equals(after) && !after.isEmpty())
                values.computeIfAbsent(name, key -> new HashSet<>()).addAll(after);
        }
        if (values.isEmpty())
            return (earlier == null) ? applied : applied.newBuilder().tag(ApproovIssuedHeaders.class, null).build();
        return applied.newBuilder()
                .tag(ApproovIssuedHeaders.class, new ApproovIssuedHeaders(applied.url(), values))
                .build();
    }

    /**
     * Removes, from a request to another origin, every header that still carries a value Approov
     * set for the recorded origin. All values of such a header are removed, including any a later
     * interceptor appended. A header the app has since set to its own value is kept. A request to
     * the recorded origin is returned unchanged.
     *
     * @param request the request about to be sent or processed
     * @return the request without Approov credentials if it targets another origin
     */
    Request stripIfOtherOrigin(Request request) {
        if (isSameOrigin(request.url()))
            return request;
        Request.Builder stripped = null;
        for (Map.Entry<String, Set<String>> entry : values.entrySet()) {
            if (carriesApplied(request.headers(entry.getKey()), entry.getValue())) {
                if (stripped == null)
                    stripped = request.newBuilder();
                stripped.removeHeader(entry.getKey());
            }
        }
        if (stripped == null)
            return request;
        ApproovService.log(ApproovService.LOG_DEBUG, TAG,
                "Approov headers issued for " + scheme + "://" + host + ":" + port
                        + " removed from a request to " + request.url().scheme() + "://"
                        + request.url().host() + ":" + request.url().port());
        return stripped.build();
    }

    // true if any of the header's values contains a value Approov set
    private static boolean carriesApplied(List<String> current, Set<String> applied) {
        for (String value : current) {
            for (String set : applied) {
                if (!set.isEmpty() && value.contains(set))
                    return true;
            }
        }
        return false;
    }
}
