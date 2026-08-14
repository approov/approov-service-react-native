package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import io.approov.util.sig.ComponentProvider;
import io.approov.util.sig.SignatureBaseBuilder;
import io.approov.util.sig.SignatureParameters;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

import org.junit.Before;
import org.junit.Test;

public class ApproovDefaultMessageSigningTest {

    private static final MediaType APPLICATION_JSON = MediaType.get("application/json");
    private static final long FIXED_CREATED = 1_717_171_717L;
    private static final long FIXED_EXPIRES_LIFETIME = 15L;

    private RecordingApproovDefaultMessageSigning signer;
    private ApproovRequestMutations changes;

    @Before
    public void setUp() {
        signer = new RecordingApproovDefaultMessageSigning();
        signer.setDefaultFactory(new FixedDefaultSignatureParametersFactory());
        changes = new ApproovRequestMutations();
        changes.setTokenHeaderKey("Approov-Token");
        changes.setTraceIDHeaderKey("Approov-TraceID");
    }

    private static final class RecordingApproovDefaultMessageSigning extends ApproovDefaultMessageSigning {
        private final List<String> installMessages = new ArrayList<>();
        private final List<String> accountMessages = new ArrayList<>();
        private String installSignatureBase64 = "";
        private String accountSignatureBase64 = "";
        private boolean throwAccountException;

        @Override
        protected String getInstallMessageSignature(String message) {
            installMessages.add(message);
            return installSignatureBase64;
        }

        @Override
        protected String getAccountMessageSignature(String message) throws ApproovException {
            accountMessages.add(message);
            if (throwAccountException) {
                throw new ApproovException("account signature unavailable");
            }
            return accountSignatureBase64;
        }

        @Override
        protected byte[] decodeBase64(String base64) {
            return Base64.getDecoder().decode(base64);
        }

        void setInstallSignatureBase64(String signature) {
            installSignatureBase64 = signature;
        }

        void setAccountSignatureBase64(String signature) {
            accountSignatureBase64 = signature;
        }

        void setThrowAccountException(boolean throwAccountException) {
            this.throwAccountException = throwAccountException;
        }

        void clearRecordedMessages() {
            installMessages.clear();
        }

        List<String> getInstallMessages() {
            return installMessages;
        }

        List<String> getAccountMessages() {
            return accountMessages;
        }
    }

    private static class FixedDefaultSignatureParametersFactory
        extends ApproovDefaultMessageSigning.SignatureParametersFactory {

        FixedDefaultSignatureParametersFactory() {
            setBaseParameters(new SignatureParameters()
                .addComponentIdentifier(ComponentProvider.DC_METHOD)
                .addComponentIdentifier(ComponentProvider.DC_TARGET_URI));
            setUseInstallMessageSigning();
            setAddCreated(true);
            setExpiresLifetime(FIXED_EXPIRES_LIFETIME);
            setAddApproovTokenHeader(true);
            setAddApproovTraceIDHeader(true);
            addOptionalHeaders("Authorization", "Content-Length", "Content-Type");
            setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, false);
        }

        @Override
        protected SignatureParameters buildSignatureParameters(
                ApproovDefaultMessageSigning.OkHttpComponentProvider provider,
                ApproovRequestMutations changes) {
            SignatureParameters params = super.buildSignatureParameters(provider, changes);
            params.setCreated(FIXED_CREATED);
            params.setExpires(FIXED_CREATED + FIXED_EXPIRES_LIFETIME);
            return params;
        }
    }

    private static final class UnsupportedAlgorithmFactory extends FixedDefaultSignatureParametersFactory {
        @Override
        protected SignatureParameters buildSignatureParameters(
                ApproovDefaultMessageSigning.OkHttpComponentProvider provider,
                ApproovRequestMutations changes) {
            SignatureParameters params = super.buildSignatureParameters(provider, changes);
            params.setAlg("unsupported-alg");
            return params;
        }
    }

    private static String derEncodedInstallSignature() {
        byte[] der = new byte[] { 0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02 };
        return Base64.getEncoder().encodeToString(der);
    }

    private static int countOccurrences(String value, String needle) {
        int matches = 0;
        int index = 0;
        while (value != null && (index = value.indexOf(needle, index)) != -1) {
            matches++;
            index += needle.length();
        }
        return matches;
    }

    private Request signedRequestFixture() {
        return new Request.Builder()
            .url("https://api.example.com/reply")
            .post(RequestBody.create(APPLICATION_JSON, "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8)))
            .header("Approov-Token", "Bearer jwt-token")
            .header("Approov-TraceID", "trace-123")
            .header("Authorization", "Bearer auth-token")
            .header("Content-Type", "application/json")
            .header("Signature", "stale-signature")
            .header("Signature-Input", "stale-input")
            .header("Signature-Base-Digest", "stale-digest")
            .build();
    }

    private Request unsignedRequestFixture() {
        return new Request.Builder()
            .url("https://api.example.com/reply")
            .header("Approov-Token", "Bearer jwt-token")
            .header("Approov-TraceID", "trace-123")
            .build();
    }

    private String expectedSignatureBase(Request request) {
        ApproovDefaultMessageSigning.OkHttpComponentProvider provider =
            new ApproovDefaultMessageSigning.OkHttpComponentProvider(request);
        return new SignatureBaseBuilder(
            signer.defaultFactory.buildSignatureParameters(provider, changes),
            provider
        ).createSignatureBase();
    }

    private String captureSignatureBase(Request request) throws Exception {
        signer.clearRecordedMessages();
        signer.setInstallSignatureBase64("");
        signer.processedRequest(request, changes);
        return signer.getInstallMessages().get(0);
    }

    @Test
    public void defaultSigningAddsRequiredHeadersAndComputesTheSignature() throws Exception {
        Request request = signedRequestFixture();
        String expectedMessage = expectedSignatureBase(request);
        String actualMessage = captureSignatureBase(request);

        signer.clearRecordedMessages();
        signer.setInstallSignatureBase64(derEncodedInstallSignature());
        Request signed = signer.processedRequest(request, changes);

        assertEquals("Bearer jwt-token", signed.header("Approov-Token"));
        assertEquals("trace-123", signed.header("Approov-TraceID"));
        assertEquals(expectedMessage, actualMessage);
        assertEquals(Arrays.asList(actualMessage), signer.getInstallMessages());
        assertFalse(expectedMessage.isEmpty());
        assertTrue(expectedMessage.contains("\"@method\""));
        assertTrue(expectedMessage.contains("\"@target-uri\""));
        assertTrue(expectedMessage.contains("\"approov-token\""));
        assertTrue(expectedMessage.contains("\"approov-traceid\""));
        assertTrue(expectedMessage.contains("\"content-digest\""));
        assertTrue(expectedMessage.contains("\"authorization\""));
        assertTrue(expectedMessage.contains("\"@signature-params\""));

        assertNotNull("Signed headers: " + signed.headers(), signed.header("Content-Digest"));
        assertNotNull(signed.header("Signature"));
        assertNotNull(signed.header("Signature-Input"));
        assertTrue(signed.header("Signature").contains("install=:"));
        assertFalse(signed.header("Signature").contains(":" + derEncodedInstallSignature() + ":"));
        assertTrue(signed.header("Signature-Input").contains("install=("));
        assertNotEquals("stale-signature", signed.header("Signature"));
        assertNotEquals("stale-input", signed.header("Signature-Input"));
        assertNull(signed.header("Signature-Base-Digest"));
        assertEquals(1, signed.headers("Signature").size());
        assertEquals(1, signed.headers("Signature-Input").size());
    }

    @Test
    public void signingTheSameRequestTwiceDoesNotProduceDoubleSignatures() throws Exception {
        Request request = signedRequestFixture();
        String expectedMessage = expectedSignatureBase(request);
        String actualMessage = captureSignatureBase(request);

        signer.clearRecordedMessages();
        signer.setInstallSignatureBase64(derEncodedInstallSignature());
        Request signedOnce = signer.processedRequest(request, changes);
        Request signedTwice = signer.processedRequest(signedOnce, changes);

        assertEquals(expectedMessage, actualMessage);
        assertEquals(Arrays.asList(actualMessage, actualMessage), signer.getInstallMessages());
        assertEquals(1, signedTwice.headers("Signature").size());
        assertEquals(1, signedTwice.headers("Signature-Input").size());
        assertNull("Signed twice headers: " + signedTwice.headers(), signedTwice.header("Signature-Base-Digest"));
        assertFalse(signedTwice.header("Signature").contains("stale-signature"));
        assertFalse(signedTwice.header("Signature-Input").contains("stale-input"));
        assertEquals(1, countOccurrences(signedTwice.header("Signature"), "install=:"));
        assertEquals(1, countOccurrences(signedTwice.header("Signature-Input"), "install="));
    }

    @Test
    public void signingCanonicalizesRootTargetUris() throws Exception {
        Request request = new Request.Builder()
            .url("https://api.example.com?hello=world")
            .header("Approov-Token", "Bearer jwt-token")
            .header("Approov-TraceID", "trace-123")
            .build();

        String actualMessage = captureSignatureBase(request);

        assertTrue(actualMessage.contains("https://api.example.com/?hello=world"));
    }

    @Test
    public void signingUsesDynamicTokenHeadersWithoutRequiringTraceHeaders() throws Exception {
        changes.setTokenHeaderKey("X-Approov-Token");
        changes.setTraceIDHeaderKey(null);

        Request request = new Request.Builder()
            .url("https://api.example.com/reply")
            .post(RequestBody.create(APPLICATION_JSON, "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8)))
            .header("X-Approov-Token", "Bearer custom-token")
            .header("Content-Type", "application/json")
            .build();

        String actualMessage = captureSignatureBase(request);

        signer.clearRecordedMessages();
        signer.setInstallSignatureBase64(derEncodedInstallSignature());
        Request signed = signer.processedRequest(request, changes);

        assertTrue(actualMessage.contains("\"x-approov-token\""));
        assertFalse(actualMessage.contains("\"approov-traceid\""));
        assertNotNull(signed.header("Signature"));
        assertNotNull(signed.header("Signature-Input"));
    }

    @Test
    public void installSigningBase64FailuresProceedUnsigned() throws Exception {
        Request request = unsignedRequestFixture();
        signer.setInstallSignatureBase64("not base64");

        Request processed = signer.processedRequest(request, changes);

        assertSame(request, processed);
        assertNull(processed.header("Signature"));
        assertNull(processed.header("Signature-Input"));
        assertEquals(1, signer.getInstallMessages().size());
    }

    @Test
    public void installSigningDerFailuresProceedUnsigned() throws Exception {
        Request request = unsignedRequestFixture();
        signer.setInstallSignatureBase64(
            Base64.getEncoder().encodeToString("not der".getBytes(StandardCharsets.UTF_8)));

        Request processed = signer.processedRequest(request, changes);

        assertSame(request, processed);
        assertNull(processed.header("Signature"));
        assertNull(processed.header("Signature-Input"));
        assertEquals(1, signer.getInstallMessages().size());
    }

    @Test
    public void accountSigningUnavailableProceedsUnsigned() throws Exception {
        Request request = unsignedRequestFixture();
        ApproovDefaultMessageSigning.SignatureParametersFactory accountFactory =
            new FixedDefaultSignatureParametersFactory().setUseAccountMessageSigning();
        signer.setDefaultFactory(accountFactory);
        signer.setThrowAccountException(true);

        Request processed = signer.processedRequest(request, changes);

        assertSame(request, processed);
        assertNull(processed.header("Signature"));
        assertNull(processed.header("Signature-Input"));
        assertEquals(1, signer.getAccountMessages().size());
    }

    @Test
    public void accountSigningBase64FailuresProceedUnsigned() throws Exception {
        Request request = unsignedRequestFixture();
        ApproovDefaultMessageSigning.SignatureParametersFactory accountFactory =
            new FixedDefaultSignatureParametersFactory().setUseAccountMessageSigning();
        signer.setDefaultFactory(accountFactory);
        signer.setAccountSignatureBase64("not base64");

        Request processed = signer.processedRequest(request, changes);

        assertSame(request, processed);
        assertNull(processed.header("Signature"));
        assertNull(processed.header("Signature-Input"));
        assertEquals(1, signer.getAccountMessages().size());
    }

    @Test
    public void unsupportedSigningAlgorithmFailsClosed() throws Exception {
        Request request = unsignedRequestFixture();
        signer.setDefaultFactory(new UnsupportedAlgorithmFactory());

        try {
            signer.processedRequest(request, changes);
            fail("Unsupported signing algorithms must fail closed");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Unsupported algorithm identifier"));
        }
    }
}
