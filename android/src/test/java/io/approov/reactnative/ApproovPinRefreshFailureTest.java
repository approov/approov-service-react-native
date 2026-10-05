package io.approov.reactnative;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

/**
 * A pin refresh that fails inside the interceptor must fail only that request. OkHttp rethrows an
 * unchecked exception from an interceptor on its dispatcher thread, which ends the app, so the
 * failure has to reach the app as an IOException. Runs without the mini-SDK.
 */
public class ApproovPinRefreshFailureTest {
    private MockedStatic<Approov> sdk;
    private ApproovService service;
    private Thread.UncaughtExceptionHandler previousHandler;
    private final AtomicReference<Throwable> uncaught = new AtomicReference<>();

    @Before
    public void setUp() {
        ApproovService.setServiceMutator(ApproovServiceMutator.DEFAULT);
        sdk = mockStatic(Approov.class);
        service = mock(ApproovService.class);
        when(service.isInitialized()).thenReturn(true);
        when(service.isApproovEnabled()).thenReturn(true);
        when(service.getTokenHeader()).thenReturn("Approov-Token");
        when(service.getTokenPrefix()).thenReturn("");

        Approov.TokenFetchResult result = mock(Approov.TokenFetchResult.class);
        when(result.getStatus()).thenReturn(Approov.TokenFetchStatus.SUCCESS);
        when(result.getToken()).thenReturn("token");
        when(result.isForceApplyPins()).thenReturn(true);
        when(service.fetchApproovTokenAndWait(anyString())).thenReturn(result);
        doThrow(new IllegalStateException("pins unavailable")).when(service).rebuildPins();

        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> uncaught.set(error));
    }

    @After
    public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        if (sdk != null)
            sdk.close();
    }

    @Test
    public void pinRefreshFailureFailsTheCallWithoutAnUncaughtException() throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new ApproovInterceptor(service))
                .build();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<IOException> failure = new AtomicReference<>();
        client.newCall(new Request.Builder().url("https://api.example.com/").build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                failure.set(e);
                done.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) {
                response.close();
                done.countDown();
            }
        });

        assertTrue("call did not complete", done.await(5, TimeUnit.SECONDS));
        client.dispatcher().executorService().shutdown();
        assertTrue(client.dispatcher().executorService().awaitTermination(5, TimeUnit.SECONDS));

        assertTrue("expected an Approov failure, got " + failure.get(), failure.get() instanceof ApproovException);
        assertTrue(failure.get().getCause() instanceof IllegalStateException);
        assertNull("nothing may escape to the dispatcher thread", uncaught.get());
    }

    @Test
    public void pinRefreshFailureIsReportedAsAnIOExceptionFromIntercept() throws Exception {
        IllegalStateException cause = new IllegalStateException("pins unavailable");
        doThrow(cause).when(service).rebuildPins();
        okhttp3.Interceptor.Chain chain = mock(okhttp3.Interceptor.Chain.class);
        when(chain.request()).thenReturn(new Request.Builder().url("https://api.example.com/").build());

        try {
            new ApproovInterceptor(service).intercept(chain);
        } catch (ApproovException e) {
            assertSame(cause, e.getCause());
            return;
        }
        throw new AssertionError("expected ApproovException");
    }
}
