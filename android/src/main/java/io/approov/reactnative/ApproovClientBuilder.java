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

import okhttp3.CertificatePinner;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

// ApproovClientBuilder is a custom client building for OkHttp to add Approov protection, including dynamic pinning
public class ApproovClientBuilder implements CustomClientBuilder {
    // interceptor for adding Approov tokens or substituting headers and/or query
    // parameters; null once retired
    private volatile Interceptor interceptor;

    // Shared by all clients of this service; constructing a builder never fetches pins.
    private volatile ApproovPinningInterceptor pinningInterceptor;

    // prior client builder that might have been set by another SDK (e.g. New Relic). Held as
    // Object because React Native types this hook as the nested
    // NetworkingModule.CustomClientBuilder on some versions and the top-level
    // com.facebook.react.modules.network.CustomClientBuilder on others.
    private final Object wrappedBuilder;

    // set when a later Approov registration supersedes this builder: it then applies only the
    // builder it wraps, adds no Approov protection, and holds no reference to the old service
    private volatile boolean retired;

    /** Creates a builder using the service's shared pinning state. */
    public ApproovClientBuilder(ApproovService approovService, CustomClientBuilder wrappedBuilder) {
        this(approovService, (Object) wrappedBuilder);
    }

    /**
     * Retains the existing constructor for callers creating short-lived builders. All builders
     * now share service-owned pins, so neither kind registers listeners or fetches pins.
     */
    public ApproovClientBuilder(ApproovService approovService, CustomClientBuilder wrappedBuilder, boolean ephemeral) {
        this(approovService, (Object) wrappedBuilder);
    }

    private ApproovClientBuilder(ApproovService approovService, Object wrappedBuilder) {
        this.wrappedBuilder = wrappedBuilder;
        interceptor = new ApproovInterceptor(approovService);
        pinningInterceptor = approovService.getPinningInterceptor();
    }

    /** Wraps the vendor's per-request callback, sharing the service's pinning interceptor. */
    static ApproovClientBuilder wrapping(ApproovService approovService, Object previousBuilder) {
        return new ApproovClientBuilder(approovService, previousBuilder);
    }

    /**
     * Supersedes this builder after a later Approov registration. React Native (or a third
     * party's wrapper) may keep applying it, so it continues to apply the builder it wraps but
     * adds no Approov protection, and it drops every reference to the old service so that the
     * service and its ReactContext are not retained.
     */
    void retire() {
        retired = true;
        interceptor = null;
        pinningInterceptor = null;
    }

    @Override
    public void apply(OkHttpClient.Builder builder) {
        // apply the wrapped builder first if it exists; its failures propagate, since a
        // partially configured builder must not proceed
        applyCustomClientBuilder(wrappedBuilder, builder);

        if ((builder == null) || retired)
            return;
        Interceptor current = interceptor;
        ApproovPinningInterceptor currentPinning = pinningInterceptor;
        if ((current == null) || (currentPinning == null))
            return;

        // Replace both Approov layers, including misplaced or stale copies left by another
        // factory/hook. Preserve every third-party interceptor and its relative order.
        builder.interceptors().removeIf(ApproovClientBuilder::isApproovInterceptor);
        builder.networkInterceptors().removeIf(ApproovClientBuilder::isApproovInterceptor);
        builder.addInterceptor(current);
        builder.addNetworkInterceptor(currentPinning);

        // Pinning is enforced on each network exchange. Clear the built-in pinner so stale
        // Approov pins cannot reject a connection before our network interceptor runs.
        // Approov continues to own the policy: customer pins are not merged or retained.
        builder.certificatePinner(CertificatePinner.DEFAULT);
    }

    static boolean isApproovInterceptor(Interceptor interceptor) {
        return interceptor instanceof ApproovInterceptor || interceptor instanceof ApproovPinningInterceptor;
    }

    /**
     * Invokes an RN callback through its public interface, so non-public implementations
     * (including another SDK's anonymous classes and lambdas) work as they do in RN. The nested
     * NetworkingModule.CustomClientBuilder extends the top-level interface, so one cast covers
     * both. Callback exceptions propagate: callers must not continue with a partially configured
     * builder, and diagnostics reject if the callback fails.
     */
    static void applyCustomClientBuilder(Object callback, OkHttpClient.Builder builder) {
        if (callback == null)
            return;
        ((com.facebook.react.modules.network.CustomClientBuilder) callback).apply(builder);
    }

    // Package-private accessor for tests: the interceptor this builder installs.
    Interceptor getInterceptor() {
        return interceptor;
    }

    // Package-private: the builder this one wraps (another SDK's, or a previous Approov
    // builder), or null.
    Object getWrappedBuilder() {
        return wrappedBuilder;
    }
}
