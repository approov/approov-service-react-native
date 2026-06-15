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
import java.security.cert.Certificate;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.Handshake;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import com.criticalblue.approovsdk.Approov;

// ApproovPinningInterceptor is an OkHttp network interceptor that enforces Approov dynamic certificate
// pinning by checking the peer-presented certificate chain of the live TLS handshake against the pins
// provided by the Approov SDK. Being a network interceptor (rather than relying on OkHttp's built-in
// CertificatePinner set on the builder) gives access to the actual Connection/Handshake, so pinning is
// performed against the certificates the server actually presented - matching the okhttp and iOS
// service layers for cross-layer parity.
public class ApproovPinningInterceptor implements Interceptor {
    // logging tag
    private final static String TAG = "ApproovService";

    // maximum number of elements that may be held in the handshake cache to allow caching of
    // different concurrent connections but without causing a significant memory leak
    private final static int maxCachedHandshakes = 10;

    // service wrapping the Approov SDK - used to determine if Approov is enabled before querying pins
    // (the interceptor may be constructed before ApproovService.initialize() has completed)
    private ApproovService approovService;

    // the certificate pinner to use for pinning that may be rebuilt if there is a change in the
    // pinning configuration
    private CertificatePinner certificatePinner;

    // set of TLS handshakes that are known to be valid constrained to a size of maxCachedHandshakes
    // to prevent a memory leak for long running apps
    private LinkedHashSet<Handshake> knownValidHandshakes;

    /**
     * Construct a new pinning interceptor. The initial pins are built immediately; if Approov is not
     * yet enabled this yields an empty pinner that is rebuilt (via {@link #buildPins()}) once
     * initialization completes.
     *
     * @param approovService the Approov service being used
     */
    public ApproovPinningInterceptor(ApproovService approovService) {
        this.approovService = approovService;
        knownValidHandshakes = new LinkedHashSet<>();
        buildPins();
    }

    /**
     * Rebuild the pinning configuration from the current Approov public key pins. This is called when
     * the dynamic configuration changes and we need to update the pinning information, and once
     * initialization completes. This forces all known valid handshakes to be cleared.
     *
     * Note: {@code Approov.getPins()} can block, so this must only be invoked off the request hot path
     * (e.g. from the token interceptor on the OkHttp background thread, at construction, or on init).
     */
    synchronized public void buildPins() {
        CertificatePinner.Builder pinBuilder = new CertificatePinner.Builder();
        if (approovService.isApproovEnabled()) {
            // only query pins once Approov is enabled - querying before initialization would fail
            Map<String, List<String>> allPins = Approov.getPins("public-key-sha256");
            for (Map.Entry<String, List<String>> entry : allPins.entrySet()) {
                String domain = entry.getKey();
                if (!domain.equals("*")) {
                    // the * domain is for managed trust roots and should not be added directly
                    List<String> pins = entry.getValue();

                    // if there are no pins then we try and use any managed trust roots
                    if (pins.isEmpty() && (allPins.get("*") != null))
                        pins = allPins.get("*");

                    // add the required pins for the domain
                    for (String pin : pins)
                        pinBuilder = pinBuilder.add(domain, "sha256/" + pin);

                    // log the number of pins applied
                    Log.d(TAG, "applied " + String.valueOf(pins.size()) + " pins to host domain " + domain);
                }
            }
        }
        certificatePinner = pinBuilder.build();
        knownValidHandshakes.clear();
    }

    /**
     * Gets the current CertificatePinner for checking peer certificates on a TLS handshake.
     *
     * @return the current CertificatePinner
     */
    synchronized CertificatePinner getCertificatePinner() {
        return certificatePinner;
    }

    /**
     * Determines if the given handshake is known to be valid, supporting different TLS negotiations on
     * different domains as required.
     *
     * @param handshake to be checked
     * @return true if the handshake is known valid, false otherwise
     */
    synchronized private boolean isValidHandshake(Handshake handshake) {
        return knownValidHandshakes.contains(handshake);
    }

    /**
     * Adds a valid handshake to the cached set, evicting the oldest entry if that would exceed the
     * maximum size.
     *
     * @param handshake to be added as known valid
     */
    synchronized private void addValidHandshake(Handshake handshake) {
        while (knownValidHandshakes.size() >= maxCachedHandshakes) {
            Iterator<Handshake> it = knownValidHandshakes.iterator();
            if (it.hasNext()) { // can't really fail, but this keeps it safe
                it.next();
                it.remove();
            }
        }
        knownValidHandshakes.add(handshake);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // first check if we are to proceed with any pinning processing
        if (!ApproovService.getServiceMutator().handlePinningShouldProcessRequest(request)) {
            // we are not to proceed with any pinning processing so just continue
            return chain.proceed(request);
        }

        // obtain the live connection and TLS handshake for the request
        String host = request.url().host();
        Connection connection = chain.connection();
        Handshake handshake = (connection != null) ? connection.handshake() : null;
        if (handshake == null) {
            // there is no TLS handshake to verify. For cleartext (http) requests there is nothing to
            // pin - matching OkHttp's built-in CertificatePinner, which only acts on https - so we
            // proceed. For an https request a missing handshake is anomalous, so we fail closed.
            if (request.url().isHttps())
                throw new ApproovNetworkException("network interceptor has no TLS handshake for an HTTPS request");
            return chain.proceed(request);
        }

        if (!isValidHandshake(handshake)) {
            // if we haven't seen this handshake and pins combination before then we need to check it
            // against the certificates the peer actually presented
            List<Certificate> certs = handshake.peerCertificates();
            try {
                getCertificatePinner().check(host, certs);
            } catch (SSLPeerUnverifiedException e) {
                // if a certificate pinning error is detected then close the socket to force the next
                // request to redo the TLS negotiation
                Log.d(TAG, "pinning failure for " + host + ": " + e.toString());
                connection.socket().close();
                throw e;
            }

            // pins were valid for the handshake so cache it
            addValidHandshake(handshake);
        }
        return chain.proceed(request);
    }
}
