//
// MIT License
// 
// Copyright (c) 2016-present, Approov Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
// (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
// publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
// subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
// 
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
// ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
// THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package io.approov.reactnative;

import android.util.Base64;
import android.util.Log;

import io.approov.internal.reactnative.bouncycastle.asn1.ASN1InputStream;
import io.approov.internal.reactnative.bouncycastle.asn1.ASN1Integer;
import io.approov.internal.reactnative.bouncycastle.asn1.ASN1Sequence;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.approov.util.http.sfv.ByteSequenceItem;
import io.approov.util.http.sfv.Dictionary;
import io.approov.util.sig.ComponentProvider;
import io.approov.util.sig.SignatureBaseBuilder;
import io.approov.util.sig.SignatureParameters;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.ByteString;

/**
 * Provides a base implementation of message signing for Approov when using
 * OkHttp requests. This class provides mechanisms to configure and apply
 * message signatures to HTTP requests based on specified parameters and
 * algorithms.
 */
public class ApproovDefaultMessageSigning implements ApproovServiceMutator {
    // logging tag
    private static final String TAG = "ApproovMsgSign";

    /**
     * Constant for the SHA-256 digest algorithm (used for body digests).
     */
    public static final String DIGEST_SHA256 = "sha-256";

    /**
     * Constant for the SHA-512 digest algorithm (used for body digests).
     */
    public static final String DIGEST_SHA512 = "sha-512";

    /**
     * Constant for the ECDSA P-256 with SHA-256 algorithm (used when signing with
     * install private key).
     */
    public final static String ALG_ES256 = "ecdsa-p256-sha256";

    /**
     * Constant for the HMAC with SHA-256 algorithm (used when signing with the
     * account signing key).
     */
    public final static String ALG_HS256 = "hmac-sha256";

    /**
     * The default factory for generating signature parameters.
     */
    protected SignatureParametersFactory defaultFactory;

    /**
     * A map of host-specific factories for generating signature parameters.
     */
    protected final Map<String, SignatureParametersFactory> hostFactories;

    /**
     * Constructs an instance of {@code ApproovDefaultMessageSigning}.
     */
    public ApproovDefaultMessageSigning() {
        hostFactories = new HashMap<>();
    }

    @Override
    public String toString() {
        return "ApproovDefaultMessageSigning";
    }

    /**
     * Sets the default factory for generating signature parameters.
     *
     * @param factory The factory to set as the default.
     * @return The current instance for method chaining.
     */
    public ApproovDefaultMessageSigning setDefaultFactory(SignatureParametersFactory factory) {
        this.defaultFactory = factory;
        return this;
    }

    /**
     * Associates a specific host with a factory for generating signature
     * parameters.
     *
     * @param hostName The host name.
     * @param factory  The factory to associate with the host.
     * @return The current instance for method chaining.
     */
    public ApproovDefaultMessageSigning putHostFactory(String hostName, SignatureParametersFactory factory) {
        this.hostFactories.put(hostName, factory);
        return this;
    }

    /**
     * Builds the signature parameters for a given request.
     *
     * @param provider The component provider for the request.
     * @param changes  The request mutations to apply.
     * @return The generated {@link SignatureParameters}, or {@code null} if no
     *         factory is available.
     */
    protected SignatureParameters buildSignatureParameters(OkHttpComponentProvider provider,
            ApproovRequestMutations changes) {
        SignatureParametersFactory factory = hostFactories.get(provider.getAuthority());
        if (factory == null) {
            factory = defaultFactory;
            if (factory == null) {
                return null;
            }
        }
        return factory.buildSignatureParameters(provider, changes);
    }

    /**
     * Converts one part, encoded as an ASN1Integer, of an ASN.1 DER encoded ES256
     * signature to a byte array of
     * exactly 32 bytes. Throws IllegalArgumentException if this is not possible.
     *
     * @param bytesAsASN1Integer The ASN1Integer to convert.
     * @return A byte array of length 32, containing the raw bytes of the signature
     *         part.
     * @throws IllegalArgumentException if the ASN1Integer is not representing a 32
     *                                  byte array.
     */
    private static byte[] to32ByteArray(ASN1Integer bytesAsASN1Integer) {
        BigInteger bytesAsBigInteger = bytesAsASN1Integer.getValue();
        byte[] bytes = bytesAsBigInteger.toByteArray();
        byte[] bytes32;
        if (bytes.length < 32) {
            bytes32 = new byte[32];
            System.arraycopy(bytes, 0, bytes32, 32 - bytes.length, bytes.length);
        } else if (bytes.length == 32) {
            bytes32 = bytes;
        } else if (bytes.length == 33 && bytes[0] == 0) {
            bytes32 = new byte[32];
            System.arraycopy(bytes, 1, bytes32, 0, 32);
        } else {
            throw new IllegalArgumentException("Not an ASN.1 DER ES256 signature part");
        }
        return bytes32;
    }

    /**
     * Obtains the install-scoped message signature for the supplied signature base.
     * This hook exists so tests can override just the SDK lookup while still
     * exercising the real signing logic.
     *
     * @param message The signature base to sign.
     * @return The base64-encoded ASN.1 DER install signature.
     * @throws ApproovException If the SDK cannot provide a signature.
     */
    protected String getInstallMessageSignature(String message) throws ApproovException {
        return ApproovService.getInstallMessageSignature(message);
    }

    /**
     * Obtains the account-scoped message signature for the supplied signature base.
     * This hook exists so tests can override just the SDK lookup while still
     * exercising the real signing logic.
     *
     * @param message The signature base to sign.
     * @return The base64-encoded account signature.
     * @throws ApproovException If the SDK cannot provide a signature.
     */
    protected String getAccountMessageSignature(String message) throws ApproovException {
        return ApproovService.getAccountMessageSignature(message);
    }

    /**
     * Decodes the supplied base64 signature payload. This hook exists so tests can
     * supply a JVM-friendly decoder while production continues to use the Android
     * platform implementation.
     *
     * @param base64 The base64-encoded signature payload.
     * @return The decoded bytes.
     */
    protected byte[] decodeBase64(String base64) {
        return Base64.decode(base64, Base64.NO_WRAP);
    }

    /**
     * Logs a message-signing failure that should not abort the request.
     *
     * @param request The original request to forward unsigned.
     * @param reason  The reason signing was skipped.
     * @param cause   Optional failure cause.
     * @return The original request.
     */
    private Request proceedUnsigned(Request request, String reason, Throwable cause) {
        if (cause == null) {
            Log.e(TAG, reason);
        } else {
            Log.e(TAG, reason, cause);
        }
        return request;
    }

    /**
     * Adds message signature to requests that have passed through the Approov
     * interceptor. The request is only modified to include message signature
     * headers if an ApproovToken has been added to the request and if there is
     * a defined SignatureParameter factory for the request.
     *
     * @param service The ApproovService instance.
     * @param request The original HTTP request.
     * @param changes The request mutations that were applied by the Approov
     *                interceptor.
     * @return The processed HTTP request with the signature headers added.
     * @throws ApproovException If an error occurs during processing.
     */
    @Override
    public Request handleInterceptorProcessedRequest(ApproovService service, Request request,
            ApproovRequestMutations changes)
            throws ApproovException {
        return processedRequest(request, changes);
    }

    /**
     * @deprecated Use ApproovServiceMutator.handleInterceptorProcessedRequest
     *             instead.
     *
     *             Currently the method is implemented to maintain backwards
     *             compatibility. A future release will move the implementation
     *             to the ApproovServiceMutator.handleInterceptorProcessedRequest
     *             method.
     */
    @Deprecated
    public Request processedRequest(Request request, ApproovRequestMutations changes) throws ApproovException {
        if (changes == null || changes.getTokenHeaderKey() == null) {
            // the request doesn't have an Approov token, so we don't need to sign it
            return request;
        }
        // generate and add a message signature
        OkHttpComponentProvider provider = new OkHttpComponentProvider(request);
        SignatureParameters params = buildSignatureParameters(provider, changes);
        if (params == null) {
            // No sig to be added to the request; return the original request.
            return request;
        }

        // Apply the params to get the message
        SignatureBaseBuilder baseBuilder = new SignatureBaseBuilder(params, provider);
        String message;
        try {
            message = baseBuilder.createSignatureBase();
        } catch (RuntimeException e) {
            return proceedUnsigned(request, "Failed to create signature base - skipping message signing", e);
        }
        // WARNING never log the message as it contains an Approov token which provides
        // access to your API.

        // Generate the signature
        String sigId;
        byte[] signature;
        switch (params.getAlg()) {
            case ALG_ES256: {
                sigId = "install";
                String base64;
                try {
                    base64 = getInstallMessageSignature(message);
                } catch (ApproovException e) {
                    return proceedUnsigned(request,
                            "Failed to get InstallMessageSignature - skipping message signing", e);
                }
                if (base64 == null || base64.isEmpty()) {
                    return proceedUnsigned(request,
                            "InstallMessageSignature is unavailable - skipping message signing", null);
                }
                try {
                    signature = decodeBase64(base64);
                } catch (RuntimeException e) {
                    return proceedUnsigned(request,
                            "Failed to decode InstallMessageSignature base64 - skipping message signing", e);
                }
                // decode the signature from ASN.1 DER format
                try (ASN1InputStream asn1InputStream = new ASN1InputStream(signature)) {
                    ASN1Sequence sequence = (ASN1Sequence) asn1InputStream.readObject();
                    if (sequence instanceof ASN1Sequence) {
                        // Combine r and s into a single byte array
                        byte[] rBytes = to32ByteArray((ASN1Integer) sequence.getObjectAt(0));
                        byte[] sBytes = to32ByteArray((ASN1Integer) sequence.getObjectAt(1));
                        signature = new byte[rBytes.length + sBytes.length];
                        System.arraycopy(rBytes, 0, signature, 0, rBytes.length);
                        System.arraycopy(sBytes, 0, signature, rBytes.length, sBytes.length);
                    } else {
                        throw new IllegalStateException("Not an ASN1Sequence");
                    }
                } catch (Exception e) {
                    return proceedUnsigned(request,
                            "Failed to decode ASN.1 DER ES256 signature - skipping message signing", e);
                }
                break;
            }
            case ALG_HS256: {
                sigId = "account";
                String base64;
                try {
                    base64 = getAccountMessageSignature(message);
                } catch (ApproovException e) {
                    return proceedUnsigned(request,
                            "Failed to get AccountMessageSignature - skipping message signing", e);
                }
                if (base64 == null || base64.isEmpty()) {
                    return proceedUnsigned(request,
                            "AccountMessageSignature is unavailable - skipping message signing", null);
                }
                try {
                    signature = decodeBase64(base64);
                } catch (RuntimeException e) {
                    return proceedUnsigned(request,
                            "Failed to decode AccountMessageSignature base64 - skipping message signing", e);
                }
                break;
            }
            default:
                throw new IllegalStateException("Unsupported algorithm identifier: " + params.getAlg());
        }

        String sigHeader;
        String sigInputHeader;
        try {
            sigHeader = Dictionary.valueOf(Map.of(
                    sigId, ByteSequenceItem.valueOf(signature))).serialize();
            sigInputHeader = Dictionary.valueOf(Map.of(
                    sigId, params.toComponentValue())).serialize();
        } catch (RuntimeException e) {
            return proceedUnsigned(request, "Failed to serialize signature headers - skipping message signing", e);
        }

        // Debugging - log the message and signature-related headers
        // WARNING never log the message in production code as it contains the Approov
        // token which allows API access
        // Log.d(TAG, "Message Value - Signature Message: " + message);
        // Log.d(TAG, "Message Header - Signature: " + sigHeader);
        // Log.d(TAG, "Message Header Signature-Input: " + sigInputHeader);

        // Update the request from the one held by the component provider as the
        // signature builder
        // may have modified it.
        Request.Builder signedBuilder = provider.getRequest().newBuilder()
                .header("Signature", sigHeader)
                .header("Signature-Input", sigInputHeader);
        if (params.isDebugMode()) {
            try {
                MessageDigest digestBuilder = MessageDigest.getInstance("SHA-256");
                digestBuilder.reset();
                byte[] digest = digestBuilder.digest(message.getBytes(StandardCharsets.UTF_8));
                String digestHeader = Dictionary.valueOf(Map.of(
                        DIGEST_SHA256, ByteSequenceItem.valueOf(digest))).serialize();
                signedBuilder.header("Signature-Base-Digest", digestHeader);
            } catch (RuntimeException | NoSuchAlgorithmException e) {
                Log.e(TAG, "Failed to add debug signature base digest", e);
            }
        } else {
            signedBuilder.removeHeader("Signature-Base-Digest");
        }
        Request signed = signedBuilder.build();

        // WARNING never log the full request as it contains an Approov token which
        // provides access to your API
        // Log.d(TAG, "Request String: " + signed.toString());
        return signed;
    }

    /**
     * Generates a default {@link SignatureParametersFactory} with predefined
     * settings.
     *
     * @return A new instance of {@link SignatureParametersFactory}.
     */
    public static SignatureParametersFactory generateDefaultSignatureParametersFactory() {
        return generateDefaultSignatureParametersFactory(null);
    }

    /**
     * Generates a default {@link SignatureParametersFactory} with optional base
     * parameters.
     *
     * @param baseParametersOverride The base parameters to override, or
     *                               {@code null} to use defaults.
     * @return A new instance of {@link SignatureParametersFactory}.
     */
    public static SignatureParametersFactory generateDefaultSignatureParametersFactory(
            SignatureParameters baseParametersOverride) {
        // default expiry seconds - must encompass worst case request retry
        // time and clock skew
        long defaultExpiresLifetime = 15;
        SignatureParameters baseParameters;
        if (baseParametersOverride != null) {
            baseParameters = baseParametersOverride;
        } else {
            baseParameters = new SignatureParameters()
                    .addComponentIdentifier(ComponentProvider.DC_METHOD)
                    .addComponentIdentifier(ComponentProvider.DC_TARGET_URI);
        }
        return new SignatureParametersFactory()
                .setBaseParameters(baseParameters)
                .setUseInstallMessageSigning()
                .setAddCreated(true)
                .setExpiresLifetime(defaultExpiresLifetime)
                .setAddApproovTokenHeader(true)
                .setAddApproovTraceIDHeader(true)
                .addOptionalHeaders("Authorization", "Content-Length", "Content-Type")
                .setBodyDigestConfig(DIGEST_SHA256, false);
    }

    /**
     * Factory class for creating pre-request {@link SignatureParameters} with
     * configurable settings. Each request passed to the factory builds a new
     * SignatureParameters instance based on the configured settings and
     * specific for the request.
     */
    public static class SignatureParametersFactory {
        // The base parameters that are copied for every new generated message
        // signature.
        protected SignatureParameters baseParameters;
        // The algorithm to use for body digests, or null if no body digest is to be
        // used.
        protected String bodyDigestAlgorithm;
        // True if a body digest is required; body digests cannot be generated for all
        // requests - either because they have no body or because the request body is
        // one shot.
        protected boolean bodyDigestRequired;
        // True to switch to account message signing, false to use install message
        // signing.
        protected boolean useAccountMessageSigning;
        // True to add the "created" timestamp field to the signature parameters.
        protected boolean addCreated;
        // Expiration lifetime in seconds; if >0 the "expires" field is added to the
        // signature parameters.
        protected long expiresLifetime;
        // True to add the Approov token header to the signature parameters. This is
        // strongly advised.
        protected boolean addApproovTokenHeader;
        protected boolean addApproovTraceIDHeader;
        // Lists the headers to add to the message signature if they are present in the
        // request. (Non-optional headers should be added to the base parameters).
        protected List<String> optionalHeaders;

        /**
         * Sets the base parameters for the factory. The base parameters are copied for
         * every new generated message signature.
         *
         * @param baseParameters The base parameters to set.
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setBaseParameters(SignatureParameters baseParameters) {
            this.baseParameters = baseParameters;
            return this;
        }

        /**
         * Configures the body digest settings for the factory. If set, then requests
         * with bodies will have the digest created and added as a header to the request
         * with the header included in the request's message signature.
         *
         * @param bodyDigestAlgorithm The digest algorithm to use, or {@code null} to
         *                            disable.
         * @param required            Whether the body digest is required.
         * @return The current instance for method chaining.
         * @throws IllegalArgumentException If an unsupported algorithm is specified.
         */
        public SignatureParametersFactory setBodyDigestConfig(String bodyDigestAlgorithm, boolean required) {
            if (bodyDigestAlgorithm == null) {
                required = false;
            } else if (!bodyDigestAlgorithm.equals(DIGEST_SHA256)
                    && !bodyDigestAlgorithm.equals(DIGEST_SHA512)) {
                throw new IllegalArgumentException("Unsupported body digest algorithm: " + bodyDigestAlgorithm);
            }
            this.bodyDigestAlgorithm = bodyDigestAlgorithm;
            this.bodyDigestRequired = required;
            return this;
        }

        /**
         * Configures the factory to use install message signing
         *
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setUseInstallMessageSigning() {
            this.useAccountMessageSigning = false;
            return this;
        }

        /**
         * Configures the factory to use account message signing.
         *
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setUseAccountMessageSigning() {
            this.useAccountMessageSigning = true;
            return this;
        }

        /**
         * Sets whether the "created" field should be added to the signature parameters.
         * The created field holds the device's timestamp indicating when the request
         * was
         * created.
         *
         * @param addCreated Whether to add the "created" field.
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setAddCreated(boolean addCreated) {
            this.addCreated = addCreated;
            return this;
        }

        /**
         * Sets the expiration lifetime for the signature parameters. Only a
         * value >0 will cause the expires attribute to be added to the
         * SignatureParameters for a request. The expires attribute holds the
         * timestamp indicating when the message signature will expire. It is
         * equal to the created timestamp (if included) plus the expiration
         * lifetime.
         *
         * @param expiresLifetime The expiration lifetime in seconds, if <=0
         *                        no expiration is added.
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setExpiresLifetime(long expiresLifetime) {
            this.expiresLifetime = expiresLifetime;
            return this;
        }

        /**
         * Sets whether the Approov token header should be added to the signature
         * parameters.
         *
         * @param addApproovTokenHeader Whether to add the Approov token header.
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setAddApproovTokenHeader(boolean addApproovTokenHeader) {
            this.addApproovTokenHeader = addApproovTokenHeader;
            return this;
        }

        /**
         * Sets whether the optional Approov traceID header should be added to the
         * signature
         * parameters.
         *
         * @param addApproovTraceIDHeader Whether to add the Approov traceID header.
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory setAddApproovTraceIDHeader(boolean addApproovTraceIDHeader) {
            this.addApproovTraceIDHeader = addApproovTraceIDHeader;
            return this;
        }

        /**
         * Adds optional headers to the signature parameters. Headers
         * configured as optional are added to the generated
         * SignatureParameters if the target request includes the header
         * otherwise they are ignored.
         *
         * @param headers The headers to add.
         * @return The current instance for method chaining.
         */
        public SignatureParametersFactory addOptionalHeaders(String... headers) {
            if (this.optionalHeaders == null) {
                this.optionalHeaders = new ArrayList<>(Arrays.asList(headers));
            } else {
                this.optionalHeaders.addAll(Arrays.asList(headers));
            }
            return this;
        }

        /**
         * Generates a body digest for the request if possible.
         *
         * @param provider          The component provider for the request.
         * @param requestParameters The signature parameters to update.
         * @return {@code true} if the body digest was successfully generated,
         *         {@code false} otherwise.
         */
        protected boolean generateBodyDigest(OkHttpComponentProvider provider, SignatureParameters requestParameters) {
            RequestBody body = provider.request.body();
            // ignore null bodies, one shot bodies, or bodies of unknown length as these
            // will
            // likely require more specific knowledge
            if (body == null || body.isOneShot()) {
                return false;
            } else {
                try {
                    if (body.contentLength() <= 0) {
                        return false;
                    }
                } catch (IOException e) {
                    // ignore bodies that can't be interrogated
                    return false;
                }
            }

            // grab the body data
            final Buffer buffer = new Buffer();
            try {
                body.writeTo(buffer);
            } catch (IOException e) {
                // ignore bodies that can't be interrogated
                return false;
            }
            // calculate the digest
            ByteString digest;
            switch (bodyDigestAlgorithm) {
                case DIGEST_SHA256:
                    digest = buffer.sha256();
                    break;
                case DIGEST_SHA512:
                    digest = buffer.sha512();
                    break;
                default:
                    return false;
            }
            // generate the header value
            Dictionary digestHeader = Dictionary.valueOf(Map.of(
                    bodyDigestAlgorithm, ByteSequenceItem.valueOf(digest.toByteArray())));

            // add the digest to the request
            Request request = provider.getRequest();
            request = request.newBuilder()
                    .header("Content-Digest", digestHeader.serialize())
                    .build();
            provider.setRequest(request);
            // add the header to the SignatureParameters
            requestParameters.addComponentIdentifier("Content-Digest");
            return true;
        }

        /**
         * Builds the signature parameters for a given request.
         *
         * @param provider The component provider for the request.
         * @param changes  The request mutations to apply.
         * @return The generated {@link SignatureParameters}.
         * @throws IllegalStateException If required parameters cannot be generated.
         */
        protected SignatureParameters buildSignatureParameters(OkHttpComponentProvider provider,
                ApproovRequestMutations changes) {
            SignatureParameters requestParameters = new SignatureParameters(baseParameters);
            if (useAccountMessageSigning) {
                requestParameters.setAlg(ALG_HS256);
            } else {
                requestParameters.setAlg(ALG_ES256);
            }
            if (addCreated || expiresLifetime > 0) {
                long currentTime = System.currentTimeMillis() / 1000;
                if (addCreated) {
                    requestParameters.setCreated(currentTime);
                }
                if (expiresLifetime > 0) {
                    requestParameters.setExpires(currentTime + expiresLifetime);
                }
            }
            if (addApproovTokenHeader) {
                requestParameters.addComponentIdentifier(changes.getTokenHeaderKey());
            }
            if (addApproovTraceIDHeader && changes.getTraceIDHeaderKey() != null) {
                requestParameters.addComponentIdentifier(changes.getTraceIDHeaderKey());
            }
            for (String headerName : optionalHeaders) {
                if (provider.hasField(headerName)) {
                    requestParameters.addComponentIdentifier(headerName);
                }
            }
            if (bodyDigestAlgorithm != null) {
                if (!generateBodyDigest(provider, requestParameters) && bodyDigestRequired) {
                    throw new IllegalStateException("Failed to create required body digest");
                }
            }
            return requestParameters;
        }
    }

    /**
     * OkHttpComponentProvider implements the ComponentProvider interface for
     * OkHttp3 requests.
     */
    protected static final class OkHttpComponentProvider implements ComponentProvider {
        private Request request;

        private HttpUrl okURL;

        private URI jURI;

        /**
         * Constructs an instance of {@code OkHttpComponentProvider}.
         *
         * @param request The OkHttp request to wrap.
         */
        OkHttpComponentProvider(Request request) {
            this.request = request;
            this.okURL = request.url();
            this.jURI = okURL.uri();
        }

        public Request getRequest() {
            return request;
        }

        public void setRequest(Request request) {
            this.request = request;
            this.okURL = request.url();
            this.jURI = okURL.uri();
        }

        @Override
        public String getMethod() {
            return request.method();
        }

        @Override
        public String getAuthority() {
            return okURL.host();
        }

        @Override
        public String getScheme() {
            return okURL.scheme();
        }

        @Override
        public String getTargetUri() {
            String rawPath = jURI.getRawPath();
            if (rawPath != null && !rawPath.isEmpty()) {
                return jURI.toString();
            }
            try {
                return new URI(
                        jURI.getScheme(),
                        jURI.getRawAuthority(),
                        "/",
                        jURI.getRawQuery(),
                        jURI.getRawFragment()).toString();
            } catch (URISyntaxException e) {
                return jURI.toString();
            }
        }

        @Override
        public String getRequestTarget() {
            String path = getPath();
            String reqt = path;
            if (jURI.getRawQuery() != null) {
                reqt += "?" + okURL.encodedQuery();
            }
            return reqt;
        }

        @Override
        public String getPath() {
            String encodedPath = okURL.encodedPath();
            if (encodedPath == null || encodedPath.isEmpty()) {
                return "/";
            }
            return encodedPath;
        }

        @Override
        public String getQuery() {
            return okURL.encodedQuery();
        }

        @Override
        public String getQueryParam(String name) {
            List<String> values = okURL.queryParameterValues(name);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Could not find query parameter named " + name);
            } else if (values.size() > 1) {
                // From Section 2.2.8 of the spec: If a parameter name occurs multiple times in
                // a request, the
                // named query parameter MUST NOT be included. If multiple parameters are common
                // within
                // an application, it is RECOMMENDED to sign the entire query string using the
                // @query
                // component identifier defined in Section 2.2.7.

                // to indicate that a query param must not be included, we return null
                return null;
            }
            return values.get(0);
        }

        @Override
        public String getStatus() {
            throw new IllegalStateException("Only requests are supported");
        }

        @Override
        public boolean hasField(String name) {
            List<String> headers = request.headers(name);
            return !headers.isEmpty();
        }

        @Override
        public String getField(String name) {
            List<String> headers = request.headers(name);
            return ComponentProvider.combineFieldValues(headers);
        }

        @Override
        public boolean hasBody() {
            return request.body() != null;
        }
    }
}
