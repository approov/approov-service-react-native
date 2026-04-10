package io.approov.reactnative;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.res.AssetManager;

import com.criticalblue.approovsdk.Approov;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.modules.network.NetworkingModule;
import com.facebook.react.modules.network.OkHttpClientProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.CertificatePinner;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

public class ApproovServicePublicApiTest {

    private ReactApplicationContext reactContext;
    private MockedStatic<NetworkingModule> networkingModuleStatic;

    @Before
    public void setUp() throws Exception {
        reactContext = mock(ReactApplicationContext.class);
        AssetManager assetManager = mock(AssetManager.class);
        when(reactContext.getAssets()).thenReturn(assetManager);
        when(assetManager.open(anyString())).thenThrow(new IOException("missing"));

        networkingModuleStatic = mockStatic(NetworkingModule.class);
    }

    @After
    public void tearDown() {
        networkingModuleStatic.close();
    }

    private ApproovService newService() {
        return new ApproovService(reactContext);
    }

    @Test
    public void getLastArcReturnsArcOnlyAfterSuccessfulFetch() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class)) {
            ApproovService service = newService();
            Promise promise = mock(Promise.class);

            approov.when(() -> Approov.getPins("public-key-sha256"))
                .thenReturn(Collections.singletonMap("example.com", Collections.singletonList("pin")));

            Approov.TokenFetchResult success = mock(Approov.TokenFetchResult.class);
            when(success.getToken()).thenReturn("jwt-token");
            when(success.getARC()).thenReturn("ARC123");
            approov.when(() -> Approov.fetchApproovToken(any(Approov.TokenFetchCallback.class), anyString()))
                .thenAnswer(invocation -> {
                    Approov.TokenFetchCallback callback = invocation.getArgument(0);
                    callback.approovCallback(success);
                    return null;
                });

            service.getLastARC(promise);

            org.mockito.Mockito.verify(promise).resolve("ARC123");
        }
    }

    @Test
    public void getPinningDiagnosticsResolvesExpectedFlagsAndInterceptorNames() {
        try (MockedStatic<Approov> approov = mockStatic(Approov.class);
             MockedStatic<OkHttpClientProvider> okHttpClientProvider = mockStatic(OkHttpClientProvider.class)) {
            ApproovService service = newService();
            Promise promise = mock(Promise.class);
            AtomicReference<Object> resolvedValue = new AtomicReference<>();

            approov.when(() -> Approov.getPins(anyString())).thenReturn(Collections.emptyMap());
            doAnswer(invocation -> {
                resolvedValue.set(invocation.getArgument(0));
                return null;
            }).when(promise).resolve(any());

            Interceptor extraInterceptor = chain -> chain.proceed(chain.request());
            OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new ApproovInterceptor(service))
                .addInterceptor(extraInterceptor)
                .certificatePinner(new CertificatePinner.Builder()
                    .add("example.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    .build())
                .build();
            okHttpClientProvider.when(OkHttpClientProvider::getOkHttpClient).thenReturn(client);

            service.getPinningDiagnostics(promise);

            assertTrue(resolvedValue.get() instanceof ReadableMap);
            ReadableMap diagnostics = (ReadableMap) resolvedValue.get();
            assertTrue(diagnostics.hasKey("isInterceptorPresent"));
            assertTrue(diagnostics.getBoolean("isInterceptorPresent"));
            assertTrue(diagnostics.hasKey("isPinnerPresent"));
            assertTrue(diagnostics.getBoolean("isPinnerPresent"));

            ReadableArray interceptors = diagnostics.getArray("interceptors");
            assertNotNull(interceptors);
            boolean foundApproovInterceptor = false;
            for (int index = 0; index < interceptors.size(); index++) {
                if ("io.approov.reactnative.ApproovInterceptor".equals(interceptors.getString(index))) {
                    foundApproovInterceptor = true;
                    break;
                }
            }
            assertTrue(foundApproovInterceptor);
        }
    }
}
