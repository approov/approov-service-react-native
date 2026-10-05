package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.AssetManager;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.modules.network.NetworkingModule;
import com.facebook.react.modules.network.OkHttpClientProvider;

import java.io.IOException;
import java.lang.reflect.Field;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The pinning interceptor must be the first network interceptor. It removes Approov headers from
 * a redirect to another origin, so a logging or inspection interceptor added by another SDK must
 * only ever see the request after that. Also covers what a client built before a React Native
 * reload holds on to. Runs without the mini-SDK.
 */
public class ApproovClientWiringTest {
    private ApproovService service;
    private ReactApplicationContext context;

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

    private static void resetReactNative() throws Exception {
        field(NetworkingModule.class, "customClientBuilder", "mCustomClientBuilder").set(null, null);
        field(OkHttpClientProvider.class, "factory", "sFactory").set(null, null);
        field(OkHttpClientProvider.class, "client", "sClient").set(null, null);
    }

    @Before
    public void setUp() throws Exception {
        field(ApproovService.class, "isInitialized").set(null, false);
        field(ApproovService.class, "initialConfig").set(null, null);
        resetReactNative();
        context = mock(ReactApplicationContext.class);
        AssetManager assets = mock(AssetManager.class);
        when(context.getAssets()).thenReturn(assets);
        when(assets.open(anyString())).thenThrow(new IOException("no config"));
        service = new ApproovService(context);
    }

    @After
    public void tearDown() throws Exception {
        resetReactNative();
    }

    @Test
    public void pinningRunsBeforeOtherNetworkInterceptors() {
        Interceptor logger = chain -> chain.proceed(chain.request());
        OkHttpClient.Builder builder = new OkHttpClient.Builder().addNetworkInterceptor(logger);

        new ApproovClientBuilder(service, null).apply(builder);

        assertEquals(2, builder.networkInterceptors().size());
        assertTrue(builder.networkInterceptors().get(0) instanceof ApproovPinningInterceptor);
        assertSame(logger, builder.networkInterceptors().get(1));
    }

    @Test
    public void reapplyingKeepsOnePinningInterceptorInFront() {
        Interceptor logger = chain -> chain.proceed(chain.request());
        OkHttpClient.Builder builder = new OkHttpClient.Builder().addNetworkInterceptor(logger);
        ApproovClientBuilder approov = new ApproovClientBuilder(service, null);

        approov.apply(builder);
        approov.apply(builder);

        assertEquals(2, builder.networkInterceptors().size());
        assertTrue(builder.networkInterceptors().get(0) instanceof ApproovPinningInterceptor);
    }

    // creates a service and an interceptor for it, keeping only a weak reference to the service
    private ApproovInterceptor interceptorForReloadedService(java.lang.ref.WeakReference<ApproovService>[] old) {
        ApproovService first = new ApproovService(context);
        old[0] = new java.lang.ref.WeakReference<>(first);
        return new ApproovInterceptor(first);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void clientBuiltBeforeAReloadUsesTheNewServiceAndDoesNotKeepTheOldOne() throws Exception {
        java.lang.ref.WeakReference<ApproovService>[] old = new java.lang.ref.WeakReference[1];
        ApproovInterceptor interceptor = interceptorForReloadedService(old);

        // a React Native reload creates a new service
        ApproovService reloaded = new ApproovService(context);
        assertTrue(old[0].get() == null || old[0].get().isSuperseded());
        assertSame("the interceptor works for the live service", reloaded, interceptor.service());

        for (int i = 0; (i < 50) && (old[0].get() != null); i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertTrue("nothing may keep the replaced service (and its ReactContext) alive", old[0].get() == null);
    }

    @Test
    public void everyServiceSharesOnePinningInterceptor() {
        assertSame(service.getPinningInterceptor(), new ApproovService(context).getPinningInterceptor());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void deprecatedPinChangeApiStillCompilesAndIsCalledOnARefresh() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ApproovService.PinChangeListener listener = calls::incrementAndGet;
        service.addPinChangeListener(listener);
        assertEquals(java.util.Collections.singletonList(listener), service.getPinChangeListeners());

        service.rebuildPins();
        assertEquals(1, calls.get());
        service.setEarliestNetworkRequestTime();
        assertEquals(0, service.getEarliestNetworkRequestTime());
        new ApproovClientBuilder(service, null).approovPinsUpdated();
    }
}
