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
import com.facebook.react.modules.network.ReactCookieJarContainer;

import android.content.Context;
import android.util.Log;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

// ApproovClientBuilder is a custom client builder for OkHttp to add Approov protection. It installs an
// application interceptor (ApproovTokenInterceptor) for token/header/query mutation and a network
// interceptor (ApproovPinningInterceptor) for dynamic certificate pinning against the peer-presented
// chain.
public class ApproovClientBuilder implements CustomClientBuilder {
    // underlying ApproovService that is wrapping the SDK
    private ApproovService approovService;

    // application interceptor for adding Approov tokens or substituting headers and/or query
    // parameters
    private Interceptor interceptor;

    // network interceptor for enforcing dynamic certificate pinning
    private ApproovPinningInterceptor pinningInterceptor;

    // prior client builder that might have been set by another SDK (e.g. New Relic)
    private CustomClientBuilder wrappedBuilder;

    /**
     * Creates a long-lived ApproovClientBuilder for OkHttp requests. This adds the token interceptor
     * and the pinning network interceptor. The pinning interceptor is registered with the
     * ApproovService so that its pins can be rebuilt in place when the dynamic configuration changes.
     *
     * @param approovService is the ApproovService being used
     * @param wrappedBuilder is the CustomClientBuilder that was already set, or
     *                       null if none
     */
    public ApproovClientBuilder(ApproovService approovService, CustomClientBuilder wrappedBuilder) {
        this(approovService, wrappedBuilder, false);
    }

    /**
     * Creates an ApproovClientBuilder for OkHttp requests. This adds the token interceptor and the
     * pinning network interceptor. When {@code ephemeral} is false the pinning interceptor is
     * registered with the ApproovService so that it can react to dynamic pin changes over the app's
     * lifetime. When {@code ephemeral} is true the builder is intended for a single, short-lived
     * request (e.g. fetchWithApproov) and does NOT register the pinning interceptor, avoiding
     * unbounded growth of the registry; such builders simply build fresh pins per call.
     *
     * @param approovService is the ApproovService being used
     * @param wrappedBuilder is the CustomClientBuilder that was already set, or
     *                       null if none
     * @param ephemeral      if true, skip registering the pinning interceptor for dynamic updates
     */
    public ApproovClientBuilder(ApproovService approovService, CustomClientBuilder wrappedBuilder, boolean ephemeral) {
        this.approovService = approovService;
        this.wrappedBuilder = wrappedBuilder;

        // set the application interceptor
        interceptor = new ApproovTokenInterceptor(approovService);

        // set the pinning network interceptor (builds its initial pins immediately)
        pinningInterceptor = new ApproovPinningInterceptor(approovService);

        // only register the pinning interceptor for dynamic pin rebuilds on long-lived builders;
        // ephemeral builders (used by fetchWithApproov) are single-use and build fresh pins on
        // construction, so registering them would just leak references.
        if (!ephemeral) {
            approovService.registerPinningInterceptor(pinningInterceptor);
        }
    }

    @Override
    public void apply(OkHttpClient.Builder builder) {
        // apply the wrapped builder first if it exists
        if (wrappedBuilder != null)
            wrappedBuilder.apply(builder);

        if (builder != null) {
            // Guard against double-registration: on RN < 0.73 both the
            // OkHttpClientFactory and legacy setCustomClientBuilder paths may
            // fire for the same builder. Adding the interceptors twice would
            // cause duplicate token fetches and signature generations.
            boolean tokenPresent = false;
            for (Interceptor existing : builder.interceptors()) {
                if (existing instanceof ApproovTokenInterceptor) {
                    tokenPresent = true;
                    break;
                }
            }
            if (!tokenPresent) {
                builder.addInterceptor(interceptor);
            }

            // pinning runs as a network interceptor so it can inspect the live TLS handshake
            boolean pinningPresent = false;
            for (Interceptor existing : builder.networkInterceptors()) {
                if (existing instanceof ApproovPinningInterceptor) {
                    pinningPresent = true;
                    break;
                }
            }
            if (!pinningPresent) {
                builder.addNetworkInterceptor(pinningInterceptor);
            }
        }
    }
}
