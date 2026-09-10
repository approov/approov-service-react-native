package io.approov.testfixtures;

import com.facebook.react.modules.network.CustomClientBuilder;
import com.facebook.react.modules.network.NetworkingModule;

import okhttp3.CertificatePinner;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

/** Callbacks in a different package to exercise access to non-public implementations. */
public final class CustomClientBuilderFixtures {
    private CustomClientBuilderFixtures() {}

    public static CustomClientBuilder anonymous(Interceptor marker) {
        return new CustomClientBuilder() {
            @Override
            public void apply(OkHttpClient.Builder builder) {
                builder.addInterceptor(marker);
            }
        };
    }

    public static CustomClientBuilder lambda(Interceptor marker) {
        return builder -> builder.addInterceptor(marker);
    }

    public static NetworkingModule.CustomClientBuilder nested(Interceptor marker) {
        return new NetworkingModule.CustomClientBuilder() {
            @Override
            public void apply(OkHttpClient.Builder builder) {
                builder.addInterceptor(marker);
            }
        };
    }

    public static CustomClientBuilder removingProtection() {
        return builder -> {
            builder.interceptors().clear();
            builder.certificatePinner(CertificatePinner.DEFAULT);
        };
    }

    public static final class Failing implements CustomClientBuilder {
        private final Throwable failure;

        public Failing(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void apply(OkHttpClient.Builder builder) {
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            throw (Error) failure;
        }
    }

    public static final class Counting implements CustomClientBuilder {
        public int calls;

        @Override
        public void apply(OkHttpClient.Builder builder) {
            calls++;
        }
    }
}
