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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
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
    // As in approov-service-okhttp, cache successful checks for concurrent/reused connections
    // while bounding retention in long-running apps. Each pin snapshot owns its own cache.
    private static final int MAX_CACHED_HANDSHAKES = 10;
    private volatile PinSnapshot snapshot = new PinSnapshot(CertificatePinner.DEFAULT);

    private static final class VerifiedHandshake {
        final String host;
        final Handshake handshake;

        VerifiedHandshake(String host, Handshake handshake) {
            this.host = host;
            this.handshake = handshake;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof VerifiedHandshake))
                return false;
            VerifiedHandshake that = (VerifiedHandshake) other;
            return host.equals(that.host) && handshake.equals(that.handshake);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, handshake);
        }
    }

    private static final class PinSnapshot {
        final CertificatePinner pinner;
        final LinkedHashSet<VerifiedHandshake> knownValidHandshakes = new LinkedHashSet<>();

        PinSnapshot(CertificatePinner pinner) {
            this.pinner = pinner;
        }

        synchronized void check(String host, Handshake handshake) throws SSLPeerUnverifiedException {
            VerifiedHandshake key = new VerifiedHandshake(host, handshake);
            if (knownValidHandshakes.contains(key))
                return;
            pinner.check(host, handshake.peerCertificates());
            while (knownValidHandshakes.size() >= MAX_CACHED_HANDSHAKES) {
                Iterator<VerifiedHandshake> oldest = knownValidHandshakes.iterator();
                oldest.next();
                oldest.remove();
            }
            knownValidHandshakes.add(key);
        }
    }

    // Serialize rebuilds so an earlier fetch cannot overwrite a later update. A failed fetch
    // leaves the previous complete snapshot intact and propagates to the caller.
    synchronized void rebuildPins(ApproovService service) {
        snapshot = new PinSnapshot(ApproovCertificatePinner.build(service));
    }

    CertificatePinner getCertificatePinner() {
        return snapshot.pinner;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (!ApproovService.getServiceMutator().handlePinningShouldProcessRequest(request))
            return chain.proceed(request);
        if (!request.url().isHttps())
            return chain.proceed(request);

        Connection connection = chain.connection();
        Handshake handshake = connection == null ? null : connection.handshake();
        if (handshake == null)
            throw new ApproovNetworkException("network interceptor has no TLS handshake for an HTTPS request");

        // Select one complete pin/cache generation. A concurrent rebuild replaces the entire
        // snapshot, so an in-flight check can never populate the new generation's cache.
        PinSnapshot pins = snapshot;
        try {
            pins.check(request.url().host(), handshake);
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
