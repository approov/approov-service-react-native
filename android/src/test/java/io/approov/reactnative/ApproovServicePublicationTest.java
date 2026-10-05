package io.approov.reactnative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.AssetManager;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.modules.network.NetworkingModule;
import com.facebook.react.modules.network.OkHttpClientProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * An interceptor from a client built before a React Native reload switches to the newest service.
 * The new service must not be visible to it until its construction is complete: a half-built
 * service has no header maps yet, and a request reading them would throw inside OkHttp and end the
 * app. Runs without the mini-SDK (bypass configuration).
 */
public class ApproovServicePublicationTest {
    // what ApproovService.latest() returned while the second service was being constructed
    private final List<ApproovService> seenDuringConstruction = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private boolean probing;

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
        field(ApproovService.class, "latestService").set(null, null);
        field(NetworkingModule.class, "customClientBuilder", "mCustomClientBuilder").set(null, null);
        field(OkHttpClientProvider.class, "factory", "sFactory").set(null, null);
        field(OkHttpClientProvider.class, "client", "sClient").set(null, null);
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
    }

    // Acts as a request on a pre-reload client would: reads the latest service's configuration.
    private void probe() {
        if (!probing)
            return;
        ApproovService latest = ApproovService.latest();
        seenDuringConstruction.add(latest);
        try {
            latest.getSubstitutionHeaders();
            latest.getSubstitutionQueryParams();
            latest.getExclusionURLRegexs();
            latest.getTokenHeader();
        } catch (Throwable t) {
            failures.add(t);
        }
    }

    private ReactApplicationContext context() throws IOException {
        ReactApplicationContext context = mock(ReactApplicationContext.class);
        // read at the start of construction (debuggable flag) and while loading the bundled files
        when(context.getApplicationInfo()).thenAnswer(invocation -> {
            probe();
            return null;
        });
        AssetManager assets = mock(AssetManager.class);
        when(context.getAssets()).thenReturn(assets);
        when(assets.open(anyString())).thenAnswer(invocation -> {
            probe();
            String name = invocation.getArgument(0);
            // an empty bundled config initializes the layer at launch in bypass mode
            if (name.equals("approov.config"))
                return new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8));
            throw new IOException("missing " + name);
        });
        return context;
    }

    @Before
    public void setUp() throws Exception {
        reset();
    }

    @After
    public void tearDown() throws Exception {
        reset();
    }

    @Test
    public void replacementServiceIsPublishedOnlyOnceConstructed() throws Exception {
        ApproovService first = new ApproovService(context());
        assertSame(first, ApproovService.latest());

        probing = true;
        ApproovService second = new ApproovService(context());
        probing = false;

        assertTrue("construction should have been probed", !seenDuringConstruction.isEmpty());
        for (ApproovService seen : seenDuringConstruction)
            assertSame("the service under construction must not be published yet", first, seen);
        assertTrue("reading the latest service during a reload must not fail: " + failures, failures.isEmpty());
        assertFalse("the old service stays current until the new one is ready", seenDuringConstruction.contains(second));

        assertSame(second, ApproovService.latest());
        assertTrue(first.isSuperseded());
        assertFalse(second.isSuperseded());
    }

    @Test
    public void firstServiceIsPublishedWhenConstructed() throws Exception {
        assertNull(ApproovService.latest());
        ApproovService service = new ApproovService(context());
        assertSame(service, ApproovService.latest());
    }
}
