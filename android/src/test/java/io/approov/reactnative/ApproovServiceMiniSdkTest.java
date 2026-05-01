package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

import android.content.res.AssetManager;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.criticalblue.minisdk.testing.AttesterProxyController;
import com.criticalblue.approovsdk.Approov;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.network.NetworkingModule;
import com.facebook.react.modules.network.OkHttpClientProvider;

import okhttp3.OkHttpClient;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.CertificatePinner;
import okhttp3.Interceptor;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ApproovServiceMiniSdkTest {
    private static final MediaType APPLICATION_JSON = MediaType.get("application/json");
    private final String validInitialConfig = "#cb-ivol#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";

    private ReactApplicationContext reactContext;
    private ApproovService service;

    private static final class PromiseResult {
        final Object value;
        final String code;
        final String message;

        PromiseResult(Object value, String code, String message) {
            this.value = value;
            this.code = code;
            this.message = message;
        }
    }

    private static final class RecordingProceedingSigningMutator extends ApproovDefaultMessageSigning {
        private final java.util.List<String> installMessages = new java.util.ArrayList<>();

        @Override
        public boolean handleInterceptorFetchTokenResult(ApproovService service,
                Approov.TokenFetchResult approovResults, String url) throws ApproovException {
            Approov.TokenFetchStatus status = approovResults.getStatus();
            return (status == Approov.TokenFetchStatus.MITM_DETECTED)
                || (status == Approov.TokenFetchStatus.NO_APPROOV_SERVICE)
                || (status == Approov.TokenFetchStatus.SUCCESS)
                || ApproovServiceMutator.DEFAULT.handleInterceptorFetchTokenResult(service, approovResults, url);
        }

        @Override
        protected String getInstallMessageSignature(String message) {
            installMessages.add(message);
            return "MAYCAQECAS0=";
        }

        @Override
        protected byte[] decodeBase64(String base64) {
            return Base64.getDecoder().decode(base64);
        }

        java.util.List<String> getInstallMessages() {
            return installMessages;
        }
    }

    private static final class RecordingMessageSigningMutator extends ApproovDefaultMessageSigning {
        private final java.util.List<String> installMessages = new java.util.ArrayList<>();
        private final java.util.List<String> accountMessages = new java.util.ArrayList<>();

        @Override
        protected String getInstallMessageSignature(String message) {
            installMessages.add(message);
            return "MAYCAQECAS0=";
        }

        @Override
        protected String getAccountMessageSignature(String message) {
            accountMessages.add(message);
            return Base64.getEncoder().encodeToString("account-signature".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        protected byte[] decodeBase64(String base64) {
            return Base64.getDecoder().decode(base64);
        }

        java.util.List<String> getInstallMessages() {
            return installMessages;
        }

        java.util.List<String> getAccountMessages() {
            return accountMessages;
        }
    }

    private static final class FailingInstallMessageSigningMutator extends ApproovDefaultMessageSigning {
        private final java.util.List<String> installMessages = new java.util.ArrayList<>();

        @Override
        protected String getInstallMessageSignature(String message) {
            installMessages.add(message);
            return "";
        }

        java.util.List<String> getInstallMessages() {
            return installMessages;
        }
    }

    @Before
    public void setUp() throws Exception {
        Context appContext = ApplicationProvider.getApplicationContext();
        reactContext = mock(ReactApplicationContext.class);
        when(reactContext.getApplicationContext()).thenReturn(appContext);
        when(reactContext.getPackageName()).thenReturn(appContext.getPackageName());
        when(reactContext.getAssets()).thenReturn(appContext.getAssets());
        service = new ApproovService(reactContext);
        resetServiceState();
        AttesterProxyController.reset();
        AttesterProxyController.loadTokenSigningConfigFile("../core-service-layers-testing/mini-sdk/attester-proxy/token-signing-config.json");
    }

    @After
    public void tearDown() {
        AttesterProxyController.reset();
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        try {
            resetServiceState();
        } catch (Exception ignored) {
        }
    }

    @Test
    public void initializeIgnoresSameConfig() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));
    }

    @Test
    public void initializeRejectsDifferentConfig() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));

        PromiseResult rejected = awaitPromise(promise -> service.initialize(
            "#stg1006#aprv2stg-attest.api.approov.io#https://dev.approoval.com/token#dpcv6jv45r6LGC4E6ZXSMLhBVLrrhAoDcjizU/t9/Eg=",
            null,
            promise
        ));

        assertEquals("initialize", rejected.code);
        assertEquals("attempt to reinitialize with a different config", rejected.message);
        assertTrue(service.isInitialized());
        assertTrue(service.isApproovEnabled());
    }

    @Test
    public void initializeFailureRejectsAndKeepsLayerUninitialized() throws Exception {
        try (org.mockito.MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            approov.when(() -> Approov.initialize(any(Context.class), any(String.class), any(String.class), any(String.class)))
                .thenThrow(new IllegalArgumentException("bad config"));

            PromiseResult rejected = awaitPromise(promise -> service.initialize(validInitialConfig, null, promise));

            assertEquals("initialize", rejected.code);
            assertEquals("initialize IllegalArgument: bad config", rejected.message);
            assertFalse(service.isInitialized());
            assertFalse(service.isApproovEnabled());
        }
    }

    @Test
    public void initializeAcceptsReinitializeComment() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));

        awaitResolvedPromise(promise -> service.initialize(
            validInitialConfig,
            "reinit:account-switch",
            promise
        ));

        assertTrue(service.isInitialized());
        assertTrue(service.isApproovEnabled());
    }

    @Test
    public void initializeWithEmptyConfigKeepsLayerInitializedButDisablesApproov() throws Exception {
        AttesterProxyController.loadScenarioJson(scenarioJson(uniqueCaseName("rn"), "\"protectedDomains\": [\"" + getTargetHost() + "\"]"));
        awaitResolvedPromise(promise -> service.initialize("", null, promise));

        assertTrue(service.isInitialized());
        assertFalse(service.isApproovEnabled());
        assertEquals(0, ApproovCertificatePinner.build(service).getPins().size());

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        assertNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
    }

    @Test
    public void initializeWithEmptyConfigCanLaterEnableApproovWithValidConfig() throws Exception {
        AttesterProxyController.loadScenarioJson(scenarioJson(uniqueCaseName("rn"), "\"protectedDomains\": [\"" + getTargetHost() + "\"]"));
        awaitResolvedPromise(promise -> service.initialize("", null, promise));

        JSONObject unprotectedReply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        assertNull(getHeader(unprotectedReply, "Approov-Token"));
        assertNull(getHeader(unprotectedReply, "Approov-TraceID"));
        assertTrue(service.isInitialized());
        assertFalse(service.isApproovEnabled());

        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));

        assertTrue(service.isInitialized());
        assertTrue(service.isApproovEnabled());
        JSONObject protectedReply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        assertNotNull(getHeader(protectedReply, "Approov-Token"));
    }

    @Test
    public void uninitializedServiceCallsRejectProperly() throws Exception {
        // CHANGELOG 3.5.13: Ensure ApproovService calls to the native SDK are rejected if the service layer is not yet initialized.
        PromiseResult precheckRejected = awaitPromise(service::precheck);
        assertEquals("approov_error", precheckRejected.code);
        assertTrue(precheckRejected.message.contains("not initialized"));

        PromiseResult fetchTokenRejected = awaitPromise(promise -> service.fetchToken("example.com", promise));
        assertEquals("approov_error", fetchTokenRejected.code);
        assertTrue(fetchTokenRejected.message.contains("not initialized"));

        PromiseResult fetchSecureStringRejected = awaitPromise(promise -> service.fetchSecureString("key", null, promise));
        assertEquals("approov_error", fetchSecureStringRejected.code);
        assertTrue(fetchSecureStringRejected.message.contains("not initialized"));

        PromiseResult fetchCustomJWTRejected = awaitPromise(promise -> service.fetchCustomJWT("{}", promise));
        assertEquals("approov_error", fetchCustomJWTRejected.code);
        assertTrue(fetchCustomJWTRejected.message.contains("not initialized"));
    }

    @Test
    public void precheckTreatsUnknownKeyAsSuccess() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));
        awaitResolvedPromise(service::precheck);
    }

    @Test
    public void getDeviceIdReturnsMiniSdkDeviceId() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));
        PromiseResult resolved = awaitResolvedPromise(service::getDeviceID);
        assertEquals("daIvmEWBA2gvZny7a/RC/w==", resolved.value);
    }

    @Test
    public void getPinningDiagnosticsReportsInterceptorAndPinner() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-pinning-diag");
        Interceptor extraInterceptor = chain -> chain.proceed(chain.request());
        OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(new ApproovInterceptor(service))
            .addInterceptor(extraInterceptor)
            .certificatePinner(ApproovCertificatePinner.build(service))
            .build();

        try (org.mockito.MockedStatic<OkHttpClientProvider> okHttpClientProvider = mockStatic(OkHttpClientProvider.class)) {
            okHttpClientProvider.when(OkHttpClientProvider::getOkHttpClient).thenReturn(client);

            PromiseResult resolved = awaitResolvedPromise(service::getPinningDiagnostics);
            assertTrue(resolved.value instanceof ReadableMap);

            ReadableMap diagnostics = (ReadableMap) resolved.value;
            assertTrue(diagnostics.hasKey("isInterceptorPresent"));
            assertTrue(diagnostics.getBoolean("isInterceptorPresent"));
            assertTrue(diagnostics.hasKey("isPinnerPresent"));
            assertTrue(diagnostics.getBoolean("isPinnerPresent"));
            assertTrue(diagnostics.hasKey("interceptors"));

            ReadableArray interceptors = diagnostics.getArray("interceptors");
            assertNotNull(interceptors);
            boolean foundApproovInterceptor = false;
            for (int i = 0; i < interceptors.size(); i++) {
                if ("io.approov.reactnative.ApproovInterceptor".equals(interceptors.getString(i))) {
                    foundApproovInterceptor = true;
                    break;
                }
            }
            assertTrue(foundApproovInterceptor);
        }
    }

    @Test
    public void pinningAcceptAnyAllowsProtectedRequestsWithoutExplicitPins() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-pinning-accept-any");

        AttesterProxyController.setNextPinningDirectiveJson("{\"operation\": \"getPins\", \"acceptAny\": true}");
        service.notifyPinChangeListeners();

        assertEquals(0, ApproovCertificatePinner.build(service).getPins().size());

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        assertNotNull(getHeader(reply, "Approov-Token"));
    }

    @Test
    public void dynamicPinningUpdateRefreshesPinnerAndKeepsProtectedRequestsWorking() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-dynamic-pins");

        JSONObject firstReply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        assertNotNull(getHeader(firstReply, "Approov-Token"));

        AttesterProxyController.setNextPinningDirectiveJson("{\"operation\": \"getPins\", \"acceptAny\": true}");
        service.notifyPinChangeListeners();

        CertificatePinner refreshedPinner = ApproovCertificatePinner.build(service);
        assertEquals(0, refreshedPinner.getPins().size());

        JSONObject secondReply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        assertNotNull(getHeader(secondReply, "Approov-Token"));
    }

    @Test
    public void fetchWithApproovAddsTokenTraceBindingHashAndSubstitutions() throws Exception {
        reinitializeServiceWithScenario(
            "\"protectedDomains\": [\"" + getTargetHost() + "\"],"
                + "\"initialSecureStrings\": {"
                + "\"header-key\": \"header-secret\","
                + "\"query-key\": \"query-secret\""
                + "}",
            "reinit-substitutions"
        );

        service.setBindingHeader("Authorization");
        service.addSubstitutionHeader("Api-Key", "");
        service.addSubstitutionQueryParam("api_key");

        Request request = new Request.Builder()
            .url(getTargetURL() + "?api_key=query-key")
            .header("Authorization", "Bearer oauth-token")
            .header("Api-Key", "header-key")
            .build();
        JSONObject reply = fetchNetworkReply(request);
        String token = getHeader(reply, "Approov-Token");

        assertNotNull(token);
        assertNotNull(getHeader(reply, "Approov-TraceID"));
        assertEquals("header-secret", getHeader(reply, "Api-Key"));
        assertTrue(reply.getString("url").contains("api_key=query-secret"));

        JSONObject payload = decodeJWTBody(token.replaceFirst("^Bearer\\s+", ""));
        assertEquals(sha256Base64("Bearer oauth-token"), payload.getString("pay"));
    }

    @Test
    public void fetchWithApproovLeavesHeaderPlaceholderWhenSecureStringResolvesEmpty() throws Exception {
        reinitializeServiceWithScenario(
            "\"protectedDomains\": [\"" + getTargetHost() + "\"],"
                + "\"fetchSecureString\": ["
                + "{\"key\":\"header-key\",\"status\":\"SUCCESS\",\"secureString\":\"\"}"
                + "]",
            "reinit-empty-header-substitution");

        service.addSubstitutionHeader("Api-Key", "Bearer ");

        Request request = new Request.Builder()
            .url(getTargetURL())
            .header("Api-Key", "Bearer header-key")
            .build();
        JSONObject reply = fetchNetworkReply(request);

        assertEquals("Bearer header-key", getHeader(reply, "Api-Key"));
    }

    @Test
    public void fetchWithApproovLeavesQueryPlaceholderWhenSecureStringResolvesEmpty() throws Exception {
        reinitializeServiceWithScenario(
            "\"protectedDomains\": [\"" + getTargetHost() + "\"],"
                + "\"fetchSecureString\": ["
                + "{\"key\":\"query-key\",\"status\":\"SUCCESS\",\"secureString\":\"\"}"
                + "]",
            "reinit-empty-query-substitution");

        service.addSubstitutionQueryParam("api_key");

        Request request = new Request.Builder()
            .url(getTargetURL() + "?api_key=query-key")
            .build();
        JSONObject reply = fetchNetworkReply(request);

        assertTrue(reply.getString("url").contains("api_key=query-key"));
    }
    
    @Test
    public void setDataHashInTokenDirect() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-data-hash");
        
        // Set data hash manually
        awaitResolvedPromise(promise -> service.setDataHashInToken("manual-data-hash", promise));

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        String token = getHeader(reply, "Approov-Token");
        assertNotNull(token);

        JSONObject payload = decodeJWTBody(token.replaceFirst("^Bearer\\s+", ""));
        assertEquals(sha256Base64("manual-data-hash"), payload.getString("pay"));
    }

    @Test
    public void setDataHashInTokenEmptyClearsPayClaim() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-data-hash-empty");

        awaitResolvedPromise(promise -> service.setDataHashInToken("", promise));

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        String token = getHeader(reply, "Approov-Token");
        assertNotNull(token);

        JSONObject payload = decodeJWTBody(token.replaceFirst("^Bearer\\s+", ""));
        assertFalse(payload.has("pay"));
    }

    @Test
    public void setDataHashInTokenNullRejectsAndDoesNotAffectWorkerRequest() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-data-hash-null");

        PromiseResult rejected = awaitPromise(promise -> service.setDataHashInToken(null, promise));
        assertEquals("setDataHashInToken", rejected.code);
        assertTrue(rejected.message.contains("IllegalArgument"));

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        String token = getHeader(reply, "Approov-Token");
        assertNotNull(token);

        JSONObject payload = decodeJWTBody(token.replaceFirst("^Bearer\\s+", ""));
        assertFalse(payload.has("pay"));
    }

    @Test
    public void setBindingHeaderMissing() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-binding-missing");
        service.setBindingHeader("X-Custom-Binding");

        // Request WITHOUT X-Custom-Binding
        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        String token = getHeader(reply, "Approov-Token");
        assertNotNull(token);

        JSONObject payload = decodeJWTBody(token.replaceFirst("^Bearer\\s+", ""));
        assertFalse(payload.has("pay"));
    }

    @Test
    public void setBindingHeaderEmptyDoesNotAddPayClaim() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-binding-empty");
        service.setBindingHeader("X-Custom-Binding");

        JSONObject reply = fetchNetworkReply(new Request.Builder()
            .url(getTargetURL())
            .header("X-Custom-Binding", "")
            .build());
        String token = getHeader(reply, "Approov-Token");
        assertNotNull(token);

        JSONObject payload = decodeJWTBody(token.replaceFirst("^Bearer\\s+", ""));
        assertFalse(payload.has("pay"));
    }


    @Test
    public void fetchWithApproovUsesCustomHeaders() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-target-host");
        service.setTokenHeader("Custom-Token", "Prefix ");
        service.setTraceIDHeader("Custom-TraceID");

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        String token = getHeader(reply, "Custom-Token");
        String traceId = getHeader(reply, "Custom-TraceID");

        assertNotNull(token);
        assertTrue(token.startsWith("Prefix "));
        assertNotNull(traceId);
        assertNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
    }

    @Test
    public void fetchWithApproovCanDisableTraceID() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-target-host");
        service.setTraceIDHeader(null);

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        assertNotNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
    }

    @Test
    public void fetchWithApproovInjectsStatusWhenNoToken() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-target-host");
        service.setUseApproovStatusIfNoToken(true);
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"NO_APPROOV_SERVICE\"}}");

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        assertEquals("NO_APPROOV_SERVICE", getHeader(reply, "Approov-Token"));
    }

    @Test
    public void fetchWithApproovInjectsStatusWithCustomMutator() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-target-host");
        service.setUseApproovStatusIfNoToken(true);

        // Use a custom mutator that allows POOR_NETWORK to proceed
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public boolean handleInterceptorFetchTokenResult(ApproovService service, Approov.TokenFetchResult result, String url) {
                if (result.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) {
                    return true;
                }
                return false;
            }
        });

        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"POOR_NETWORK\"}}");

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        assertEquals("POOR_NETWORK", getHeader(reply, "Approov-Token"));
    }

    @Test
    public void fetchWithApproovCanSignProceedingFailureStatusesWhenStatusIsUsedAsToken() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-target-host");
        service.setUseApproovStatusIfNoToken(true);
        RecordingProceedingSigningMutator signer = new RecordingProceedingSigningMutator();
        signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
        ApproovService.setServiceMutator(signer);

        AttesterProxyController.setNextAttestationDirectiveJson(
            "{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"MITM_DETECTED\"}}");

        Request request = new Request.Builder()
            .url(getTargetURL())
            .post(RequestBody.create(APPLICATION_JSON, "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer oauth-token")
            .header("Content-Type", "application/json")
            .build();

        JSONObject reply = fetchNetworkReply(request);

        assertEquals("MITM_DETECTED", getHeader(reply, "Approov-Token"));
        assertNotNull(getHeader(reply, "Approov-TraceID"));
        assertNotNull(getHeader(reply, "Signature"));
        assertNotNull(getHeader(reply, "Signature-Input"));
        assertNotNull(getHeader(reply, "Content-Digest"));
        assertTrue(signer.getInstallMessages().size() == 1);
        String message = signer.getInstallMessages().get(0);
        assertTrue(message.contains("\"approov-token\""));
        assertTrue(message.contains("\"approov-traceid\""));
    }

    @Test
    public void fetchWithApproovAddsInstallMessageSigningHeaders() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-install-signing");

        RecordingMessageSigningMutator signer = new RecordingMessageSigningMutator();
        signer.setDefaultFactory(
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setUseInstallMessageSigning()
        );
        ApproovService.setServiceMutator(signer);

        Request request = new Request.Builder()
            .url(getTargetURL())
            .post(RequestBody.create(APPLICATION_JSON, "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer oauth-token")
            .header("Content-Type", "application/json")
            .build();

        JSONObject reply = fetchNetworkReply(request);

        assertNotNull(getHeader(reply, "Approov-Token"));
        assertNotNull(getHeader(reply, "Approov-TraceID"));
        assertNotNull(getHeader(reply, "Content-Digest"));
        assertTrue(getHeader(reply, "Signature-Input").startsWith("install="));
        assertFalse(getHeader(reply, "Signature-Input").contains("account="));
        assertTrue(getHeader(reply, "Signature").startsWith("install="));
        assertFalse(getHeader(reply, "Signature").contains("account="));
        assertEquals(1, signer.getInstallMessages().size());
        assertEquals(0, signer.getAccountMessages().size());
        assertTrue(signer.getInstallMessages().get(0).contains("\"approov-token\""));
    }

    @Test
    public void fetchWithApproovAddsAccountMessageSigningHeaders() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-account-signing");

        RecordingMessageSigningMutator signer = new RecordingMessageSigningMutator();
        signer.setDefaultFactory(
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setUseAccountMessageSigning()
        );
        ApproovService.setServiceMutator(signer);

        Request request = new Request.Builder()
            .url(getTargetURL())
            .post(RequestBody.create(APPLICATION_JSON, "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer oauth-token")
            .header("Content-Type", "application/json")
            .build();

        JSONObject reply = fetchNetworkReply(request);

        assertNotNull(getHeader(reply, "Approov-Token"));
        assertNotNull(getHeader(reply, "Approov-TraceID"));
        assertNotNull(getHeader(reply, "Content-Digest"));
        assertTrue(getHeader(reply, "Signature-Input").startsWith("account="));
        assertFalse(getHeader(reply, "Signature-Input").contains("install="));
        assertTrue(getHeader(reply, "Signature").startsWith("account="));
        assertFalse(getHeader(reply, "Signature").contains("install="));
        assertEquals(0, signer.getInstallMessages().size());
        assertEquals(1, signer.getAccountMessages().size());
        assertTrue(signer.getAccountMessages().get(0).contains("\"approov-token\""));
    }

    @Test
    public void fetchWithApproovSkipsSigningWhenInstallSignatureIsUnavailable() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-signing-fallback");

        FailingInstallMessageSigningMutator signer = new FailingInstallMessageSigningMutator();
        signer.setDefaultFactory(
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setUseInstallMessageSigning()
        );
        ApproovService.setServiceMutator(signer);

        Request request = new Request.Builder()
            .url(getTargetURL())
            .post(RequestBody.create(APPLICATION_JSON, "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer oauth-token")
            .header("Content-Type", "application/json")
            .build();

        JSONObject reply = fetchNetworkReply(request);

        assertNotNull(getHeader(reply, "Approov-Token"));
        assertNotNull(getHeader(reply, "Approov-TraceID"));
        assertNull(getHeader(reply, "Content-Digest"));
        assertNull(getHeader(reply, "Signature"));
        assertNull(getHeader(reply, "Signature-Input"));
        assertEquals(1, signer.getInstallMessages().size());
        assertTrue(signer.getInstallMessages().get(0).contains("\"approov-token\""));
    }

    @Test
    public void fetchWithApproovOnlyAddsDigestWhenSignedRequestHasABody() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-digest-body");

        RecordingMessageSigningMutator signer = new RecordingMessageSigningMutator();
        signer.setDefaultFactory(
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setUseInstallMessageSigning()
        );
        ApproovService.setServiceMutator(signer);

        JSONObject postReply = fetchNetworkReply(new Request.Builder()
            .url(getTargetURL())
            .post(RequestBody.create(APPLICATION_JSON, "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer oauth-token")
            .header("Content-Type", "application/json")
            .build());
        assertNotNull(getHeader(postReply, "Content-Digest"));
        assertNotNull(getHeader(postReply, "Signature"));

        JSONObject getReply = fetchNetworkReply(new Request.Builder()
            .url(getTargetURL())
            .get()
            .header("Authorization", "Bearer oauth-token")
            .build());
        assertNull(getHeader(getReply, "Content-Digest"));
        assertNotNull(getHeader(getReply, "Signature"));
        assertNotNull(getHeader(getReply, "Signature-Input"));
    }

    @Test
    public void fetchWithApproovReplacesStaleSigningHeadersWithoutDuplicatingThem() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-single-signature");

        RecordingMessageSigningMutator signer = new RecordingMessageSigningMutator();
        signer.setDefaultFactory(
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setUseInstallMessageSigning()
        );
        ApproovService.setServiceMutator(signer);

        Request request = new Request.Builder()
            .url(getTargetURL())
            .post(RequestBody.create(APPLICATION_JSON, "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", "Bearer oauth-token")
            .header("Content-Type", "application/json")
            .header("Signature", "stale-signature")
            .header("Signature-Input", "stale-input")
            .header("Signature-Base-Digest", "stale-digest")
            .build();

        JSONObject reply = fetchNetworkReply(request);

        String signature = getHeader(reply, "Signature");
        String signatureInput = getHeader(reply, "Signature-Input");
        assertNotNull(signature);
        assertNotNull(signatureInput);
        assertFalse(signature.contains("stale-signature"));
        assertFalse(signatureInput.contains("stale-input"));
        assertEquals(1, countOccurrences("install=:", signature));
        assertEquals(1, countOccurrences("install=", signatureInput));
    }

    @Test
    public void fetchWithApproovProceedsWithoutTokenForNoApproovService() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-target-host");
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"NO_APPROOV_SERVICE\"}}");

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        assertNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
    }

    @Test
    public void fetchWithApproovOmitsEmptyTokenAndTraceHeadersWhenProceedingWithoutArtifacts() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-target-host");
        AttesterProxyController.setNextAttestationDirectiveJson(
            "{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"NO_APPROOV_SERVICE\",\"token\":\"\",\"traceID\":\"\"}}"
        );

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        assertNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
    }

    @Test
    public void fetchWithApproovLeavesUnprotectedWorkerUnmodified() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-target-host");

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getUnprotectedURL()).build());
        assertNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
    }

    @Test
    public void unprotectedDomainsAreUnaffectedByPinningFailures() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-unprotected-pinning");
        AttesterProxyController.setNextPinningDirectiveJson("{\"operation\": \"getPins\", \"shouldFail\": true}");

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getUnprotectedURL()).build());
        assertNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
        assertTrue(reply.getString("url").startsWith(getUnprotectedURL()));
    }

    @Test
    public void exclusionUrlForProtectedWorkerLeavesRequestUnmodified() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-target-host");
        service.addExclusionURLRegex("^.*excluded.*$");

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL() + "/excluded").build());
        assertNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
        assertTrue(reply.getString("url").contains("/excluded"));
    }

    @Test
    public void excludedProtectedUrlGetsPinningWithoutTokenTraceOrSigning() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-excluded-pinning-only");
        service.addExclusionURLRegex("^.*excluded.*$");

        Request request = new Request.Builder()
            .url(getTargetURL() + "/excluded")
            .post(RequestBody.create("{\"hello\":\"world\"}", APPLICATION_JSON))
            .build();
        JSONObject reply = fetchNetworkReply(request);

        assertNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
        assertNull(getHeader(reply, "Content-Digest"));
        assertNull(getHeader(reply, "Signature"));
        assertNull(getHeader(reply, "Signature-Input"));
    }

    @Test
    public void excludedProtectedUrlLeavesRequestArtifactFree() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-excluded-pinning-failure");
        service.addExclusionURLRegex("^.*excluded.*$");

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL() + "/excluded").build());
        assertNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
    }

    @Test
    public void removeSubstitutionHeaderRevertsBehavior() throws Exception {
        reinitializeServiceWithScenario(
            "\"protectedDomains\": [\"" + getTargetHost() + "\"],"
                + "\"initialSecureStrings\": {\"header-key\": \"header-secret\"}",
            "reinit-header-sub"
        );
        service.addSubstitutionHeader("Api-Key", "");

        // 1. Verify substitution happens
        JSONObject reply1 = fetchNetworkReply(new Request.Builder()
            .url(getTargetURL())
            .header("Api-Key", "header-key")
            .build());
        assertEquals("header-secret", getHeader(reply1, "Api-Key"));

        // 2. Remove substitution and verify it reverts
        service.removeSubstitutionHeader("Api-Key");
        JSONObject reply2 = fetchNetworkReply(new Request.Builder()
            .url(getTargetURL())
            .header("Api-Key", "header-key")
            .build());
        assertEquals("header-key", getHeader(reply2, "Api-Key"));
    }

    @Test
    public void removeSubstitutionQueryParamRevertsBehavior() throws Exception {
        reinitializeServiceWithScenario(
            "\"protectedDomains\": [\"" + getTargetHost() + "\"],"
                + "\"initialSecureStrings\": {\"query-key\": \"query-secret\"}",
            "reinit-query-sub"
        );
        service.addSubstitutionQueryParam("api_key");

        // 1. Verify substitution happens
        JSONObject reply1 = fetchNetworkReply(new Request.Builder()
            .url(getTargetURL() + "?api_key=query-key")
            .build());
        assertTrue(reply1.getString("url").contains("api_key=query-secret"));

        // 2. Remove substitution and verify it reverts
        service.removeSubstitutionQueryParam("api_key");
        JSONObject reply2 = fetchNetworkReply(new Request.Builder()
            .url(getTargetURL() + "?api_key=query-key")
            .build());
        assertTrue(reply2.getString("url").contains("api_key=query-key"));
    }

    @Test
    public void removeExclusionURLRegexRevertsBehavior() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-target-host");
        String regex = "^.*excluded.*$";
        service.addExclusionURLRegex(regex);

        // 1. Verify exclusion happens (no token)
        JSONObject reply1 = fetchNetworkReply(new Request.Builder().url(getTargetURL() + "/excluded").build());
        assertNull(getHeader(reply1, "Approov-Token"));

        // 2. Remove exclusion and verify it is protected again
        service.removeExclusionURLRegex(regex);
        JSONObject reply2 = fetchNetworkReply(new Request.Builder().url(getTargetURL() + "/excluded").build());
        assertNotNull(getHeader(reply2, "Approov-Token"));
    }

    // =========================================================================
    // $7 Request Caching Validation
    // =========================================================================



    @Test
    public void fetchTokenReturnsSignedTokenWithExpectedClaims() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-target-host");

        PromiseResult resolved = awaitResolvedPromise(promise -> service.fetchToken(getTargetURL(), promise));
        String token = (String) resolved.value;
        JSONObject payload = decodeJWTBody(token);

        assertEquals("81.149.55.236", payload.getString("ip"));
        assertEquals("daIvmEWBA2gvZny7a/RC/w==", payload.getString("did"));
        assertEquals("j3AWy6", payload.getString("mskid"));
        assertEquals("IXPSB7TRK26LXE3M", payload.getString("arc"));
    }

    @Test
    public void fetchSecureStringReturnsConfiguredValue() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\":\"fetchSecureString\",\"response\":{\"status\":\"SUCCESS\",\"secureString\":\"mini-secret\"}}");
        PromiseResult configured = awaitResolvedPromise(promise -> service.fetchSecureString("api-key", null, promise));
        assertEquals("mini-secret", configured.value);
    }

    @Test
    public void fetchSecureStringWithUnknownKeyResolvesNull() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\":\"fetchSecureString\",\"response\":{\"status\":\"UNKNOWN_KEY\"}}");
        PromiseResult missing = awaitResolvedPromise(promise -> service.fetchSecureString("missing-key", null, promise));
        assertNull(missing.value);
    }

    @Test
    public void fetchSecureStringWithEmptyKeyRejects() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));

        PromiseResult rejected = awaitPromise(promise -> service.fetchSecureString("", null, promise));

        assertEquals("fetchSecureString", rejected.code);
        assertTrue(rejected.message.startsWith("IllegalArgument:"));
    }

    @Test
    public void fetchSecureStringWithOverlongKeyRejects() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));

        PromiseResult rejected = awaitPromise(promise -> service.fetchSecureString(repeat("k", 65), null, promise));

        assertEquals("fetchSecureString", rejected.code);
        assertTrue(rejected.message.startsWith("IllegalArgument:"));
    }

    @Test
    public void fetchSecureStringWithNilKeyRejects() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));

        PromiseResult rejected = awaitPromise(promise -> service.fetchSecureString(null, null, promise));

        assertEquals("fetchSecureString", rejected.code);
        assertTrue(rejected.message.startsWith("IllegalArgument:"));
    }

    @Test
    public void fetchWithApproovSubstitutesShortAndLongSecureStringValues() throws Exception {
        String longValue = repeat("v", 2048);
        reinitializeServiceWithScenario(
            "\"protectedDomains\": [\"" + getTargetHost() + "\"],"
                + "\"fetchSecureString\": ["
                + "{\"key\":\"header-key\",\"status\":\"SUCCESS\",\"secureString\":\"x\"},"
                + "{\"key\":\"query-key\",\"status\":\"SUCCESS\",\"secureString\":" + JSONObject.quote(longValue) + "}"
                + "]",
            "reinit-substitution-ranges");
        service.addSubstitutionHeader("Api-Key", "");
        service.addSubstitutionQueryParam("api_key");

        JSONObject reply = fetchNetworkReply(new Request.Builder()
            .url(getTargetURL() + "?api_key=query-key")
            .header("Api-Key", "header-key")
            .build());

        assertEquals("x", getHeader(reply, "Api-Key"));
        assertTrue(reply.getString("url").contains("api_key=" + longValue));
    }

    @Test
    public void fetchCustomJwtReturnsSignedPayload() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));
        PromiseResult resolved = awaitResolvedPromise(promise -> service.fetchCustomJWT("{\"role\":\"tester\"}", promise));
        JSONObject payload = decodeJWTBody((String) resolved.value);

        assertEquals("tester", payload.getString("role"));
        assertFalse(payload.has("exp"));
        assertFalse(payload.has("did"));
    }

    @Test
    public void fetchCustomJwtSupportsEighteenKilobytePayload() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));
        String largeValue = repeat("a", 18 * 1024);
        String payloadJson = "{\"blob\":" + JSONObject.quote(largeValue) + "}";

        PromiseResult resolved = awaitResolvedPromise(promise -> service.fetchCustomJWT(payloadJson, promise));
        JSONObject payload = decodeJWTBody((String) resolved.value);

        assertEquals(18 * 1024, payload.getString("blob").length());
        assertEquals(largeValue, payload.getString("blob"));
    }

    @Test
    public void fetchCustomJwtWithMalformedPayloadRejects() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));
        
        PromiseResult rejected = awaitPromise(promise -> service.fetchCustomJWT("{\"role\":", promise));

        assertEquals("fetchCustomJWT", rejected.code);
        assertTrue(rejected.message.contains("IllegalArgument"));
    }

    @Test
    public void fetchCustomJwtRejectsWhenApproovServiceIsDisabled() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));
        AttesterProxyController.setNextAttestationDirectiveJson(
            "{\"operation\":\"fetchCustomJWT\",\"response\":{\"status\":\"NO_APPROOV_SERVICE\"}}");

        PromiseResult rejected = awaitPromise(promise -> service.fetchCustomJWT("{\"role\":\"tester\"}", promise));

        assertEquals("fetchCustomJWT", rejected.code);
        assertEquals("fetchCustomJWT: NO_APPROOV_SERVICE", rejected.message);
    }

    @Test
    public void fetchCustomJwtRejectsOnAttestationRejection() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));
        AttesterProxyController.setNextAttestationDirectiveJson(
            "{\"operation\":\"fetchCustomJWT\",\"response\":{\"status\":\"REJECTED\",\"arc\":\"IXPSB7TRK26LXE3M\",\"rejectionReasons\":\"policy\"}}");

        PromiseResult rejected = awaitPromise(promise -> service.fetchCustomJWT("{\"role\":\"tester\"}", promise));

        assertEquals("precheck", rejected.code);
        assertEquals("fetchCustomJWT: REJECTED IXPSB7TRK26LXE3M policy", rejected.message);
    }

    @Test
    public void fetchCustomJwtRejectsOnNetworkFailure() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, null, promise));
        AttesterProxyController.setNextAttestationDirectiveJson(
            "{\"operation\":\"fetchCustomJWT\",\"response\":{\"status\":\"NO_NETWORK\"}}");

        PromiseResult rejected = awaitPromise(promise -> service.fetchCustomJWT("{\"role\":\"tester\"}", promise));

        assertEquals("fetchCustomJWT", rejected.code);
        assertEquals("fetchCustomJWT: NO_NETWORK", rejected.message);
    }

    private JSONObject fetchNetworkReply(Request request) throws Exception {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        new ApproovClientBuilder(service, null).apply(builder);
        OkHttpClient client = builder.build();
        try (Response response = client.newCall(request).execute()) {
            assertTrue(response.isSuccessful());
            return new JSONObject(response.body().string());
        }
    }

    private String getHeader(JSONObject reply, String key) throws Exception {
        if (!reply.has("headers")) {
            return null;
        }
        JSONObject headers = reply.getJSONObject("headers");
        String lowerKey = key.toLowerCase();
        if (headers.has(lowerKey)) {
            return headers.get(lowerKey).toString();
        }
        if (headers.has(key)) {
            return headers.get(key).toString();
        }
        return null;
    }

    private int countOccurrences(String needle, String haystack) {
        int count = 0;
        int start = 0;
        while (true) {
            int found = haystack.indexOf(needle, start);
            if (found < 0) {
                return count;
            }
            count++;
            start = found + needle.length();
        }
    }

    private PromiseResult awaitResolvedPromise(PromiseAction action) throws Exception {
        PromiseResult result = awaitPromise(action);
        if (result.code != null) {
            fail("Expected resolve but got rejection: " + result.code + " " + result.message);
        }
        return result;
    }

    private PromiseResult awaitPromise(PromiseAction action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<PromiseResult> resultRef = new AtomicReference<>();

        Promise promise = mock(Promise.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            resultRef.set(new PromiseResult(invocation.getArgument(0), null, null));
            latch.countDown();
            return null;
        }).when(promise).resolve(org.mockito.ArgumentMatchers.nullable(Object.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            resultRef.set(new PromiseResult(null, invocation.getArgument(0), invocation.getArgument(1)));
            latch.countDown();
            return null;
        }).when(promise).reject(any(String.class), any(String.class), any(WritableMap.class));

        action.run(promise);
        assertTrue("Timed out waiting for promise", latch.await(5, TimeUnit.SECONDS));
        return resultRef.get();
    }

    private String repeat(String value, int count) {
        return value.repeat(count);
    }

    private void reinitializeServiceWithScenario(String body, String comment) throws Exception {
        AttesterProxyController.reset();
        AttesterProxyController.loadTokenSigningConfigFile("../core-service-layers-testing/mini-sdk/attester-proxy/token-signing-config.json");
        AttesterProxyController.loadScenarioJson(scenarioJson(uniqueCaseName("rn"), body));
        service = new ApproovService(reactContext);
        resetServiceState();
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, comment, promise));
    }

    private String getTargetURL() {
        String url = System.getenv("TESTING_REPLY_URL");
        return (url != null) ? url : "https://replay.ivol.workers.dev";
    }

    private String getUnprotectedURL() {
        String url = System.getenv("TESTING_REPLY_URL_UNPROTECTED");
        return (url != null) ? url : "https://replay-unprotected.ivol.workers.dev";
    }

    private String getTargetHost() {
        String url = getTargetURL();
        return url.replace("https://", "").split("/")[0];
    }

    private void resetServiceState() throws Exception {
        setStaticField("isInitialized", false);
        setStaticField("initialConfig", null);
        setInstanceField("earliestNetworkRequestTime", 0L);
        setInstanceField("pendingPrefetch", false);
    }

    private void setStaticField(String name, Object value) throws Exception {
        Field field = ApproovService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private void setInstanceField(String name, Object value) throws Exception {
        Field field = ApproovService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private interface PromiseAction {
        void run(Promise promise);
    }

    private String scenarioJson(String caseName, String body) {
        return "{"
            + "\"activeCase\":\"" + caseName + "\","
            + "\"cases\":{\"" + caseName + "\":{" + body + "}}"
            + "}";
    }

    private String uniqueCaseName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().toLowerCase();
    }

    private JSONObject decodeJWTBody(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        byte[] bytes = Base64.getUrlDecoder().decode(parts[1]);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private String sha256Base64(String data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return Base64.getEncoder().encodeToString(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
    }

}
