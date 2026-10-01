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

import java.io.IOException;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.Handshake;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Enforces Approov pins against the live TLS connection on every network exchange, including
 * redirects and pooled connections. Adapted from feature/4.0.0's network interceptor (88d023b).
 * All clients of a service share this instance. Construction performs no SDK calls and retains
 * no service/context. Pin sets are immutable and published atomically on initialization or
 * a configuration update; existing clients see the replacement on their next exchange.
 */
public final class ApproovPinningInterceptor implements Interceptor {
    // Share immutable pins across clients, but do not cache handshake approvals. Each HTTPS
    // exchange validates its hostname and certificate chain against the current pin set.
    private volatile CertificatePinner certificatePinner = CertificatePinner.DEFAULT;

    // Serialize rebuilds so an earlier fetch cannot overwrite a later update. A failed fetch
    // leaves the previous complete snapshot intact and propagates to the caller.
    // Lock order is service monitor, then this monitor, as in initialize(), which installs pins
    // while holding the service monitor. Service state is read before taking this monitor so a
    // refresh never waits for the service while holding it, which would deadlock with that commit.
    void rebuildPins(ApproovService service) {
        boolean approovEnabled = service.isApproovEnabled();
        synchronized (this) {
            certificatePinner = ApproovCertificatePinner.build(approovEnabled);
        }
    }

    // Installs a pin set built in advance, so initialization can publish pins and state together.
    synchronized void installPins(CertificatePinner pins) {
        certificatePinner = pins;
    }

    CertificatePinner getCertificatePinner() {
        return certificatePinner;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // A redirect or retry reaches only network interceptors. Reprocess an attempt OkHttp
        // rebuilt from an Approov-processed request, so it carries nothing issued for the
        // previous URL, before deciding how to pin it.
        ApproovAppliedRequest applied = request.tag(ApproovAppliedRequest.class);
        if (applied != null)
            request = applied.reclassifyIfRebuilt(request);

        if (!ApproovService.getServiceMutator().handlePinningShouldProcessRequest(request))
            return chain.proceed(request);
        if (!request.url().isHttps())
            return chain.proceed(request);

        Connection connection = chain.connection();
        Handshake handshake = connection == null ? null : connection.handshake();
        if (handshake == null)
            throw new ApproovNetworkException("network interceptor has no TLS handshake for an HTTPS request");

        // Use one complete immutable pin set for this check. A concurrent refresh is visible
        // to subsequent exchanges, including those reusing this same TLS connection.
        CertificatePinner pins = certificatePinner;
        try {
            pins.check(request.url().host(), handshake.peerCertificates());
        } catch (SSLPeerUnverifiedException failure) {
            // Force a new TLS negotiation after a mismatch, preserving the pinning exception.
            try {
                connection.socket().close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        return chain.proceed(request);
    }
}
