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

import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.Connection;
import okhttp3.Handshake;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import com.criticalblue.approovsdk.Approov;

/**
 * A NetworkInterceptor that performs Approov certificate pinning at TLS connection time.
 *
 * Unlike the previous approach (which used OkHttp's CertificatePinner configured at
 * client-build time), this interceptor reads the current pins from Approov.getPins()
 * directly during each request's TLS handshake. This eliminates the need to call
 * Approov.getPins() eagerly during initialize(), which could block the JS Promise
 * while waiting on a network fetch if no dynamic configuration was available.
 *
 * This interceptor is registered as a NetworkInterceptor (not an application interceptor)
 * so it runs after the TLS handshake has completed and the peer certificates are available
 * via chain.connection().handshake(). At that point, the ApproovInterceptor (application
 * layer) has already completed a token fetch, meaning Approov.getPins() is guaranteed to
 * have fresh, non-blocking results.
 *
 * A handshake cache (bounded to MAX_CACHED_HANDSHAKES entries) avoids redundant pin checks
 * on repeated connections to the same host. The cache is invalidated when the Approov
 * dynamic configuration changes (signalled by ApproovInterceptor calling clearHandshakeCache()).
 */
public class ApproovPinningInterceptor implements Interceptor {
    // logging tag
    private static final String TAG = "ApproovService";

    // maximum number of handshakes to cache to avoid redundant pin checks across
    // concurrent connections without causing a memory leak on long-running apps
    private static final int MAX_CACHED_HANDSHAKES = 10;

    // underlying ApproovService for checking initialisation and SDK state
    private final ApproovService approovService;

    // cache of TLS handshakes that have already passed pin verification
    private final LinkedHashSet<Handshake> knownValidHandshakes;

    /**
     * Constructs a new pinning interceptor.
     *
     * @param approovService the ApproovService used to check initialisation state
     */
    public ApproovPinningInterceptor(ApproovService approovService) {
        this.approovService = approovService;
        this.knownValidHandshakes = new LinkedHashSet<>();
    }

    /**
     * Clears the handshake cache, forcing the next request to re-verify pin validity.
     * Should be called when the Approov dynamic configuration has changed (e.g. after
     * ApproovInterceptor detects isConfigChanged() or isForceApplyPins()).
     */
    public synchronized void clearHandshakeCache() {
        knownValidHandshakes.clear();
        Log.d(TAG, "pinning: handshake cache cleared");
    }

    /**
     * Returns true if the given handshake has already been verified against the current pins.
     */
    private synchronized boolean isKnownValidHandshake(Handshake handshake) {
        return knownValidHandshakes.contains(handshake);
    }

    /**
     * Records a handshake as having passed pin verification. Evicts the oldest entry if
     * the cache has reached its maximum size.
     */
    private synchronized void addKnownValidHandshake(Handshake handshake) {
        while (knownValidHandshakes.size() >= MAX_CACHED_HANDSHAKES) {
            Iterator<Handshake> it = knownValidHandshakes.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        knownValidHandshakes.add(handshake);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        // skip if Approov is not initialised or is disabled
        if (!approovService.isInitialized() || !approovService.isApproovEnabled())
            return chain.proceed(chain.request());

        // consult the mutator to decide whether pinning should be applied to this
        // request — allows custom mutators to exempt specific URLs from pin checking
        if (!ApproovService.getServiceMutator().handlePinningShouldProcessRequest(chain.request()))
            return chain.proceed(chain.request());

        // obtain the TLS connection details — only available in a NetworkInterceptor
        String host = chain.request().url().host();
        Connection connection = chain.connection();
        Handshake handshake = (connection != null) ? connection.handshake() : null;
        if (handshake == null)
            throw new IOException("Approov pinning: no TLS connection information available");

        if (!isKnownValidHandshake(handshake)) {
            // Read the current Approov pins. At this point in the request lifecycle,
            // ApproovInterceptor (application layer) has already completed a token fetch,
            // so Approov.getPins() returns immediately from the SDK cache — no blocking.
            Map<String, List<String>> allPins = Approov.getPins("public-key-sha256");
            List<String> expectedPins = allPins.get(host);

            if (expectedPins == null) {
                // Host not in the pins map — not a protected domain, accept connection
                Log.d(TAG, "pinning: " + host + " not in pin map, accepting");
                addKnownValidHandshake(handshake);
                return chain.proceed(chain.request());
            }

            if (expectedPins.isEmpty()) {
                // Empty domain-specific list — fall back to managed trust roots
                expectedPins = allPins.get("*");
                if (expectedPins == null || expectedPins.isEmpty()) {
                    // No managed trust roots either — accept any (system trust store applies)
                    Log.d(TAG, "pinning: " + host + " using managed trust roots, accepting");
                    addKnownValidHandshake(handshake);
                    return chain.proceed(chain.request());
                }
            }

            // Verify at least one peer certificate matches a configured pin
            List<Certificate> peerCerts = handshake.peerCertificates();
            boolean pinMatched = false;
            outer:
            for (Certificate cert : peerCerts) {
                for (String pin : expectedPins) {
                    if (matchesSPKIPin(cert, pin)) {
                        pinMatched = true;
                        break outer;
                    }
                }
            }

            if (!pinMatched) {
                Log.d(TAG, "pinning: failure for " + host);
                Socket socket = connection.socket();
                if (socket != null)
                    socket.close();
                throw new SSLPeerUnverifiedException(
                        "Approov pinning: certificate pin mismatch for " + host);
            }

            addKnownValidHandshake(handshake);
            Log.d(TAG, "pinning: verified for " + host);
        }

        return chain.proceed(chain.request());
    }

    /**
     * Returns true if the certificate's SPKI SHA-256 digest matches the given
     * base64-encoded pin value (as returned by Approov.getPins("public-key-sha256")).
     *
     * @param cert     the peer certificate to check
     * @param pinBase64 the expected SHA-256 SPKI digest, base64-encoded
     * @return true if the pin matches
     */
    private static boolean matchesSPKIPin(Certificate cert, String pinBase64) {
        try {
            byte[] spki = cert.getPublicKey().getEncoded();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(spki);
            String actual = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
            return pinBase64.equals(actual);
        } catch (Exception e) {
            return false;
        }
    }
}
