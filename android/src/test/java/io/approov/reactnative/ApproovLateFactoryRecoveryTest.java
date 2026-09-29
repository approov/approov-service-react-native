package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.AssetManager;

import com.criticalblue.approovsdk.Approov;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.modules.network.NetworkingModule;
import com.facebook.react.modules.network.OkHttpClientProvider;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.CookieJar;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

/**
 * updateClientFactory(true) must keep a factory another SDK installed after React Native built
 * its live client. REFERENCE.md documents this recovery for exactly that case: the other SDK
 * overwrites the OkHttpClientFactory after Approov initialized. The live client cannot contain
 * the late factory's settings, so recovery must build from that factory, as a reload would.
 */
public class ApproovLateFactoryRecoveryTest {
    private ReactApplicationContext context;
    private MockedStatic<Approov> sdk;

    private static Field field(Class<?> cls, String... names) throws NoSuchFieldException {
        for (String name : names) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignore) {
                // try the next name
            }
        }
        throw new NoSuchFieldException(cls.getName());
    }

    @Before
    public void setUp() throws Exception {
        field(ApproovService.class, "isInitialized").set(null, false);
        field(ApproovService.class, "initialConfig").set(null, null);
        field(NetworkingModule.class, "customClientBuilder", "mCustomClientBuilder").set(null, null);
        field(OkHttpClientProvider.class, "factory", "sFactory").set(null, null);
        field(OkHttpClientProvider.class, "client", "sClient").set(null, null);
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        context = mock(ReactApplicationContext.class);
        AssetManager assets = mock(AssetManager.class);
        when(context.getAssets()).thenReturn(assets);
        when(assets.open(anyString())).thenThrow(new IOException("no config"));
        sdk = mockStatic(Approov.class);
        sdk.when(() -> Approov.getPins("public-key-sha256")).thenReturn(Collections.emptyMap());
    }

    @After
    public void tearDown() throws Exception {
        sdk.close();
        field(OkHttpClientProvider.class, "factory", "sFactory").set(null, null);
        field(OkHttpClientProvider.class, "client", "sClient").set(null, null);
    }

    private NetworkingModule attach(OkHttpClient client) throws Exception {
        NetworkingModule module = mock(NetworkingModule.class);
        field(NetworkingModule.class, "client", "mClient").set(module, client);
        when(context.getNativeModule(NetworkingModule.class)).thenReturn(module);
        return module;
    }

    private static long approovInterceptors(OkHttpClient client) {
        return client.interceptors().stream().filter(i -> i instanceof ApproovInterceptor).count();
    }

    @Test
    public void wrapExistingRecoveryKeepsFactoryInstalledAfterTheLiveClient() throws Exception {
        ApproovService service = new ApproovService(context);
        OkHttpClient live = OkHttpClientProvider.getOkHttpClient();
        NetworkingModule module = attach(live);

        // another SDK replaces the factory after RN already built its client
        Interceptor marker = chain -> chain.proceed(chain.request());
        AtomicInteger calls = new AtomicInteger();
        OkHttpClientProvider.setOkHttpClientFactory(() -> {
            calls.incrementAndGet();
            return new OkHttpClient.Builder().addInterceptor(marker).build();
        });

        Promise promise = mock(Promise.class);
        service.updateClientFactory(true, promise);
        verify(promise).resolve(true);

        OkHttpClient recovered = (OkHttpClient) field(NetworkingModule.class, "client", "mClient").get(module);
        assertTrue("recovery must build from the late factory", calls.get() > 0);
        assertTrue("late factory's interceptor must be on the live client", recovered.interceptors().contains(marker));
        assertEquals("exactly one Approov interceptor on the live client", 1, approovInterceptors(recovered));
        CookieJar liveJar = live.cookieJar();
        assertSame("recovery must keep React Native's cookie bridge", liveJar, recovered.cookieJar());

        // the late factory must also survive the next context recreation
        new ApproovService(context);
        OkHttpClient reloaded = OkHttpClientProvider.createClient();
        assertTrue("late factory must survive a reload after recovery", reloaded.interceptors().contains(marker));
        assertEquals(1, approovInterceptors(reloaded));
    }

    @Test
    public void wrapExistingRecoveryWithoutForeignFactoryStillCopiesTheLiveClient() throws Exception {
        ApproovService service = new ApproovService(context);
        Interceptor liveOnly = chain -> chain.proceed(chain.request());
        OkHttpClient live = OkHttpClientProvider.getOkHttpClient().newBuilder().addInterceptor(liveOnly).build();
        NetworkingModule module = attach(live);

        Promise promise = mock(Promise.class);
        service.updateClientFactory(true, promise);
        verify(promise).resolve(true);

        OkHttpClient recovered = (OkHttpClient) field(NetworkingModule.class, "client", "mClient").get(module);
        assertTrue("settings on the live client are kept when no foreign factory is installed",
                recovered.interceptors().contains(liveOnly));
        assertEquals(1, approovInterceptors(recovered));
    }
}
