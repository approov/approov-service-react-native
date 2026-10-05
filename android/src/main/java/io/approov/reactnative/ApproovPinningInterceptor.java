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
import okhttp3.Protocol;
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

    // Builds the pins for the service's current state and publishes them. Callers hold the
    // service monitor (ApproovService.rebuildPins and initialize), which orders rebuilds. A
    // failed build leaves the previous pin set in place and propagates to the caller.
    void rebuildPins(ApproovService service) {
        certificatePinner = ApproovCertificatePinner.build(service.isApproovEnabled());
    }

    // Installs a pin set built in advance, so initialization can publish pins and state together.
    void installPins(CertificatePinner pins) {
        certificatePinner = pins;
    }

    CertificatePinner getCertificatePinner() {
        return certificatePinner;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // A redirect reaches only network interceptors, and OkHttp builds it from the previous
        // request's headers. Approov credentials must never leave the origin (scheme, host and
        // port) they were issued for, so remove them from an attempt to any other origin. The
        // URL is unchanged, as OkHttp requires of network interceptors.
        ApproovIssuedHeaders issued = request.tag(ApproovIssuedHeaders.class);
        if (issued != null)
            request = issued.stripIfOtherOrigin(request);

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
        //
        // The pinner has no certificate chain cleaner of its own, so it checks the chain exactly
        // as Handshake.peerCertificates() returns it. In the OkHttp that React Native 0.76 and
        // later ship (4.9) that is the chain the trust manager verified (cleaned), not the raw
        // list the server sent, so a certificate the server appends to a valid chain cannot
        // satisfy a pin. OkHttp 3.x and 4.0 returned the raw list. ApproovPinningChainTest
        // covers this.
        CertificatePinner pins = certificatePinner;
        try {
            pins.check(request.url().host(), handshake.peerCertificates());
        } catch (SSLPeerUnverifiedException failure) {
            // Force a new TLS negotiation after a mismatch, preserving the pinning exception. An
            // HTTP/2 connection may carry streams for other hosts that passed their own check,
            // so it is left open: every exchange on it is checked again anyway.
            if (connection.protocol() != Protocol.HTTP_2) {
                try {
                    connection.socket().close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
        return chain.proceed(request);
    }
}
