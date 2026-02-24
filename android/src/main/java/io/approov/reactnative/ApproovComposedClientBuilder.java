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

import android.util.Log;

import com.facebook.react.modules.network.NetworkingModule;

import okhttp3.OkHttpClient;

/**
 * Composes an upstream React Native networking custom builder with the Approov
 * builder. Upstream is applied first and Approov is applied last, so Approov
 * protections cannot be accidentally removed by ordering.
 */
final class ApproovComposedClientBuilder implements NetworkingModule.CustomClientBuilder {
    private static final String TAG = "ApproovService";

    private final NetworkingModule.CustomClientBuilder upstreamBuilder;
    private final NetworkingModule.CustomClientBuilder approovBuilder;

    ApproovComposedClientBuilder(NetworkingModule.CustomClientBuilder upstreamBuilder,
            NetworkingModule.CustomClientBuilder approovBuilder) {
        this.upstreamBuilder = upstreamBuilder;
        this.approovBuilder = approovBuilder;
    }

    NetworkingModule.CustomClientBuilder getUpstreamBuilder() {
        return upstreamBuilder;
    }

    NetworkingModule.CustomClientBuilder getApproovBuilder() {
        return approovBuilder;
    }

    @Override
    public void apply(OkHttpClient.Builder builder) {
        if (builder == null) {
            return;
        }

        if (upstreamBuilder != null) {
            try {
                upstreamBuilder.apply(builder);
            } catch (Throwable t) {
                // Keep moving so Approov hardening still applies.
                Log.w(TAG, "Upstream custom client builder threw; continuing with Approov builder", t);
            }
        }

        if (approovBuilder != null) {
            approovBuilder.apply(builder);
        }
    }
}
