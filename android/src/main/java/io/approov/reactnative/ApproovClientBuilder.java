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

import java.lang.reflect.Method;

import okhttp3.CertificatePinner;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

// ApproovClientBuilder is a custom client building for OkHttp to add Approov protection, including dynamic pinning
public class ApproovClientBuilder implements CustomClientBuilder, ApproovService.PinChangeListener {
    // underlying ApproovService that is wrapping the SDK; null once retired
    private volatile ApproovService approovService;

    // interceptor for adding Approov tokens or substituting headers and/or query
    // parameters; null once retired
    private volatile Interceptor interceptor;

    // current certificate pinner, owned by this builder unless pinnerSource is set
    private volatile CertificatePinner pinner;

    // the builder whose pinner this one shares (the custom-client-builder hook shares the
    // factory path's, so one pinner and one pin-change listener exist per service), or null
    private volatile ApproovClientBuilder pinnerSource;

    // prior client builder that might have been set by another SDK (e.g. New Relic). Held as
    // Object because React Native types this hook as the nested
    // NetworkingModule.CustomClientBuilder on some versions and the top-level
    // com.facebook.react.modules.network.CustomClientBuilder on others.
    private final Object wrappedBuilder;

    // set when a later Approov registration supersedes this builder: it then applies only the
    // builder it wraps, adds no Approov protection, and holds no reference to the old service
    private volatile boolean retired;

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
        this(approovService, wrappedBuilder, false, null);
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
        this(approovService, wrappedBuilder, ephemeral, null);
    }

    /**
     * The single construction path. A builder given a {@code pinnerSource} owns no pinner and
     * registers no listener; it reads the source's current pinner when applied.
     *
     * @param approovService is the ApproovService being used
     * @param wrappedBuilder is the previously registered builder of any CustomClientBuilder
     *                       type, or null
     * @param ephemeral      if true, skip PinChangeListener registration
     * @param pinnerSource   the builder whose pinner to share, or null to own one
     */
    ApproovClientBuilder(ApproovService approovService, Object wrappedBuilder, boolean ephemeral,
            ApproovClientBuilder pinnerSource) {
        this.approovService = approovService;
        this.wrappedBuilder = wrappedBuilder;
        this.pinnerSource = pinnerSource;

        // set the interceptor
        interceptor = new ApproovInterceptor(approovService);

        // a builder sharing another's pinner neither builds nor listens; otherwise set the
        // initial pinner and, on long-lived builders only, register for pin changes (ephemeral
        // builders, used by fetchWithApproov, build a fresh pinner on every call so listening
        // would just leak references)
        if (pinnerSource == null) {
            pinner = ApproovCertificatePinner.build(approovService);
            if (!ephemeral)
                approovService.addPinChangeListener(this);
        }
    }

    /**
     * Builds the builder registered as the NetworkingModule's custom client builder. It wraps the
     * previously registered builder of any CustomClientBuilder type, so registering Approov
     * preserves rather than replaces the other SDK's hook, and it shares the factory path's
     * pinner so one pinner and one listener exist per service.
     *
     * @param approovService  is the ApproovService being used
     * @param previousBuilder is the previously registered builder, or null
     * @param pinnerSource    is the factory path's builder whose pinner is shared
     */
    static ApproovClientBuilder wrapping(ApproovService approovService, Object previousBuilder,
            ApproovClientBuilder pinnerSource) {
        return new ApproovClientBuilder(approovService, previousBuilder, true, pinnerSource);
    }

    /**
     * Handles a change to the Approov pins by creating a new pinner.
     */
    public void approovPinsUpdated() {
        if (retired || (pinnerSource != null))
            return;
        ApproovService service = approovService;
        if (service != null)
            pinner = ApproovCertificatePinner.build(service);
    }

    /**
     * The pinner currently in force for this builder, its own or its source's.
     */
    CertificatePinner currentPinner() {
        ApproovClientBuilder source = pinnerSource;
        return (source != null) ? source.currentPinner() : pinner;
    }

    /**
     * Supersedes this builder after a later Approov registration. React Native (or a third
     * party's wrapper) may keep applying it, so it continues to apply the builder it wraps but
     * adds no Approov protection, and it drops every reference to the old service so that the
     * service and its ReactContext are not retained.
     */
    void retire() {
        retired = true;
        approovService = null;
        interceptor = null;
        pinner = null;
        pinnerSource = null;
    }

    @Override
    public void apply(OkHttpClient.Builder builder) {
        // apply the wrapped builder first if it exists; its failures propagate, since a
        // partially configured builder must not proceed
        applyCustomClientBuilder(wrappedBuilder, builder);

        if ((builder == null) || retired)
            return;
        Interceptor current = interceptor;
        CertificatePinner approovPinner = currentPinner();
        if ((current == null) || (approovPinner == null))
            return;

        // Remove any ApproovInterceptor already on the builder, then add ours. This
        // covers two cases:
        // - Double-registration on RN < 0.73, where both the OkHttpClientFactory and the
        //   legacy setCustomClientBuilder fire for the same builder; adding the
        //   interceptor twice would cause duplicate token fetches and signatures.
        // - Re-registration when a new ApproovService is built (for example the RN
        //   context is recreated by an Expo OTA reload). The previous factory is wrapped
        //   and runs first, adding a STALE interceptor bound to the old service. A
        //   class-level "already present?" check cannot tell that stale interceptor
        //   apart from our own, so it would skip adding the live one and leave the client
        //   fetching tokens through a dead service. Removing every ApproovInterceptor
        //   first guarantees the client uses THIS service's interceptor.
        builder.interceptors().removeIf(existing -> existing instanceof ApproovInterceptor);
        builder.addInterceptor(current);

        // Approov owns this client's pinning policy. Replace the existing pinner so
        // customer pins and pins removed by an Approov update are not carried forward.
        builder.certificatePinner(approovPinner);
    }

    // the OkHttp accessor for a builder's current pinner, resolved once; null when this OkHttp
    // build has none (older OkHttp), in which case callers fall back
    private static volatile Method builderPinnerGetter;
    private static volatile boolean builderPinnerGetterResolved;

    /**
     * Reads the pinner currently set on a builder without building a client. OkHttp exposes it
     * to the JVM as the accessor of its internal Kotlin property.
     *
     * @param builder the builder to read
     * @return the current pinner, or null if this OkHttp build exposes no accessor
     */
    static CertificatePinner pinnerOf(OkHttpClient.Builder builder) {
        if (builder == null)
            return null;
        Method getter = builderPinnerGetter;
        if ((getter == null) && !builderPinnerGetterResolved) {
            try {
                getter = OkHttpClient.Builder.class.getMethod("getCertificatePinner$okhttp");
            } catch (NoSuchMethodException e) {
                getter = null;
            }
            builderPinnerGetter = getter;
            builderPinnerGetterResolved = true;
        }
        if (getter == null)
            return null;
        try {
            return (CertificatePinner) getter.invoke(builder);
        } catch (Exception e) {
            return null;
        }
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
