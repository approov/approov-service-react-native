package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.criticalblue.approovsdk.Approov;
import com.criticalblue.minisdk.testing.AttesterProxyController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import io.approov.internal.reactnative.util.sig.ComponentProvider;
import io.approov.internal.reactnative.util.sig.SignatureParameters;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Runs the application interceptor against the Approov mini-SDK. Token, secure string and pin
 * refresh results come from the mini-SDK, with failure statuses set by its attestation directives.
 * The OkHttp chain is a mock that answers 200 with the request it was given.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ApproovInterceptorTest {

    private static final MediaType TEXT_PLAIN = MediaType.get("text/plain");
    private static final MediaType APPLICATION_JSON = MediaType.get("application/json");
    private static final long FIXED_CREATED = 1_717_171_717L;
    private static final long FIXED_EXPIRES_LIFETIME = 15L;
    private static final String API = "https://api.example.com/data";

    // a spy over a real service initialized with the mini-SDK
    private ApproovService service;
    // every Approov SDK call, answered by the mini-SDK and recorded
    private MockedStatic<Approov> sdk;
    private Interceptor.Chain chain;
    private ApproovInterceptor interceptor;
    private RecordingMutator mutator;

    private static final class RecordingMutator implements ApproovServiceMutator {
        boolean shouldProcess = true;
        Boolean fetchTokenShouldContinue = null;
        String processedRequestMarker = null;
        ApproovRequestMutations lastMutations;
        Request lastProcessedRequest;

        @Override
        public boolean handleInterceptorShouldProcessRequest(ApproovService service, Request request)
                throws ApproovException {
            if (!shouldProcess) {
                return false;
            }
            return ApproovServiceMutator.super.handleInterceptorShouldProcessRequest(service, request);
        }

        @Override
        public boolean handleInterceptorFetchTokenResult(ApproovService service,
                Approov.TokenFetchResult approovResults, String url) throws ApproovException {
            if (fetchTokenShouldContinue != null) {
                return fetchTokenShouldContinue.booleanValue();
            }
            return ApproovServiceMutator.super.handleInterceptorFetchTokenResult(service, approovResults, url);
        }

        @Override
        public Request handleInterceptorProcessedRequest(ApproovService service, Request request,
                ApproovRequestMutations changes) throws ApproovException {
            lastMutations = changes;
            lastProcessedRequest = request;
            if (processedRequestMarker == null) {
                return request;
            }
            return request.newBuilder()
                .header("X-Mutated", processedRequestMarker)
                .build();
        }
    }

    private static final class RecordingSigningMutator extends ApproovDefaultMessageSigning {
        private final List<String> installMessages = new ArrayList<>();
        private String installSignatureBase64 = "";

        @Override
        protected String getInstallMessageSignature(String message) {
            installMessages.add(message);
            return installSignatureBase64;
        }

        @Override
        protected byte[] decodeBase64(String base64) {
            return Base64.getDecoder().decode(base64);
        }

        void setInstallSignatureBase64(String signature) {
            installSignatureBase64 = signature;
        }

        List<String> getInstallMessages() {
            return installMessages;
        }
    }

    private static final class FixedDefaultSignatureParametersFactory
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

    private static String derEncodedInstallSignature() {
        byte[] der = new byte[] { 0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02 };
        return Base64.getEncoder().encodeToString(der);
    }

    @Before
    public void setUp() throws Exception {
        MiniSdkHarness.loadScenario("\"protectedDomains\": [\"api.example.com\"],"
                + "\"initialSecureStrings\": {\"header-secret\": \"live-header-secret\", \"query-secret\": \"live-query-secret\"}");
        ApproovService initialized = MiniSdkHarness.initializedService(MiniSdkHarness.reactContext());
        initialized.setTokenHeader("Approov-Token", "Bearer ");
        service = spy(initialized);
        chain = mock(Interceptor.Chain.class);
        interceptor = new ApproovInterceptor(service);
        mutator = new RecordingMutator();
        when(chain.proceed(any())).thenAnswer(invocation -> {
            Request proceeded = invocation.getArgument(0);
            return new Response.Builder()
                .request(proceeded)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create("ok", TEXT_PLAIN))
                .build();
        });

        ApproovService.setServiceMutator(mutator);
        sdk = mockStatic(Approov.class, CALLS_REAL_METHODS);
    }

    @After
    public void tearDown() throws Exception {
        if (sdk != null)
            sdk.close();
        MiniSdkHarness.tearDown();
    }

    private Request request(String url) {
        return new Request.Builder()
            .url(url)
            .get()
            .build();
    }

    // the next mini-SDK attestation for the operation answers with the given response members
    private static void nextAttestation(String operation, String response) {
        AttesterProxyController.setNextAttestationDirectiveJson(
            "{\"operation\": \"" + operation + "\", \"response\": {" + response + "}}");
    }

    private static void assertMiniSdkToken(String header) {
        assertNotNull("a token header should be present", header);
        assertTrue(header, header.startsWith("Bearer ey"));
    }

    // Forwarded untouched means the same request on the wire. It may carry the in-process
    // ApproovIssuedHeaders tag, which is never sent.
    private static void assertForwardedUntouched(Request expected, Request actual) {
        assertEquals(expected.url(), actual.url());
        assertEquals(expected.method(), actual.method());
        assertEquals(expected.headers(), actual.headers());
        assertSame(expected.body(), actual.body());
    }

    // a service that was never initialized, or initialized with the empty (bypass) config
    private ApproovInterceptor interceptorForUnprotectedService(String config) throws Exception {
        MiniSdkHarness.resetServiceState();
        ApproovService fresh = new ApproovService(MiniSdkHarness.reactContext());
        if (config != null)
            MiniSdkHarness.initialize(fresh, config);
        sdk.clearInvocations();
        return new ApproovInterceptor(fresh);
    }

    @Test
    public void localhostRequestsBypassApproov() throws Exception {
        Request request = request("https://localhost/health");
        when(chain.request()).thenReturn(request);

        Response response = interceptor.intercept(chain);

        assertEquals(200, response.code());
        assertForwardedUntouched(request, response.request());
        sdk.verifyNoInteractions();
    }

    @Test
    public void debugBuildForwardsDevelopmentHostsWithoutApproov() throws Exception {
        doReturn(true).when(service).isAppDebuggable();
        String[] urls = {
            "http://10.0.2.2:8081/symbolicate",
            "http://10.0.3.2:8081/index.bundle?platform=android",
            "http://127.0.0.1:8081/status",
            "http://[::1]:8081/status"
        };

        for (String url : urls) {
            Request request = request(url);
            when(chain.request()).thenReturn(request);

            Response response = interceptor.intercept(chain);

            assertEquals(url, 200, response.code());
            assertForwardedUntouched(request, response.request());
        }
        sdk.verifyNoInteractions();
    }

    @Test
    public void releaseBuildStillProcessesDevelopmentHosts() throws Exception {
        doReturn(false).when(service).isAppDebuggable();
        String url = "http://10.0.2.2:8081/symbolicate";
        when(chain.request()).thenReturn(request(url));

        // the mini-SDK answers BAD_URL for a cleartext URL, which stops the request; what matters
        // here is that the host was not skipped as a development host
        try {
            interceptor.intercept(chain);
        } catch (IOException expected) {
            // BAD_URL
        }
        sdk.verify(() -> Approov.fetchApproovTokenAndWait(url));
    }

    @Test
    public void uninitializedRequestsForwardWithoutAStartupWait() throws Exception {
        ApproovInterceptor uninitialized = interceptorForUnprotectedService(null);
        Request request = request("https://example.com/data");
        when(chain.request()).thenReturn(request);

        // the removed startup wait held a request for up to 2.5 s
        long start = System.nanoTime();
        Response response = uninitialized.intercept(chain);
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("an early request must not wait for initialization (took " + elapsedMs + " ms)", elapsedMs < 1000);

        assertForwardedUntouched(request, response.request());
        sdk.verifyNoInteractions();
    }

    @Test
    public void initializedWithEmptyConfigForwardsWithoutApproovProcessing() throws Exception {
        ApproovInterceptor bypass = interceptorForUnprotectedService("");
        Request request = request("https://example.com/data");
        when(chain.request()).thenReturn(request);

        Response response = bypass.intercept(chain);

        assertForwardedUntouched(request, response.request());
        sdk.verifyNoInteractions();
    }

    @Test
    public void successWithEmptyTokenOmitsTokenAndTraceHeaders() throws Exception {
        // the mini-SDK always issues a token on SUCCESS, so the empty result is stubbed
        Approov.TokenFetchResult empty = mock(Approov.TokenFetchResult.class);
        when(empty.getStatus()).thenReturn(Approov.TokenFetchStatus.SUCCESS);
        when(empty.getToken()).thenReturn("");
        when(empty.getTraceID()).thenReturn("");
        when(empty.getLoggableToken()).thenReturn("");
        sdk.when(() -> Approov.fetchApproovTokenAndWait(API)).thenReturn(empty);
        when(chain.request()).thenReturn(request(API));

        Response response = interceptor.intercept(chain);

        assertNull(response.request().header("Approov-Token"));
        assertNull(response.request().header("Approov-TraceID"));
    }

    @Test
    public void successAddsTokenTraceHeadersSubstitutionsAndMutatorChanges() throws Exception {
        Request request = new Request.Builder()
            .url("https://api.example.com/reply?secret=query-secret")
            .header("Authorization", "bind-me")
            .header("Api-Key", "Bearer header-secret")
            .build();
        when(chain.request()).thenReturn(request);
        service.setBindingHeader("Authorization");
        service.addSubstitutionHeader("Api-Key", "Bearer ");
        service.addSubstitutionQueryParam("secret");
        mutator.processedRequestMarker = "yes";

        Response response = interceptor.intercept(chain);

        Request proceeded = response.request();
        assertMiniSdkToken(proceeded.header("Approov-Token"));
        assertNotNull(proceeded.header("Approov-TraceID"));
        assertFalse(proceeded.header("Approov-TraceID").isEmpty());
        assertEquals("Bearer live-header-secret", proceeded.header("Api-Key"));
        assertEquals("yes", proceeded.header("X-Mutated"));
        assertTrue(proceeded.url().toString().contains("secret=live-query-secret"));

        assertNotNull(mutator.lastMutations);
        assertEquals("Approov-Token", mutator.lastMutations.getTokenHeaderKey());
        assertEquals("Approov-TraceID", mutator.lastMutations.getTraceIDHeaderKey());
        assertEquals("https://api.example.com/reply?secret=live-query-secret",
            mutator.lastMutations.getOriginalURL());
        assertEquals(Arrays.asList("Api-Key"), mutator.lastMutations.getSubstitutionHeaderKeys());
        assertEquals(Arrays.asList("secret"), mutator.lastMutations.getSubstitutionQueryParamKeys());

        sdk.verify(() -> Approov.setDataHashInToken("bind-me"));
    }

    @Test
    public void networkFailuresThrowIOExceptionWithTheApproovCause() throws Exception {
        when(chain.request()).thenReturn(request(API));
        nextAttestation("fetchApproovToken", "\"status\": \"NO_NETWORK\"");

        IOException error = assertThrows(IOException.class, () -> interceptor.intercept(chain));

        assertTrue(error.getCause() instanceof ApproovNetworkException);
        verify(chain, never()).proceed(any());
    }

    @Test
    public void noApproovServiceFallsThroughWithoutAddingATokenByDefault() throws Exception {
        when(chain.request()).thenReturn(request(API));
        nextAttestation("fetchApproovToken", "\"status\": \"NO_APPROOV_SERVICE\"");

        Response response = interceptor.intercept(chain);

        assertFalse(response.request().headers().names().contains("Approov-Token"));
    }

    @Test
    public void customMutatorCanProceedOnMitmAndExposeTheStatusHeader() throws Exception {
        when(chain.request()).thenReturn(request(API));
        service.setUseApproovStatusIfNoToken(true);
        mutator.fetchTokenShouldContinue = Boolean.TRUE;
        nextAttestation("fetchApproovToken", "\"status\": \"MITM_DETECTED\"");

        Response response = interceptor.intercept(chain);

        assertEquals("Bearer MITM_DETECTED", response.request().header("Approov-Token"));
    }

    @Test
    public void messageSigningMutatorRunsAfterInterceptorMutationsEndToEnd() throws Exception {
        RecordingSigningMutator signingMutator = new RecordingSigningMutator();
        signingMutator.setDefaultFactory(new FixedDefaultSignatureParametersFactory());
        signingMutator.setInstallSignatureBase64(derEncodedInstallSignature());
        ApproovService.setServiceMutator(signingMutator);

        Request request = new Request.Builder()
            .url("https://api.example.com/reply")
            .post(RequestBody.create(APPLICATION_JSON, "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer auth-token")
            .header("Content-Type", "application/json")
            .build();
        when(chain.request()).thenReturn(request);

        Response response = interceptor.intercept(chain);
        Request proceeded = response.request();

        assertMiniSdkToken(proceeded.header("Approov-Token"));
        assertNotNull(proceeded.header("Approov-TraceID"));
        assertNotNull(proceeded.header("Content-Digest"));
        assertNotNull(proceeded.header("Signature"));
        assertNotNull(proceeded.header("Signature-Input"));
        assertTrue(proceeded.header("Signature").contains("install=:"));
        assertEquals(1, signingMutator.getInstallMessages().size());
        assertTrue(signingMutator.getInstallMessages().get(0).contains("\"approov-token\""));
        assertTrue(signingMutator.getInstallMessages().get(0).contains("\"approov-traceid\""));
    }

    @Test
    public void configChangesRefreshSharedPins() throws Exception {
        when(chain.request()).thenReturn(request(API));
        nextAttestation("fetchApproovToken", "\"status\": \"SUCCESS\", \"configChanged\": true");

        interceptor.intercept(chain);

        sdk.verify(Approov::fetchConfig);
        verify(service).rebuildPins();
    }

    @Test
    public void forceApplyPinsRefreshesBeforeProceedingWithTheToken() throws Exception {
        when(chain.request()).thenReturn(request(API));
        nextAttestation("fetchApproovToken", "\"status\": \"SUCCESS\", \"forceApplyPins\": true");

        Response response = interceptor.intercept(chain);

        assertMiniSdkToken(response.request().header("Approov-Token"));
        org.mockito.InOrder order = inOrder(service, chain);
        order.verify(service).rebuildPins();
        order.verify(chain).proceed(any());
        verify(service).rebuildPins();
        sdk.verify(Approov::fetchConfig, never());
    }

    @Test
    public void simultaneousConfigAndForceFlagsRebuildPinsOnlyOnce() throws Exception {
        when(chain.request()).thenReturn(request(API));
        nextAttestation("fetchApproovToken",
            "\"status\": \"SUCCESS\", \"configChanged\": true, \"forceApplyPins\": true");

        Response response = interceptor.intercept(chain);

        assertMiniSdkToken(response.request().header("Approov-Token"));
        sdk.verify(Approov::fetchConfig);
        verify(service).rebuildPins();
    }

    @Test
    public void forcedPinRefreshFailureStillBlocksTheRequest() throws Exception {
        when(chain.request()).thenReturn(request(API));
        nextAttestation("fetchApproovToken", "\"status\": \"SUCCESS\", \"forceApplyPins\": true");
        // the mini-SDK cannot fail a pin read, so the refresh failure is injected
        IllegalStateException failure = new IllegalStateException("pins unavailable");
        doThrow(failure).when(service).rebuildPins();

        // an IOException fails only this call; an unchecked exception would end the app
        ApproovException error = assertThrows(ApproovException.class, () -> interceptor.intercept(chain));
        assertSame(failure, error.getCause());
        verify(chain, never()).proceed(any());
    }

    @Test
    public void forcedPinRefreshDoesNotBypassTokenFailurePolicy() throws Exception {
        when(chain.request()).thenReturn(request(API));
        nextAttestation("fetchApproovToken", "\"status\": \"NO_NETWORK\", \"forceApplyPins\": true");

        assertThrows(IOException.class, () -> interceptor.intercept(chain));

        verify(service).rebuildPins();
        verify(chain, never()).proceed(any());
    }

    @Test
    public void headerSubstitutionNetworkFailureSkipsTheSubstitutionButStillProceeds() throws Exception {
        Request request = new Request.Builder()
            .url(API)
            .header("Api-Key", "Bearer header-secret")
            .build();
        when(chain.request()).thenReturn(request);
        service.addSubstitutionHeader("Api-Key", "Bearer ");
        nextAttestation("fetchSecureString", "\"status\": \"NO_NETWORK\"");

        Response response = interceptor.intercept(chain);

        assertEquals("Bearer header-secret", response.request().header("Api-Key"));
        assertMiniSdkToken(response.request().header("Approov-Token"));
        verify(chain).proceed(any());
    }

    @Test
    public void policyMutatorSkipsMaskedNoApproovServiceHeaderSubstitution() throws Exception {
        Request request = new Request.Builder()
            .url(API)
            .header("Api-Key", "header-secret")
            .build();
        when(chain.request()).thenReturn(request);
        service.setUseApproovStatusIfNoToken(true);
        service.addSubstitutionHeader("Api-Key", "");
        ApproovService.setServiceMutator(new PolicyMutator(PolicyMutator.BIT_NO_APPROOV_SERVICE, false));
        // the mini-SDK holds one directive at a time, so the token fetch queues the secure string's
        sdk.when(() -> Approov.fetchApproovTokenAndWait(API)).thenAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            nextAttestation("fetchSecureString", "\"status\": \"NO_APPROOV_SERVICE\"");
            return result;
        });
        sdk.clearInvocations();
        nextAttestation("fetchApproovToken", "\"status\": \"NO_APPROOV_SERVICE\"");

        Response response = interceptor.intercept(chain);

        assertEquals("header-secret", response.request().header("Api-Key"));
        assertEquals("Bearer NO_APPROOV_SERVICE", response.request().header("Approov-Token"));
        sdk.verify(() -> Approov.fetchSecureStringAndWait("header-secret", null));
        verify(chain).proceed(any());
    }

    @Test
    public void strictSigningFailureSurfacesAsIOExceptionNotUncheckedCrash() throws Exception {
        // strict fail-closed signing errors (unsupported algorithm, required body
        // digest) are thrown as IllegalStateException by the signer; the interceptor
        // must wrap them as IOException so OkHttp reports a clean network error
        // instead of crashing the dispatcher thread
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public Request handleInterceptorProcessedRequest(ApproovService service, Request request,
                    ApproovRequestMutations changes) {
                throw new IllegalStateException("Failed to create required body digest");
            }
        });
        when(chain.request()).thenReturn(request(API));

        IOException error = assertThrows(IOException.class, () -> interceptor.intercept(chain));

        assertTrue(error.getCause() instanceof IllegalStateException);
        assertTrue(error.getCause().getMessage().contains("required body digest"));
        verify(chain, never()).proceed(any());
    }

    @Test
    public void queryParameterRejectionStopsTheRequest() throws Exception {
        when(chain.request()).thenReturn(request("https://api.example.com/data?secret=query-secret"));
        service.addSubstitutionQueryParam("secret");
        nextAttestation("fetchSecureString", "\"status\": \"REJECTED\"");

        IOException error = assertThrows(IOException.class, () -> interceptor.intercept(chain));

        assertTrue(error.getCause() instanceof ApproovRejectionException);
        verify(chain, never()).proceed(any());
    }
}
