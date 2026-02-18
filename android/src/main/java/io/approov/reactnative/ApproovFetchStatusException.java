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

/**
 * Exception raised when an Approov token fetch returns a status other than success.
 */
public class ApproovFetchStatusException extends ApproovException {

    private final Approov.TokenFetchStatus tokenFetchStatus;

    /**
     * Constructs a token fetch status exception with the provided status.
     *
     * @param status status returned by the Approov SDK, may be {@code null} if unavailable
     * @param message information describing the exception cause
     */
    public ApproovFetchStatusException(Approov.TokenFetchStatus status, String message) {
        super(message);
        this.tokenFetchStatus = status;
    }

    /**
     * Retrieves the token fetch status associated with this exception.
     *
     * @return the status returned by the Approov SDK, or {@code null} if not provided
     */
    public Approov.TokenFetchStatus getTokenFetchStatus() {
        return tokenFetchStatus;
    }
}
