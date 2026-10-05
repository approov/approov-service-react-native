package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.net.Proxy;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.X509ExtendedKeyManager;

import okhttp3.CertificatePinner;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * ApproovPinningInterceptor checks pins against the chain the trust manager verified, not the raw
 * list of certificates the server sent. Otherwise a server with any trusted certificate could
 * append a certificate whose key is pinned and pass the check (CVE-2016-2402 in old OkHttp). It
 * also means a pinned root CA (managed trust roots) matches even though servers do not send the
 * root. Runs without the mini-SDK.
 */
public class ApproovPinningChainTest {
    private static final String HOST = "api.test";

    private HeldCertificate root;
    private HeldCertificate unrelated;
    private MockWebServer server;
    private ApproovPinningInterceptor pinning;
    private OkHttpClient client;

    @Before
    public void setUp() throws Exception {
        root = new HeldCertificate.Builder().certificateAuthority(0).commonName("Trusted Root").build();
        HeldCertificate leaf = new HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName(HOST).build();
        // a self-signed certificate the client does not trust, sent after the valid chain
        unrelated = new HeldCertificate.Builder().commonName("Appended").build();

        // the server sends exactly [leaf, appended]; a key store would refuse that chain
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(new KeyManager[] { new FixedChainKeyManager(leaf, unrelated.certificate()) }, null, null);
        HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
                .addTrustedCertificate(root.certificate())
                .build();
        server = new MockWebServer();
        server.useHttps(serverTls.getSocketFactory(), false);
        server.start();

        pinning = new ApproovPinningInterceptor();
        Dns local = hostname -> Collections.singletonList(InetAddress.getByName("127.0.0.1"));
        client = new OkHttpClient.Builder()
                .dns(local)
                .proxy(Proxy.NO_PROXY)
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager())
                .callTimeout(5, TimeUnit.SECONDS)
                .addNetworkInterceptor(pinning)
                .build();
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdownNow();
        }
        if (server != null)
            server.shutdown();
    }

    private Response call() throws Exception {
        return client.newCall(new Request.Builder().url("https://" + HOST + ":" + server.getPort() + "/").build()).execute();
    }

    @Test
    public void appendedCertificateDoesNotSatisfyAPin() {
        pinning.installPins(new CertificatePinner.Builder().add(HOST, CertificatePinner.pin(unrelated.certificate())).build());
        server.enqueue(new MockResponse().setBody("ok"));

        assertThrows(SSLPeerUnverifiedException.class, this::call);
    }

    @Test
    public void pinnedRootThatTheServerDoesNotSendIsMatched() throws Exception {
        pinning.installPins(new CertificatePinner.Builder().add(HOST, CertificatePinner.pin(root.certificate())).build());
        server.enqueue(new MockResponse().setBody("ok"));

        try (Response response = call()) {
            assertEquals(200, response.code());
        }
    }

    private void checkMismatchOn(okhttp3.Protocol protocol, Socket socket) throws Exception {
        pinning.installPins(new CertificatePinner.Builder().add(HOST, CertificatePinner.pin(unrelated.certificate())).build());
        okhttp3.Connection connection = mock(okhttp3.Connection.class);
        when(connection.protocol()).thenReturn(protocol);
        when(connection.socket()).thenReturn(socket);
        when(connection.handshake()).thenReturn(okhttp3.Handshake.get(okhttp3.TlsVersion.TLS_1_3,
                okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
                Collections.singletonList(root.certificate()), Collections.emptyList()));
        okhttp3.Interceptor.Chain chain = mock(okhttp3.Interceptor.Chain.class);
        when(chain.request()).thenReturn(new Request.Builder().url("https://" + HOST + "/").build());
        when(chain.connection()).thenReturn(connection);

        assertThrows(SSLPeerUnverifiedException.class, () -> pinning.intercept(chain));
        verify(chain, never()).proceed(any());
    }

    @Test
    public void mismatchLeavesASharedHttp2ConnectionOpen() throws Exception {
        Socket socket = mock(Socket.class);
        checkMismatchOn(okhttp3.Protocol.HTTP_2, socket);
        verify(socket, never()).close();
    }

    @Test
    public void mismatchClosesAnHttp11Connection() throws Exception {
        Socket socket = mock(Socket.class);
        checkMismatchOn(okhttp3.Protocol.HTTP_1_1, socket);
        verify(socket).close();
    }

    // Presents one private key with a fixed certificate list, whether or not it forms a chain.
    private static final class FixedChainKeyManager extends X509ExtendedKeyManager {
        private final PrivateKey key;
        private final X509Certificate[] chain;

        FixedChainKeyManager(HeldCertificate leaf, X509Certificate... extra) {
            key = leaf.keyPair().getPrivate();
            chain = new X509Certificate[extra.length + 1];
            chain[0] = leaf.certificate();
            System.arraycopy(extra, 0, chain, 1, extra.length);
        }

        @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return null; }
        @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return null; }
        @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return new String[] { "server" }; }
        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return "server"; }
        @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) { return "server"; }
        @Override public X509Certificate[] getCertificateChain(String alias) { return chain.clone(); }
        @Override public PrivateKey getPrivateKey(String alias) { return key; }
    }
}
