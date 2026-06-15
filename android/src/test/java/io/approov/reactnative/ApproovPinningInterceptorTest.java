package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.criticalblue.approovsdk.Approov;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

// Unit tests for the ApproovPinningInterceptor network interceptor. The actual peer-certificate check
// against live TLS handshakes is exercised end-to-end by the Mini-SDK tests (which make real pinned
// requests); these tests cover pin building/refresh and the interceptor control-flow branches that do
// not require real certificates.
public class ApproovPinningInterceptorTest {

    private static final String PIN_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String PIN_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA=";

    private ApproovServiceMutator originalMutator;

    @Before
    public void setUp() {
        // capture the global mutator so tests that swap it can restore it afterwards
        originalMutator = ApproovService.getServiceMutator();
    }

    @After
    public void tearDown() {
        ApproovService.setServiceMutator(originalMutator);
    }

    @Test
    public void buildsPinsFromApproovWhenEnabled() throws Exception {
        ApproovService service = mock(ApproovService.class);
        when(service.isApproovEnabled()).thenReturn(true);

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            approov.when(() -> Approov.getPins("public-key-sha256"))
                .thenReturn(Collections.singletonMap("example.com", Collections.singletonList(PIN_B)));

            ApproovPinningInterceptor interceptor = new ApproovPinningInterceptor(service);

            CertificatePinner pinner = interceptor.getCertificatePinner();
            assertEquals(1, pinner.getPins().size());
            assertTrue(hasPinHash(pinner, PIN_B));
        }
    }

    @Test
    public void buildsEmptyPinnerWhenApproovNotEnabled() {
        ApproovService service = mock(ApproovService.class);
        when(service.isApproovEnabled()).thenReturn(false);

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovPinningInterceptor interceptor = new ApproovPinningInterceptor(service);

            assertEquals(0, interceptor.getCertificatePinner().getPins().size());
            // querying pins before initialization would fail, so it must be skipped entirely
            approov.verify(() -> Approov.getPins(anyString()), never());
        }
    }

    @Test
    public void rebuildingPinsReflectsUpdatedConfiguration() throws Exception {
        ApproovService service = mock(ApproovService.class);
        when(service.isApproovEnabled()).thenReturn(true);

        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            approov.when(() -> Approov.getPins("public-key-sha256"))
                .thenReturn(Collections.singletonMap("example.com", Collections.singletonList(PIN_B)))
                .thenReturn(Collections.singletonMap("example.com", Collections.singletonList(PIN_A)));

            ApproovPinningInterceptor interceptor = new ApproovPinningInterceptor(service);
            assertTrue(hasPinHash(interceptor.getCertificatePinner(), PIN_B));

            interceptor.buildPins();
            assertTrue(hasPinHash(interceptor.getCertificatePinner(), PIN_A));
        }
    }

    @Test
    public void mutatorCanSkipPinningProcessing() throws Exception {
        ApproovService service = mock(ApproovService.class);
        when(service.isApproovEnabled()).thenReturn(false);
        ApproovPinningInterceptor interceptor = new ApproovPinningInterceptor(service);

        // a mutator that declines pinning means the request is forwarded without inspecting the
        // connection/handshake at all
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public boolean handlePinningShouldProcessRequest(Request request) {
                return false;
            }
        });

        Request request = new Request.Builder().url("https://api.example.com/").build();
        Response response = mock(Response.class);
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any())).thenReturn(response);

        assertSame(response, interceptor.intercept(chain));
        // pinning was skipped, so the connection was never consulted
        verify(chain, never()).connection();
    }

    @Test
    public void httpsRequestWithoutHandshakeThrowsApproovNetworkException() {
        ApproovService service = mock(ApproovService.class);
        when(service.isApproovEnabled()).thenReturn(false);
        ApproovPinningInterceptor interceptor = new ApproovPinningInterceptor(service);

        // default mutator allows pinning; for an https request a missing handshake is anomalous and
        // must fail closed
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);

        Request request = new Request.Builder().url("https://api.example.com/").build();
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.connection()).thenReturn(null);

        assertThrows(ApproovNetworkException.class, () -> interceptor.intercept(chain));
    }

    @Test
    public void cleartextRequestWithoutHandshakeProceedsWithoutPinning() throws Exception {
        ApproovService service = mock(ApproovService.class);
        when(service.isApproovEnabled()).thenReturn(false);
        ApproovPinningInterceptor interceptor = new ApproovPinningInterceptor(service);

        // a cleartext (http) request has no TLS handshake to pin, so it must be forwarded rather than
        // failing closed (matching OkHttp's built-in CertificatePinner, which only acts on https)
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);

        Request request = new Request.Builder().url("http://localhost:8080/data").build();
        Response response = mock(Response.class);
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.connection()).thenReturn(null);
        when(chain.proceed(any())).thenReturn(response);

        assertSame(response, interceptor.intercept(chain));
    }

    private boolean hasPinHash(CertificatePinner pinner, String expectedHashBase64) throws Exception {
        for (Object pin : pinner.getPins()) {
            Object hash = pin.getClass().getMethod("getHash").invoke(pin);
            String actualHashBase64 = (String) hash.getClass().getMethod("base64").invoke(hash);
            if (expectedHashBase64.equals(actualHashBase64)) {
                return true;
            }
        }
        return false;
    }
}
