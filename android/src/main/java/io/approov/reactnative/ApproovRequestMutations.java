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

import java.util.List;

/**
 * ApproovRequestMutations stores information about changes made to a network request
 * during Approov processing, such as token headers, substituted headers, and query parameters.
 */
public class ApproovRequestMutations {
    private String tokenHeaderKey;
    private String traceIDHeaderKey;
    private List<String> substitutionHeaderKeys;
    private String originalURL;
    private List<String> substitutionQueryParamKeys;

    /**
     * Gets the header key used for the Approov token.
     *
     * @return the Approov token header key
     */
    public String getTokenHeaderKey() {
        return tokenHeaderKey;
    }

    /**
     * Sets the header key used for the Approov token.
     *
     * @param tokenHeaderKey the Approov token header key
     */
    public void setTokenHeaderKey(String tokenHeaderKey) {
        this.tokenHeaderKey = tokenHeaderKey;
    }

    /**
     * Gets the header key used for the optional Approov TraceID debug header.
     *
     * @return the Approov TraceID header key. Null if the TraceID header was
     *         not used.
     */
    public String getTraceIDHeaderKey() {
        return traceIDHeaderKey;
    }

    /**
     * Sets the header key used for the optional Approov TraceID debug header.
     *
     * @param traceIDHeaderKey the Approov TraceID header key
     */
    public void setTraceIDHeaderKey(String traceIDHeaderKey) {
        this.traceIDHeaderKey = traceIDHeaderKey;
    }

    /**
     * Gets the list of headers that were substituted with secure strings.
     *
     * @return the list of substituted header keys
     */
    public List<String> getSubstitutionHeaderKeys() {
        return substitutionHeaderKeys;
    }

    /**
     * Sets the list of headers that were substituted with secure strings.
     *
     * @param substitutionHeaderKeys the list of substituted header keys
     */
    public void setSubstitutionHeaderKeys(List<String> substitutionHeaderKeys) {
        this.substitutionHeaderKeys = substitutionHeaderKeys;
    }

    /**
     * Gets the original URL before any query parameter substitutions.
     *
     * @return the original URL
     */
    public String getOriginalURL() {
        return originalURL;
    }

    /**
     * Gets the list of query parameter keys that were substituted with secure strings.
     *
     * @return the list of substituted query parameter keys
     */
    public List<String> getSubstitutionQueryParamKeys() {
        return substitutionQueryParamKeys;
    }

    /**
     * Sets the results of query parameter substitutions, including the original URL and the keys of substituted parameters.
     *
     * @param originalURL the original URL before substitutions
     * @param substitutionQueryParamKeys the list of substituted query parameter keys
     */
    public void setSubstitutionQueryParamResults(String originalURL, List<String> substitutionQueryParamKeys) {
        this.originalURL = originalURL;
        this.substitutionQueryParamKeys = substitutionQueryParamKeys;
    }
}
