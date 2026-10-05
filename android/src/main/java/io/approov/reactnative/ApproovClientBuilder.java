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
    // interceptor for adding Approov tokens or substituting headers and/or query parameters;
    // null once retired
    private volatile Interceptor interceptor;

    // the process-wide pinning interceptor; null once retired
    private volatile ApproovPinningInterceptor pinningInterceptor;

    // prior client builder that might have been set by another SDK (e.g. New Relic). React
    // Native types the hook as the nested NetworkingModule.CustomClientBuilder on some versions
    // and the top-level interface on others; the nested type extends the top-level one.
    private final com.facebook.react.modules.network.CustomClientBuilder wrappedBuilder;

    /** Creates a builder that adds Approov protection after applying the wrapped builder. */
    public ApproovClientBuilder(ApproovService approovService, CustomClientBuilder wrappedBuilder) {
        this(approovService, (com.facebook.react.modules.network.CustomClientBuilder) wrappedBuilder);
    }

    /**
     * Same as {@link #ApproovClientBuilder(ApproovService, CustomClientBuilder)}.
     *
     * @deprecated the ephemeral flag has no effect: no builder registers listeners or fetches pins
     */
    @Deprecated
    public ApproovClientBuilder(ApproovService approovService, CustomClientBuilder wrappedBuilder, boolean ephemeral) {
        this(approovService, wrappedBuilder);
    }

    private ApproovClientBuilder(ApproovService approovService,
            com.facebook.react.modules.network.CustomClientBuilder wrappedBuilder) {
        this.wrappedBuilder = wrappedBuilder;
        interceptor = new ApproovInterceptor(approovService);
        pinningInterceptor = approovService.getPinningInterceptor();
    }

    /** Wraps the vendor's per-request callback, sharing the process-wide pinning interceptor. */
    static ApproovClientBuilder wrapping(ApproovService approovService,
            com.facebook.react.modules.network.CustomClientBuilder previousBuilder) {
        return new ApproovClientBuilder(approovService, previousBuilder);
    }

    /**
     * Supersedes this builder after a later Approov registration. React Native (or a third
     * party's wrapper) may keep applying it, so it continues to apply the builder it wraps but
     * adds no Approov protection.
     */
    void retire() {
        interceptor = null;
        pinningInterceptor = null;
    }

    @Override
    public void apply(OkHttpClient.Builder builder) {
        // apply the wrapped builder first if it exists; its failures propagate, since a
        // partially configured builder must not proceed
        applyCustomClientBuilder(wrappedBuilder, builder);

        if (builder == null)
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
        // First network interceptor, so that other network interceptors (loggers, inspectors)
        // only ever see a redirect after Approov removed its headers from it.
        builder.networkInterceptors().add(0, currentPinning);

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
     * (including another SDK's anonymous classes and lambdas) work as they do in RN. Callback
     * exceptions propagate: callers must not continue with a partially configured builder, and
     * diagnostics reject if the callback fails.
     */
    static void applyCustomClientBuilder(com.facebook.react.modules.network.CustomClientBuilder callback,
            OkHttpClient.Builder builder) {
        if (callback != null)
            callback.apply(builder);
    }

    // Package-private accessor for tests: the interceptor this builder installs.
    Interceptor getInterceptor() {
        return interceptor;
    }

    // Package-private: the builder this one wraps (another SDK's, or a previous Approov
    // builder), or null.
    com.facebook.react.modules.network.CustomClientBuilder getWrappedBuilder() {
        return wrappedBuilder;
    }
}
