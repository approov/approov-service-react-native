package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import io.approov.util.sig.ComponentProvider;
import io.approov.util.sig.SignatureParameters;
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
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

public class ApproovInterceptorTest {

    private static final MediaType TEXT_PLAIN = MediaType.get("text/plain");
    private static final MediaType APPLICATION_JSON = MediaType.get("application/json");
    private static final long FIXED_CREATED = 1_717_171_717L;
    private static final long FIXED_EXPIRES_LIFETIME = 15L;

    private ApproovService service;
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
        service = mock(ApproovService.class);
        chain = mock(Interceptor.Chain.class);
        interceptor = new ApproovInterceptor(service);
        mutator = new RecordingMutator();

        when(service.isSuppressLoggingUnknownURL()).thenReturn(false);
        when(service.getBindingHeader()).thenReturn(null);
        when(service.getTokenHeader()).thenReturn("Approov-Token");
        when(service.getTokenPrefix()).thenReturn("Bearer ");
        when(service.getTraceIDHeader()).thenReturn("Approov-TraceID");
        when(service.getSubstitutionHeaders()).thenReturn(new HashMap<>());
        when(service.getSubstitutionQueryParams()).thenReturn(new HashMap<>());
        when(service.getExclusionURLRegexs()).thenReturn(new HashMap<>());
        when(service.getUseApproovStatusIfNoToken()).thenReturn(false);
        when(service.isInitialized()).thenReturn(true);
        when(service.isApproovEnabled()).thenReturn(true);
        doNothing().when(service).notifyPinChangeListeners();
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
    }

    @After
    public void tearDown() {
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
    }

    private Request request(String url) {
        return new Request.Builder()
            .url(url)
            .get()
            .build();
    }

    private Approov.TokenFetchResult result(Approov.TokenFetchStatus status) {
        return result(status, "jwt-token", "trace-123", "secret-value");
    }

    private Approov.TokenFetchResult result(Approov.TokenFetchStatus status, String token, String traceId,
            String secureString) {
        Approov.TokenFetchResult result = mock(Approov.TokenFetchResult.class);
        when(result.getStatus()).thenReturn(status);
        when(result.getLoggableToken()).thenReturn("loggable-token");
        when(result.getToken()).thenReturn(token);
        when(result.getTraceID()).thenReturn(traceId);
        when(result.getSecureString()).thenReturn(secureString);
        when(result.getARC()).thenReturn("ARC123");
        when(result.getRejectionReasons()).thenReturn("reason");
        when(result.isConfigChanged()).thenReturn(false);
        when(result.isForceApplyPins()).thenReturn(false);
        return result;
    }

    @Test
    public void localhostRequestsBypassApproov() throws Exception {
        Request request = request("https://localhost/health");
        when(chain.request()).thenReturn(request);

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            Response response = interceptor.intercept(chain);

            assertEquals(200, response.code());
            assertEquals(request, response.request());
            approov.verifyNoInteractions();
        }
    }

    @Test
    public void uninitializedRequestsForwardWhenTheStartupWindowHasExpired() throws Exception {
        Request request = request("https://example.com/data");
        when(chain.request()).thenReturn(request);
        when(service.isInitialized()).thenReturn(false);
        when(service.isApproovEnabled()).thenReturn(false);
        when(service.getEarliestNetworkRequestTime()).thenReturn(System.currentTimeMillis() - 1L);

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            Response response = interceptor.intercept(chain);

            assertEquals(request, response.request());
            verify(service).setEarliestNetworkRequestTime();
            approov.verifyNoInteractions();
        }
    }

    @Test
    public void initializedWithEmptyConfigForwardsWithoutApproovProcessing() throws Exception {
        Request request = request("https://example.com/data");
        when(chain.request()).thenReturn(request);
        when(service.isInitialized()).thenReturn(true);
        when(service.isApproovEnabled()).thenReturn(false);

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            Response response = interceptor.intercept(chain);

            assertEquals(request, response.request());
            approov.verifyNoInteractions();
        }
    }

    @Test
    public void successWithEmptyTokenOmitsTokenAndTraceHeaders() throws Exception {
        Request request = request("https://api.example.com/data");
        when(chain.request()).thenReturn(request);
        when(service.getTraceIDHeader()).thenReturn("Approov-TraceID");

        Approov.TokenFetchResult tokenResult = result(
            Approov.TokenFetchStatus.SUCCESS,
            "",
            "",
            "unused"
        );

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            when(service.fetchApproovTokenCached("https://api.example.com/data"))
                .thenReturn(tokenResult);

            Response response = interceptor.intercept(chain);

            assertNull(response.request().header("Approov-Token"));
            assertNull(response.request().header("Approov-TraceID"));
        }
    }

    @Test
    public void successAddsTokenTraceHeadersSubstitutionsAndMutatorChanges() throws Exception {
        Request request = new Request.Builder()
            .url("https://api.example.com/reply?secret=query-secret")
            .header("Authorization", "bind-me")
            .header("Api-Key", "Bearer header-secret")
            .build();
        when(chain.request()).thenReturn(request);
        when(service.getBindingHeader()).thenReturn("Authorization");

        HashMap<String, String> substitutionHeaders = new HashMap<>();
        substitutionHeaders.put("Api-Key", "Bearer ");
        when(service.getSubstitutionHeaders()).thenReturn(substitutionHeaders);

        HashMap<String, Pattern> substitutionQueryParams = new HashMap<>();
        substitutionQueryParams.put("secret", Pattern.compile("[\\?&]secret=([^&;]+)"));
        when(service.getSubstitutionQueryParams()).thenReturn(substitutionQueryParams);

        mutator.processedRequestMarker = "yes";

        Approov.TokenFetchResult tokenResult = result(
            Approov.TokenFetchStatus.SUCCESS,
            "jwt-token",
            "trace-123",
            "unused"
        );
        Approov.TokenFetchResult headerResult = result(
            Approov.TokenFetchStatus.SUCCESS,
            "",
            "",
            "live-header-secret"
        );
        Approov.TokenFetchResult queryResult = result(
            Approov.TokenFetchStatus.SUCCESS,
            "",
            "",
            "live-query-secret"
        );

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            when(service.fetchApproovTokenCached("https://api.example.com/reply?secret=query-secret")).thenReturn(tokenResult);
            approov.when(() -> Approov.fetchSecureStringAndWait("header-secret", null)).thenReturn(headerResult);
            approov.when(() -> Approov.fetchSecureStringAndWait("query-secret", null)).thenReturn(queryResult);

            Response response = interceptor.intercept(chain);

            Request proceeded = response.request();
            assertEquals("Bearer jwt-token", proceeded.header("Approov-Token"));
            assertEquals("trace-123", proceeded.header("Approov-TraceID"));
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

            approov.verify(() -> Approov.setDataHashInToken("bind-me"));
        }
    }

    @Test
    public void networkFailuresThrowIOExceptionWithTheApproovCause() throws Exception {
        Request request = request("https://api.example.com/data");
        when(chain.request()).thenReturn(request);

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            Approov.TokenFetchResult tokenResult = result(Approov.TokenFetchStatus.NO_NETWORK);
            when(service.fetchApproovTokenCached("https://api.example.com/data"))
                .thenReturn(tokenResult);

            IOException error = assertThrows(IOException.class, () -> interceptor.intercept(chain));

            assertTrue(error.getCause() instanceof ApproovNetworkException);
            verify(chain, never()).proceed(any());
        }
    }

    @Test
    public void noApproovServiceFallsThroughWithoutAddingATokenByDefault() throws Exception {
        Request request = request("https://api.example.com/data");
        when(chain.request()).thenReturn(request);

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            Approov.TokenFetchResult tokenResult = result(Approov.TokenFetchStatus.NO_APPROOV_SERVICE);
            when(service.fetchApproovTokenCached("https://api.example.com/data"))
                .thenReturn(tokenResult);

            Response response = interceptor.intercept(chain);

            assertFalse(response.request().headers().names().contains("Approov-Token"));
        }
    }

    @Test
    public void customMutatorCanProceedOnMitmAndExposeTheStatusHeader() throws Exception {
        Request request = request("https://api.example.com/data");
        when(chain.request()).thenReturn(request);
        when(service.getUseApproovStatusIfNoToken()).thenReturn(true);
        mutator.fetchTokenShouldContinue = Boolean.TRUE;

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            Approov.TokenFetchResult tokenResult = result(Approov.TokenFetchStatus.MITM_DETECTED, "", "", "");
            when(service.fetchApproovTokenCached("https://api.example.com/data"))
                .thenReturn(tokenResult);

            Response response = interceptor.intercept(chain);

            assertEquals("Bearer MITM_DETECTED", response.request().header("Approov-Token"));
        }
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

        Approov.TokenFetchResult tokenResult = result(
            Approov.TokenFetchStatus.SUCCESS,
            "jwt-token",
            "trace-123",
            "unused"
        );

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            when(service.fetchApproovTokenCached("https://api.example.com/reply"))
                .thenReturn(tokenResult);

            Response response = interceptor.intercept(chain);
            Request proceeded = response.request();

            assertEquals("Bearer jwt-token", proceeded.header("Approov-Token"));
            assertEquals("trace-123", proceeded.header("Approov-TraceID"));
            assertNotNull(proceeded.header("Content-Digest"));
            assertNotNull(proceeded.header("Signature"));
            assertNotNull(proceeded.header("Signature-Input"));
            assertTrue(proceeded.header("Signature").contains("install=:"));
            assertEquals(1, signingMutator.getInstallMessages().size());
            assertTrue(signingMutator.getInstallMessages().get(0).contains("\"approov-token\""));
            assertTrue(signingMutator.getInstallMessages().get(0).contains("\"approov-traceid\""));
        }
    }

    @Test
    public void configChangesRefreshPinsAndNotifyListeners() throws Exception {
        Request request = request("https://api.example.com/data");
        when(chain.request()).thenReturn(request);
        Approov.TokenFetchResult tokenResult = result(Approov.TokenFetchStatus.SUCCESS);
        when(tokenResult.isConfigChanged()).thenReturn(true);

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            when(service.fetchApproovTokenCached("https://api.example.com/data")).thenReturn(tokenResult);

            interceptor.intercept(chain);

            approov.verify(Approov::fetchConfig);
            verify(service).notifyPinChangeListeners();
        }
    }

    @Test
    public void forceApplyPinsStopsTheRequestAndNotifiesListeners() {
        Request request = request("https://api.example.com/data");
        when(chain.request()).thenReturn(request);
        Approov.TokenFetchResult tokenResult = result(Approov.TokenFetchStatus.SUCCESS);
        when(tokenResult.isForceApplyPins()).thenReturn(true);

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            when(service.fetchApproovTokenCached("https://api.example.com/data")).thenReturn(tokenResult);

            IOException error = assertThrows(IOException.class, () -> interceptor.intercept(chain));

            assertTrue(error.getMessage().contains("Approov pins need to be updated"));
            verify(service).notifyPinChangeListeners();
        }
    }

    @Test
    public void headerSubstitutionNetworkFailureSkipsTheSubstitutionButStillProceeds() throws Exception {
        Request request = new Request.Builder()
            .url("https://api.example.com/data")
            .header("Api-Key", "Bearer header-secret")
            .build();
        when(chain.request()).thenReturn(request);
        when(service.getSubstitutionHeaders()).thenReturn(new HashMap<String, String>() {{
            put("Api-Key", "Bearer ");
        }});

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            Approov.TokenFetchResult fetchTokenResult = result(Approov.TokenFetchStatus.SUCCESS);
            Approov.TokenFetchResult substitutionResult = result(Approov.TokenFetchStatus.NO_NETWORK);
            when(service.fetchApproovTokenCached("https://api.example.com/data"))
                .thenReturn(fetchTokenResult);
            approov.when(() -> Approov.fetchSecureStringAndWait("header-secret", null))
                .thenReturn(substitutionResult);

            Response response = interceptor.intercept(chain);

            assertEquals("Bearer header-secret", response.request().header("Api-Key"));
            assertEquals("Bearer jwt-token", response.request().header("Approov-Token"));
            verify(chain).proceed(any());
        }
    }

    @Test
    public void queryParameterRejectionStopsTheRequest() throws Exception {
        Request request = request("https://api.example.com/data?secret=query-secret");
        when(chain.request()).thenReturn(request);
        when(service.getSubstitutionQueryParams()).thenReturn(new HashMap<String, Pattern>() {{
            put("secret", Pattern.compile("[\\?&]secret=([^&;]+)"));
        }});

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            Approov.TokenFetchResult fetchTokenResult = result(Approov.TokenFetchStatus.SUCCESS);
            Approov.TokenFetchResult substitutionResult = result(Approov.TokenFetchStatus.REJECTED);
            when(service.fetchApproovTokenCached("https://api.example.com/data?secret=query-secret"))
                .thenReturn(fetchTokenResult);
            approov.when(() -> Approov.fetchSecureStringAndWait("query-secret", null))
                .thenReturn(substitutionResult);

            IOException error = assertThrows(IOException.class, () -> interceptor.intercept(chain));

            assertTrue(error.getCause() instanceof ApproovRejectionException);
            verify(chain, never()).proceed(any());
        }
    }
}
