package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.AssetManager;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.modules.network.NetworkingModule;
import com.facebook.react.modules.network.OkHttpClientProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * initialize() resets the runtime configuration. The token header, prefix and binding header set
 * from the bundled approov.props at launch are configuration too, so the reset goes back to them
 * rather than to the built-in defaults. Runs without the mini-SDK (bypass configuration).
 */
public class ApproovLaunchPropsTest {
    private ReactApplicationContext context;
    private boolean withProps = true;

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

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

    private static void reset() throws Exception {
        field(ApproovService.class, "isInitialized").set(null, false);
        field(ApproovService.class, "initialConfig").set(null, null);
        field(NetworkingModule.class, "customClientBuilder", "mCustomClientBuilder").set(null, null);
        field(OkHttpClientProvider.class, "factory", "sFactory").set(null, null);
        field(OkHttpClientProvider.class, "client", "sClient").set(null, null);
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
    }

    @Before
    public void setUp() throws Exception {
        reset();
        context = mock(ReactApplicationContext.class);
        AssetManager assets = mock(AssetManager.class);
        when(context.getAssets()).thenReturn(assets);
        when(assets.open(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            // an empty bundled config initializes the layer at launch in bypass mode
            if (name.equals("approov.config"))
                return stream("\n");
            if (name.equals("approov.props") && withProps)
                return stream("token.name=Authorization\ntoken.prefix=Bearer\\u0020\nbinding.name=X-Session\n");
            throw new IOException("missing " + name);
        });
    }

    @After
    public void tearDown() throws Exception {
        reset();
    }

    @Test
    public void initializeResetsToTheLaunchProperties() {
        ApproovService service = new ApproovService(context);
        assertEquals("Authorization", service.getTokenHeader());

        service.setTokenHeader("Other-Header", "");
        service.setBindingHeader("Other-Binding");
        Promise promise = mock(Promise.class);
        service.initialize("", null, promise);
        verify(promise).resolve(null);

        assertEquals("Authorization", service.getTokenHeader());
        assertEquals("Bearer ", service.getTokenPrefix());
        assertEquals("X-Session", service.getBindingHeader());
    }

    @Test
    public void withoutPropertiesInitializeResetsToTheDefaults() throws Exception {
        withProps = false;
        ApproovService service = new ApproovService(context);
        service.setTokenHeader("Other-Header", "x ");

        Promise promise = mock(Promise.class);
        service.initialize("", null, promise);
        verify(promise).resolve(null);

        assertEquals("Approov-Token", service.getTokenHeader());
        assertEquals("", service.getTokenPrefix());
        assertNull(service.getBindingHeader());
    }
}
