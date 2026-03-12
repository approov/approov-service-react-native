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

import okhttp3.CertificatePinner;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

// ApproovClientBuilder is a custom client building for OkHttp to add Approov protection, including dynamic pinning
public class ApproovClientBuilder implements CustomClientBuilder, ApproovService.PinChangeListener {
    // underlying ApproovService that is wrapping the SDK
    private ApproovService approovService;

    // interceptor for adding Approov tokens or substituting headers and/or query
    // parameters
    private Interceptor interceptor;

    // current certificate pinner to be used
    private CertificatePinner pinner;

    // prior client builder that might have been set by another SDK (e.g. New Relic)
    private CustomClientBuilder wrappedBuilder;

    /**
     * Creates a long-lived ApproovClientBuilder for OkHttp requests. This adds
     * the interceptor and certificate pinning, which can be dynamically updated
     * if the pins change during app usage. The builder registers itself as a
     * PinChangeListener so that it is notified when pins are updated.
     *
     * @param approovService is the ApproovService being used
     * @param wrappedBuilder is the CustomClientBuilder that was already set, or
     *                       null if none
     */
    public ApproovClientBuilder(ApproovService approovService, CustomClientBuilder wrappedBuilder) {
        this(approovService, wrappedBuilder, false);
    }

    /**
     * Creates an ApproovClientBuilder for OkHttp requests. This adds the
     * interceptor and certificate pinning. When {@code ephemeral} is false the
     * builder registers itself as a PinChangeListener so that it can react to
     * dynamic pin changes over the app's lifetime. When {@code ephemeral} is
     * true the builder is intended for a single, short-lived request (e.g.
     * fetchWithApproov) and does NOT register as a listener, avoiding unbounded
     * listener list growth.
     *
     * @param approovService is the ApproovService being used
     * @param wrappedBuilder is the CustomClientBuilder that was already set, or
     *                       null if none
     * @param ephemeral      if true, skip PinChangeListener registration
     */
    public ApproovClientBuilder(ApproovService approovService, CustomClientBuilder wrappedBuilder, boolean ephemeral) {
        this.approovService = approovService;
        this.wrappedBuilder = wrappedBuilder;

        // set initial certificate pinner
        pinner = ApproovCertificatePinner.build(approovService);

        // set the interceptor
        interceptor = new ApproovInterceptor(approovService);

        // only register for pin change notifications on long-lived builders;
        // ephemeral builders (used by fetchWithApproov) build a fresh pinner on
        // every call so listening for updates would just leak references.
        if (!ephemeral) {
            approovService.addPinChangeListener(this);
        }
    }

    /**
     * Handles a change to the Approov pins by creating a new pinner.
     */
    public void approovPinsUpdated() {
        pinner = ApproovCertificatePinner.build(approovService);
    }

    @Override
    public void apply(OkHttpClient.Builder builder) {
        // apply the wrapped builder first if it exists
        if (wrappedBuilder != null)
            wrappedBuilder.apply(builder);

        if (builder != null) {
            builder.addInterceptor(interceptor).certificatePinner(pinner);
        }
    }
}
