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

import com.facebook.react.modules.network.NetworkingModule.CustomClientBuilder;

import android.util.Log;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

/**
 * Custom OkHttpClient builder for React Native that adds Approov token injection
 * (via ApproovInterceptor) and certificate pinning (via ApproovPinningInterceptor).
 *
 * Pinning is now handled by a NetworkInterceptor rather than OkHttp's CertificatePinner.
 * This means pins are read from Approov at TLS-handshake time during each request,
 * not eagerly during initialize(). See ApproovPinningInterceptor for details.
 */
public class ApproovClientBuilder implements CustomClientBuilder {
    // underlying ApproovService that is wrapping the SDK
    private ApproovService approovService;

    // interceptor for adding Approov tokens or substituting headers and/or query parameters
    private Interceptor interceptor;

    // network interceptor for certificate pinning at TLS handshake time
    private ApproovPinningInterceptor pinningInterceptor;

    // prior client builder that might have been set by another SDK (e.g. New Relic)
    private CustomClientBuilder wrappedBuilder;

    /**
     * Creates a long-lived ApproovClientBuilder for OkHttp requests. This adds
     * the Approov token interceptor and a network-level pinning interceptor. Pins
     * are verified at TLS-handshake time so no eager Approov.getPins() call is
     * needed at initialization.
     *
     * @param approovService is the ApproovService being used
     * @param wrappedBuilder is the CustomClientBuilder that was already set, or
     *                       null if none
     */
    public ApproovClientBuilder(ApproovService approovService, CustomClientBuilder wrappedBuilder) {
        this.approovService = approovService;
        this.wrappedBuilder = wrappedBuilder;
        this.interceptor = new ApproovInterceptor(approovService);
        this.pinningInterceptor = new ApproovPinningInterceptor(approovService);
    }

    /**
     * Returns the pinning interceptor so that callers (e.g. ApproovInterceptor) can
     * clear the handshake cache when a dynamic configuration change is detected.
     *
     * @return the ApproovPinningInterceptor used by this builder
     */
    public ApproovPinningInterceptor getPinningInterceptor() {
        return pinningInterceptor;
    }

    @Override
    public void apply(OkHttpClient.Builder builder) {
        // apply the wrapped builder first if it exists
        if (wrappedBuilder != null)
            wrappedBuilder.apply(builder);

        if (builder == null)
            return;

        // guard against duplicate insertion (e.g. both the OkHttpClientFactory and the legacy
        // setCustomClientBuilder paths firing on RN < 0.73, or any other repeated apply() call)
        for (Interceptor existing : builder.interceptors()) {
            if (existing instanceof ApproovInterceptor)
                return; // already present — skip to avoid stacking
        }

        builder.addInterceptor(interceptor)
               .addNetworkInterceptor(pinningInterceptor);
    }
}
