package io.approov.reactnative;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.criticalblue.approovsdk.Approov;
import com.facebook.react.bridge.ReactApplicationContext;
import java.net.Socket;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.Handshake;
import okhttp3.Interceptor;
import okhttp3.Request;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.mockito.MockedStatic;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ApproovPinningInterceptorTest {
    private ReactApplicationContext context;
    private ApproovService service;
    private ApproovPinningInterceptor pinning;
    private MockedStatic<Approov> sdk;
    private X509Certificate certificate;
    private String matchingPin;
    private static final String WRONG_PIN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Before
    public void setUp() throws Exception {
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        context = MiniSdkHarness.reactContext();
        MiniSdkHarness.loadScenario("\"protectedDomains\": [\"example.com\"]");
        service = MiniSdkHarness.initializedService(context);
        // every SDK call from here on is answered by the mini-SDK and recorded
        sdk = mockStatic(Approov.class, CALLS_REAL_METHODS);
        pinning = new ApproovPinningInterceptor();
        certificate = mock(X509Certificate.class);
        PublicKey key = mock(PublicKey.class);
        when(key.getEncoded()).thenReturn(new byte[] {1, 2, 3});
        when(certificate.getPublicKey()).thenReturn(key);
        when(certificate.getSubjectDN()).thenReturn(new javax.security.auth.x500.X500Principal("CN=example.com"));
        matchingPin = CertificatePinner.pin(certificate).substring("sha256/".length());
    }

    @After
    public void tearDown() throws Exception {
        sdk.close();
        MiniSdkHarness.tearDown();
    }

    // publishes a new pin set from the mini-SDK, as a configuration update would, then refreshes
    private void pins(Map<String, List<String>> pins) throws Exception {
        StringBuilder json = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : pins.entrySet()) {
            if (json.length() > 0)
                json.append(',');
            json.append('"').append(entry.getKey()).append("\": [\"")
                    .append(String.join("\",\"", entry.getValue())).append("\"]");
        }
        MiniSdkHarness.loadScenario("\"protectedDomains\": [\"example.com\"], "
                + "\"pins\": {\"public-key-sha256\": {" + json + "}}");
        MiniSdkHarness.initialize(service, MiniSdkHarness.CONFIG);
        sdk.clearInvocations();
        pinning.rebuildPins(service);
    }

    private Handshake handshake() {
        Handshake handshake = mock(Handshake.class);
        when(handshake.peerCertificates()).thenReturn(Collections.<Certificate>singletonList(certificate));
        return handshake;
    }

    private Interceptor.Chain chain(String url, Handshake handshake) {
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(new Request.Builder().url(url).build());
        Connection connection = mock(Connection.class);
        when(chain.connection()).thenReturn(connection);
        when(connection.handshake()).thenReturn(handshake);
        when(connection.socket()).thenReturn(mock(Socket.class));
        return chain;
    }

    @Test
    public void constructionDoesNotQuerySdk() {
        new ApproovPinningInterceptor();
        sdk.verifyNoInteractions();
    }

    @Test
    public void checksEveryExchangeWithoutFetchingPins() throws Exception {
        pins(Collections.singletonMap("example.com", Collections.singletonList(matchingPin)));
        Handshake handshake = handshake();
        Interceptor.Chain chain = chain("https://example.com/a", handshake);
        clearInvocations(certificate);
        for (int i = 0; i < 1000; i++)
            pinning.intercept(chain);
        verify(handshake, times(1000)).peerCertificates();
        verify(certificate, times(1000)).getPublicKey();
        verify(chain, times(1000)).proceed(chain.request());
        // Only the initial pin refresh calls the SDK; checking connections is local work.
        sdk.verify(() -> Approov.getPins("public-key-sha256"), times(1));
    }

    @Test
    public void approvalDoesNotCarryAcrossHostsOnSharedConnection() throws Exception {
        Map<String, List<String>> pins = new HashMap<>();
        pins.put("example.com", Collections.singletonList(matchingPin));
        pins.put("other.example.com", Collections.singletonList(WRONG_PIN));
        pins(pins);
        Handshake handshake = handshake();
        pinning.intercept(chain("https://example.com/a", handshake));
        Interceptor.Chain otherHost = chain("https://other.example.com/a", handshake);
        assertThrows(SSLPeerUnverifiedException.class, () -> pinning.intercept(otherHost));
        verify(otherHost, never()).proceed(any());
        verify(otherHost.connection().socket()).close();
    }

    @Test
    public void rotationRechecksPreviouslyAcceptedConnection() throws Exception {
        pins(Collections.singletonMap("example.com", Collections.singletonList(matchingPin)));
        Interceptor.Chain chain = chain("https://example.com/a", handshake());
        pinning.intercept(chain);
        pins(Collections.singletonMap("example.com", Collections.singletonList(WRONG_PIN)));
        assertThrows(SSLPeerUnverifiedException.class, () -> pinning.intercept(chain));
        verify(chain, times(1)).proceed(any());
    }

    @Test
    public void pinUpdateDuringCheckAppliesToNextExchange() throws Exception {
        pins(Collections.singletonMap("example.com", Collections.singletonList(matchingPin)));
        Handshake handshake = handshake();
        when(handshake.peerCertificates()).thenAnswer(invocation -> {
            // Deterministically publish a new generation during validation of the old one.
            pins(Collections.singletonMap("example.com", Collections.singletonList(WRONG_PIN)));
            return Collections.<Certificate>singletonList(certificate);
        });
        Interceptor.Chain chain = chain("https://example.com/a", handshake);
        pinning.intercept(chain);
        assertThrows(SSLPeerUnverifiedException.class, () -> pinning.intercept(chain));
        verify(chain, times(1)).proceed(any());
    }

    @Test
    public void failedPinRebuildRetainsPreviousSnapshotAndPropagates() throws Exception {
        pins(Collections.singletonMap("example.com", Collections.singletonList(matchingPin)));
        CertificatePinner previous = pinning.getCertificatePinner();
        IllegalStateException failure = new IllegalStateException("pins unavailable");
        sdk.when(() -> Approov.getPins("public-key-sha256")).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> pinning.rebuildPins(service)));
        assertSame(previous, pinning.getCertificatePinner());
    }

    @Test
    public void missingHttpsHandshakeFailsBeforeSendingRequest() throws Exception {
        Interceptor.Chain chain = chain("https://example.com/a", null);
        assertThrows(ApproovNetworkException.class, () -> pinning.intercept(chain));
        verify(chain, never()).proceed(any());
    }

    @Test
    public void httpAndMutatorBypassProceedWithoutTls() throws Exception {
        Interceptor.Chain http = chain("http://example.com/a", null);
        pinning.intercept(http);
        verify(http).proceed(http.request());
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override public boolean handlePinningShouldProcessRequest(Request request) { return false; }
        });
        Interceptor.Chain https = chain("https://example.com/a", null);
        pinning.intercept(https);
        verify(https).proceed(https.request());
    }
}
