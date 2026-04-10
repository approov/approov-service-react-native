package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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
import okhttp3.Request;
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
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ApproovServiceMiniSdkTest {
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
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, promise));
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, promise));
    }

    @Test
    public void initializeWithEmptyConfigKeepsLayerInitializedButDisablesApproov() throws Exception {
        AttesterProxyController.loadScenarioJson(scenarioJson(uniqueCaseName("rn"), "\"protectedDomains\": [\"" + getTargetHost() + "\"]"));
        awaitResolvedPromise(promise -> service.initialize("", promise));

        assertTrue(service.isInitialized());
        assertFalse(service.isApproovEnabled());
        assertEquals(0, getPinCount(ApproovCertificatePinner.build(service)));

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL()).build());
        assertNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
    }

    @Test
    public void precheckTreatsUnknownKeyAsSuccess() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, promise));
        awaitResolvedPromise(service::precheck);
    }

    @Test
    public void getDeviceIdReturnsMiniSdkDeviceId() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, promise));
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
    public void fetchWithApproovProceedsWithoutTokenForNoApproovService() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-target-host");
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\":\"fetchApproovToken\",\"response\":{\"status\":\"NO_APPROOV_SERVICE\"}}");

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
    public void exclusionUrlForProtectedWorkerLeavesRequestUnmodified() throws Exception {
        reinitializeServiceWithScenario("\"protectedDomains\": [\"" + getTargetHost() + "\"]", "reinit-target-host");
        service.addExclusionURLRegex("^.*excluded.*$");

        JSONObject reply = fetchNetworkReply(new Request.Builder().url(getTargetURL() + "/excluded").build());
        assertNull(getHeader(reply, "Approov-Token"));
        assertNull(getHeader(reply, "Approov-TraceID"));
        assertTrue(reply.getString("url").contains("/excluded"));
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
    public void fetchSecureStringReturnsConfiguredValueAndUnknownKeyResolvesNull() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, promise));
        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\":\"fetchSecureString\",\"response\":{\"status\":\"SUCCESS\",\"secureString\":\"mini-secret\"}}");
        PromiseResult configured = awaitResolvedPromise(promise -> service.fetchSecureString("api-key", null, promise));
        assertEquals("mini-secret", configured.value);

        AttesterProxyController.setNextAttestationDirectiveJson("{\"operation\":\"fetchSecureString\",\"response\":{\"status\":\"UNKNOWN_KEY\"}}");
        PromiseResult missing = awaitResolvedPromise(promise -> service.fetchSecureString("missing-key", null, promise));
        assertNull(missing.value);
    }

    @Test
    public void fetchCustomJwtReturnsSignedPayload() throws Exception {
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, promise));
        PromiseResult resolved = awaitResolvedPromise(promise -> service.fetchCustomJWT("{\"role\":\"tester\"}", promise));
        JSONObject payload = decodeJWTBody((String) resolved.value);

        assertEquals("tester", payload.getString("role"));
        assertFalse(payload.has("exp"));
        assertFalse(payload.has("did"));
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

    private void reinitializeServiceWithScenario(String body, String comment) throws Exception {
        AttesterProxyController.reset();
        AttesterProxyController.loadTokenSigningConfigFile("../core-service-layers-testing/mini-sdk/attester-proxy/token-signing-config.json");
        AttesterProxyController.loadScenarioJson(scenarioJson(uniqueCaseName("rn"), body));
        service = new ApproovService(reactContext);
        resetServiceState();
        awaitResolvedPromise(promise -> service.initialize(validInitialConfig, promise));
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

    private int getPinCount(CertificatePinner pinner) throws Exception {
        Field pinsField = CertificatePinner.class.getDeclaredField("pins");
        pinsField.setAccessible(true);
        Object pins = pinsField.get(pinner);
        return (pins instanceof Set) ? ((Set<?>) pins).size() : 0;
    }
}
