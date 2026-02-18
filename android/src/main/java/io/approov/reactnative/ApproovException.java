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

import java.io.IOException;

/**
 * Base exception indicating an error while using the Approov SDK.
 * <p>
 * For token fetch failures prefer using {@link ApproovFetchStatusException}, which preserves the
 * {@link com.criticalblue.approovsdk.Approov.TokenFetchStatus} reported by the SDK.
 */
public class ApproovException extends IOException {

    /**
     * Constructs an exception due to an Approov error.
     *
     * @param message information describing the exception cause
     */
    public ApproovException(String message) {
        super(message);
    }

    /**
     * Constructs an exception with an underlying cause.
     *
     * @param message information describing the exception cause
     * @param cause underlying cause of the exception
     */
    public ApproovException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs an exception with an underlying cause, using the cause message when available.
     *
     * @param cause underlying cause of the exception
     */
    public ApproovException(Throwable cause) {
        super("Wrapped " + cause.getClass().getName() + ": " + cause.getMessage(), cause);
    }
}
