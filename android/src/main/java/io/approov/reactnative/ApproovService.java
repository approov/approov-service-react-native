/*
 * MIT License
 *
 * Copyright (c) 2016-present, CriticalBlue Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
 * OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package io.approov.reactnative;

import android.content.Context;
import android.util.Log;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.JavaOnlyArray;
import com.facebook.react.bridge.JavaOnlyMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.modules.network.NetworkingModule;
import com.facebook.react.modules.network.OkHttpClientProvider;
import com.facebook.react.modules.network.OkHttpClientFactory;

import okhttp3.OkHttpClient;
import okhttp3.Interceptor;
import okhttp3.CertificatePinner;
import okhttp3.Request;
import okhttp3.Response;

import com.criticalblue.approovsdk.Approov;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Properties;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

// ApproovService wraps the underlying Approov SDK, provides network interceptors bridges to allow calls from Javascript
public class ApproovService extends ReactContextBaseJavaModule {
    // logging tag
    private static final String TAG = "ApproovService";

    /**
     * Interface to be implemented by classes that wish to be notified of any pin
     * changes.
     */
    public interface PinChangeListener {
        void approovPinsUpdated();
    }

    // module name that defines how it is called from Javascript
    private static final String MODULE_NAME = "ApproovService";

    // optional configuration and properties files
    private static final String CONFIG_NAME = "approov.config";
    private static final String PROPS_NAME = "approov.props";

    // header that will be added to Approov enabled requests
    private static final String APPROOV_TOKEN_HEADER = "Approov-Token";

    // default header that will carry any optional Approov TraceID debug value from
    // the SDK
    private static final String APPROOV_TRACE_ID_HEADER = "Approov-TraceID";

    // any prefix to be added before the Approov token, such as "Bearer "
    private static final String APPROOV_TOKEN_PREFIX = "";

    // time window (in millseconds) applied to any network request attempts made
    // before Approov
    // is initialized. The start of the window is defined by the first network
    // request received
    // prior to initialization. That network request, and any others arriving during
    // the window, may
    // then be delayed until the end of the window period. This is to allow time for
    // the Approov
    // initialization to be completed as it may be in a race with API requests made
    // as the app
    // starts up.
    private static final long STARTUP_SYNC_TIME_WINDOW = 2500;

    // flag indicating whether the Approov SDK has been initialized - if not then no
    // Approov functionality is enabled
    private static boolean isInitialized = false;

    // any initial configuration used in order to detect a difference
    private static String initialConfig = null;

    // the application context used for certain framework calls
    private Context applicationContext;

    // the earliest time that any network request will be allowed to avoid any
    // potential race conditions with Approov
    // protected API calls being made before Approov itself can be initialized - or
    // 0 they may proceed immediately
    private long earliestNetworkRequestTime;

    // flag indicating if there is a pending prefetch to be executed upon
    // initialization
    private boolean pendingPrefetch;

    // application config options
    private boolean useApproovStatusIfNoToken;

    /**
     * Sets the flag to indicate if the interceptor should proceed on network
     * failures and not add an Approov token and instead add the Approov status.
     * 
     * @param useApproovStatusIfNoToken is the flag value
     */
    public synchronized void setUseApproovStatusIfNoTokenInternal(boolean useApproovStatusIfNoToken) {
        this.useApproovStatusIfNoToken = useApproovStatusIfNoToken;
    }

    /**
     * Gets the flag that indicates if the interceptor should proceed on network
     * failures and not add an Approov token and instead add the Approov status.
     * 
     * @return true if the interceptor should proceed on network failures and not
     *         add an Approov token and instead add the Approov status
     */
    public synchronized boolean getUseApproovStatusIfNoToken() {
        return useApproovStatusIfNoToken;
    }

    // true if the logging should be suppressed for unknown (and excluded) URLs
    private boolean suppressLoggingUnknownURL;

    // true if verbose session metadata collection should be enabled. Android
    // does not currently emit the iOS-style session ledger, but this flag keeps
    // the JS API cross-platform safe.
    private boolean sessionMetadataCollectionEnabled;

    // header to be used to send Approov tokens
    private String approovTokenHeader;

    // header used to send any optional Approov TraceID debug value provided by the
    // SDK
    private String approovTraceIDHeader;

    // any prefix String to be added before the transmitted Approov token
    private String approovTokenPrefix;

    // any header to be used for binding in Approov tokens or null if not set
    private String bindingHeader;

    // map of headers that should have their values substituted for secure strings,
    // mapped to their
    // required prefixes
    private Map<String, String> substitutionHeaders;

    // set of query parameters that may be substituted, specified by the key name,
    // mapped to their regex patterns
    private Map<String, Pattern> substitutionQueryParams;

    // set of URL regexs that should be excluded from any Approov protection, mapped
    // to the compiled Pattern
    private Map<String, Pattern> exclusionURLRegexs;

    // list of listeners for pin changes
    private List<PinChangeListener> pinChangeListeners;

    // Log levels matching iOS/ApproovUtils
    private static final int LOG_EXTREME = 0;
    private static final int LOG_DEBUG = 1;
    private static final int LOG_INFO = 2;
    private static final int LOG_WARN = 3;
    private static final int LOG_ERROR = 4;
    private static final int LOG_NONE = 5;

    // Current log level (default to INFO)
    private static int currentLogLevel = LOG_INFO;

    // The mutator instance used to control ApproovService behavior. Marked volatile because
    // it is written from setServiceMutator/setServiceMutatorType and from the re-initialization
    // reset (the latter under synchronized (this)), but read by the interceptor on OkHttp
    // network threads through the static, unsynchronized getServiceMutator(). The instance
    // monitor held by the reset does not order those reads, so without volatile a request
    // racing a config-change re-initialization could keep using a stale custom mutator.
    private static volatile ApproovServiceMutator serviceMutator;

    static {
        ApproovDefaultMessageSigning signer = new ApproovDefaultMessageSigning();
        signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
        serviceMutator = signer;
    }

    /**
     * Sentinel mask value selecting the built-in signing default rather than a
     * {@link PolicyMutator}. Passed from JavaScript as {@code MutatorPreset.DEFAULT}
     * to {@link #setServiceMutatorType(double, boolean, Promise)} to restore the
     * out-of-box {@link ApproovDefaultMessageSigning} mutator (with message signing).
     */
    public static final int MUTATOR_PRESET_DEFAULT = -1;

    /**
     * Sets the ApproovServiceMutator instance to handle configurations.
     *
     * @param mutator is the ApproovServiceMutator to use
     */
    public static void setServiceMutator(ApproovServiceMutator mutator) {
        if (mutator == null) {
            mutator = ApproovServiceMutator.DEFAULT;
        }
        serviceMutator = mutator;
        if (currentLogLevel <= LOG_DEBUG) {
            Log.d(TAG, "Applied ApproovServiceMutator: " + mutator.toString());
        }
    }

    /**
     * Gets the active service mutator instance.
     *
     * @return the service mutator instance (never null)
     */
    public static ApproovServiceMutator getServiceMutator() {
        return serviceMutator;
    }

    /**
     * Selects the active service mutator from JavaScript.
     *
     * <p>A {@code mask} equal to {@link #MUTATOR_PRESET_DEFAULT} restores the
     * built-in {@link ApproovDefaultMessageSigning} mutator (with message
     * signing), exactly as the static initializer configures it; the {@code sign}
     * flag is not applicable in that case. Any other value installs a
     * {@link PolicyMutator} driven by that proceed bitmask (see the
     * {@code PolicyMutator.BIT_*} constants).
     *
     * <p>This call uses replace semantics: the newly built mutator wholly replaces
     * any previously-installed mutator; it does not wrap or compose with it.
     *
     * @param maskDouble the proceed bitmask (bridged as a double), or
     *                   {@link #MUTATOR_PRESET_DEFAULT} to restore the default
     * @param sign       {@code true} to HTTP Message Sign the processed request
     *                   (the default), {@code false} to proceed per the mask but
     *                   forward the request unsigned; ignored for
     *                   {@link #MUTATOR_PRESET_DEFAULT}
     * @param promise    resolved with null on success, rejected on error
     */
    @ReactMethod
    public void setServiceMutatorType(double maskDouble, boolean sign, Promise promise) {
        try {
            // The mask is bridged from JavaScript as a double. Reject any value that is
            // not a finite, integral, 32-bit quantity before narrowing to int: a
            // fractional value (e.g. 1.5), NaN/Infinity, or an out-of-range magnitude
            // would otherwise be silently truncated or coerced by the (int) cast and
            // could install a policy other than the one the caller intended. This is
            // security-relevant: the mask decides which failure statuses may proceed.
            if (Double.isNaN(maskDouble) || Double.isInfinite(maskDouble)
                    || maskDouble != Math.floor(maskDouble)
                    || maskDouble < Integer.MIN_VALUE || maskDouble > Integer.MAX_VALUE) {
                promise.reject("setServiceMutatorType",
                        "invalid mutator mask: expected a finite 32-bit integer bitmask, got " + maskDouble);
                return;
            }
            int mask = (int) maskDouble;
            // Reject undefined bits. Only PolicyMutator.ALL_BITS name a token-fetch status;
            // a mask carrying any other bit (e.g. 1 << 11) grants PROCEED to nothing and
            // would install a policy that silently BLOCKs every failure status. Fail loudly
            // instead, so a caller's typo cannot masquerade as a deliberate block-all policy.
            if (mask != MUTATOR_PRESET_DEFAULT && (mask & ~PolicyMutator.ALL_BITS) != 0) {
                promise.reject("setServiceMutatorType",
                        "invalid mutator mask: undefined bits set (allowed bits 0-10, mask 0x"
                                + Integer.toHexString(PolicyMutator.ALL_BITS) + "), got 0x"
                                + Integer.toHexString(mask));
                return;
            }
            if (mask == MUTATOR_PRESET_DEFAULT) {   // restore out-of-box signing default (sign flag N/A)
                ApproovDefaultMessageSigning signer = new ApproovDefaultMessageSigning();
                signer.setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
                setServiceMutator(signer);
            } else {
                setServiceMutator(new PolicyMutator(mask, sign));
            }
            if (currentLogLevel <= LOG_DEBUG)
                Log.d(TAG, "setServiceMutatorType mask=" + Integer.toBinaryString(mask) + " sign=" + sign);
            promise.resolve(null);
        } catch (Exception e) {
            promise.reject("setServiceMutatorType", e.getMessage(), e);
        }
    }

    /**
     * Fetches an Approov token for the given URL.
     *
     * @param url is the URL giving the domain for the token fetch
     * @return the token fetch result
     */
    public Approov.TokenFetchResult fetchApproovTokenAndWait(String url) {
        return Approov.fetchApproovTokenAndWait(url);
    }

    /**
     * Sets the log level for Approov logging.
     *
     * @param level is the log level to set
     */
    @ReactMethod
    public void setLogLevel(int level) {
        currentLogLevel = level;
        log(LOG_INFO, TAG, "setLogLevel " + level);
    }

    private void log(int level, String tag, String msg) {
        log(level, tag, msg, null);
    }

    /**
     * Logs a message at INFO through the shared, level-gated logger. Exposed
     * package-privately so collaborators such as {@link ApproovInterceptor} honour the
     * configured log level (set via setLogLevel) instead of writing to android.util.Log
     * unconditionally. This matches the iOS ApproovLogI behaviour, where the equivalent
     * "task mutation" log is suppressed below INFO.
     *
     * @param tag the logging tag
     * @param msg the message to log
     */
    void logInfo(String tag, String msg) {
        log(LOG_INFO, tag, msg);
    }

    private void log(int level, String tag, String msg, Throwable tr) {
        if (level < currentLogLevel)
            return;

        if (tr == null) {
            switch (level) {
                case LOG_EXTREME:
                    Log.v(tag, msg);
                    break;
                case LOG_DEBUG:
                    Log.d(tag, msg);
                    break;
                case LOG_INFO:
                    Log.i(tag, msg);
                    break;
                case LOG_WARN:
                    Log.w(tag, msg);
                    break;
                case LOG_ERROR:
                    Log.e(tag, msg);
                    break;
                default:
                    Log.i(tag, msg);
                    break;
            }
        } else {
            switch (level) {
                case LOG_EXTREME:
                    Log.v(tag, msg, tr);
                    break;
                case LOG_DEBUG:
                    Log.d(tag, msg, tr);
                    break;
                case LOG_INFO:
                    Log.i(tag, msg, tr);
                    break;
                case LOG_WARN:
                    Log.w(tag, msg, tr);
                    break;
                case LOG_ERROR:
                    Log.e(tag, msg, tr);
                    break;
                default:
                    Log.i(tag, msg, tr);
                    break;
            }
        }
    }

    @Override
    public String getName() {
        return MODULE_NAME;
    }

    /**
     * Loads the optional Approov configuration from a file.
     * 
     * @return String of the configuration or null if not present
     */
    private String loadApproovConfig() {
        String config;
        try {
            InputStream stream = applicationContext.getAssets().open(CONFIG_NAME);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            config = reader.readLine();
            reader.close();
            log(LOG_INFO, TAG, "Approov configuration read from assets file " + CONFIG_NAME);
        } catch (IOException e) {
            config = null;
        }
        return config;
    }

    /**
     * Loads the optional Approov properties from a file.
     * 
     * @return Properties if present or null otherwise
     */
    private Properties loadApproovProps() {
        Properties props = new Properties();
        try {
            props.load(applicationContext.getAssets().open(PROPS_NAME));
            log(LOG_INFO, TAG, "Approov properties read from assets file " + PROPS_NAME);
        } catch (Exception e) {
            props = null;
        }
        return props;
    }

    /**
     * Configures any special version of the "rn-fetch-blob" package to add Approov
     * protection. This requires a special fork of the
     * package that adds a method that can set a custom client builder.
     * 
     * @param clientBuilder is the Approov enabled client builder that should be
     *                      used
     */
    private void configureRNFetchBlobIfFound(ApproovClientBuilder clientBuilder) {
        try {
            // find the class we want to use
            Class<?> clazz = Class.forName("com.RNFetchBlob.RNFetchBlob");

            // call the "getInstance" method on the class by reflection
            Class<?>[] ctypes = { ReactApplicationContext.class };
            Object[] cparams = new Object[] { applicationContext };
            Method cmethod = clazz.getMethod("getInstance", ctypes);
            Object instance = cmethod.invoke(null, cparams);

            // call the special "addCustomClientBuilder" method on the instance with the
            // Approov
            // client builder so that Approov protection is added to RNFetchBlob
            Class<?>[] itypes = { NetworkingModule.CustomClientBuilder.class };
            Object[] iparams = new Object[] { clientBuilder };
            Method imethod = clazz.getMethod("addCustomClientBuilder", itypes);
            imethod.invoke(instance, iparams);
            log(LOG_INFO, TAG, "rn-fetch-blob package found and Approov protection added");
        } catch (ClassNotFoundException e) {
            log(LOG_DEBUG, TAG, "rn-fetch-blob package not installed");
        } catch (Exception e) {
            log(LOG_WARN, TAG,
                    "The installed version of the rn-fetch-blob package is not compatible with Approov, so any fetch-blob requests will not be protected by Approov. See the Approov react native quickstart for more information.");
        }
    }

    /**
     * Creates a new ApproovService that wraps the underlying Approov SDK and
     * provides a bridge
     * to Javascript methods.
     * 
     * @param reactContext is the application context to be used for certain
     *                     framework calls
     */
    public ApproovService(ReactApplicationContext reactContext) {
        // initialize the service state
        super(reactContext);
        applicationContext = reactContext;
        earliestNetworkRequestTime = 0;
        pendingPrefetch = false;
        suppressLoggingUnknownURL = false;
        sessionMetadataCollectionEnabled = true;
        approovTokenHeader = APPROOV_TOKEN_HEADER;
        approovTraceIDHeader = APPROOV_TRACE_ID_HEADER;
        approovTokenPrefix = APPROOV_TOKEN_PREFIX;
        bindingHeader = null;
        substitutionHeaders = new HashMap<>();
        substitutionQueryParams = new HashMap<>();
        exclusionURLRegexs = new HashMap<>();
        pinChangeListeners = new ArrayList<>();

        // load any configuration and use it to initialize the SDK
        String config = loadApproovConfig();
        if (config != null) {
            // initialize the Approov SDK
            try {
                if (!config.isEmpty()) {
                    Approov.initialize(applicationContext, config, "auto", null);
                    Approov.setUserProperty("approov-react-native");
                }
                initialConfig = config;
                isInitialized = true;
                if (isApproovEnabled()) {
                    log(LOG_INFO, TAG, "initialized on launch on deviceID " + Approov.getDeviceID());
                } else {
                    log(LOG_INFO, TAG, "initialized on launch without Approov SDK");
                }
            } catch (IllegalArgumentException e) {
                log(LOG_ERROR, TAG, "initialization failed with IllegalArgument: " + e.getMessage());
            } catch (IllegalStateException e) {
                log(LOG_ERROR, TAG, "initialization failed with IllegalState: " + e.getMessage());
            }

            // load any properties and apply them
            Properties props = loadApproovProps();
            if (props != null) {
                // perform a background prefetch if that is requested in the properties
                String prefetch = props.getProperty("init.prefetch");
                if ((prefetch != null) && (prefetch.length() != 0) && Boolean.parseBoolean(prefetch))
                    prefetch();

                // set any alternative token header name and prefix
                String header = props.getProperty("token.name");
                if ((header != null) && (header.length() != 0)) {
                    String prefix = props.getProperty("token.prefix");
                    if (prefix == null)
                        prefix = "";
                    setTokenHeader(header, prefix);
                }

                // set any token binding header
                String bindingHeader = props.getProperty("binding.name");
                if ((bindingHeader != null) && (bindingHeader.length() != 0))
                    setBindingHeader(bindingHeader);
            }
        } else
            log(LOG_INFO, TAG, "started");

        // Register Approov protection in the React Native networking stack.
        // We use a two-tier strategy to support both modern (RN 0.73+) and legacy
        // (RN < 0.73) versions.
        ApproovClientBuilder clientBuilder = new ApproovClientBuilder(this, null);

        // --- PRIMARY: OkHttpClientFactory (works on all RN versions, required on
        // 0.73+) ---
        // Capture any existing factory set by another SDK so we can chain through it.
        OkHttpClientFactory existingFactory = null;
        try {
            Field factoryField = OkHttpClientProvider.class.getDeclaredField("sFactory");
            factoryField.setAccessible(true);
            existingFactory = (OkHttpClientFactory) factoryField.get(null);
            if (existingFactory != null)
                log(LOG_DEBUG, TAG, "found existing OkHttpClientFactory: " + existingFactory.getClass().getName());
        } catch (Exception e) {
            log(LOG_DEBUG, TAG, "could not check for existing OkHttpClientFactory: " + e.getMessage());
        }
        final OkHttpClientFactory wrappedFactory = existingFactory;
        OkHttpClientProvider.setOkHttpClientFactory(new OkHttpClientFactory() {
            @Override
            public OkHttpClient createNewNetworkModuleClient() {
                OkHttpClient.Builder builder;
                if (wrappedFactory != null) {
                    // Chain: let the existing factory build its client, then layer Approov on top
                    builder = wrappedFactory.createNewNetworkModuleClient().newBuilder();
                } else {
                    // No prior factory — start from the RN default builder
                    builder = OkHttpClientProvider.createClientBuilder();
                }
                clientBuilder.apply(builder);
                return builder.build();
            }
        });
        log(LOG_INFO, TAG, "registered Approov via OkHttpClientFactory");

        // --- LEGACY FALLBACK: setCustomClientBuilder (RN < 0.73 only) ---
        // On RN 0.73+ this method was removed so we call it via reflection and
        // silently skip if it does not exist. On older RN where both paths fire,
        // ApproovClientBuilder.apply() has a deduplication guard that prevents
        // the interceptor from being added twice.
        try {
            Method setBuilder = NetworkingModule.class.getMethod(
                    "setCustomClientBuilder", NetworkingModule.CustomClientBuilder.class);
            setBuilder.invoke(null, clientBuilder);
            log(LOG_DEBUG, TAG, "registered Approov via legacy setCustomClientBuilder");
        } catch (NoSuchMethodException e) {
            log(LOG_DEBUG, TAG, "setCustomClientBuilder not available (expected on RN 0.73+)");
        } catch (Exception e) {
            log(LOG_DEBUG, TAG, "setCustomClientBuilder failed: " + e.getMessage());
        }

        // add the Approov client builder to any rn-fetch-blob instances
        configureRNFetchBlobIfFound(clientBuilder);
    }

    /**
     * Checks if the Approov interceptor is currently active in the React Native
     * networking stack by inspecting the actual OkHttpClient interceptor chain.
     *
     * @param promise to be fulfilled with the boolean result
     */
    @ReactMethod
    public void isInterceptorActive(Promise promise) {
        try {
            OkHttpClient client = OkHttpClientProvider.getOkHttpClient();
            boolean found = false;
            for (Interceptor interceptor : client.interceptors()) {
                if (interceptor instanceof ApproovInterceptor) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                for (Interceptor interceptor : client.networkInterceptors()) {
                    if (interceptor instanceof ApproovInterceptor) {
                        found = true;
                        break;
                    }
                }
            }
            promise.resolve(found);
        } catch (Exception e) {
            promise.reject("isInterceptorActive", "Error: " + e.getMessage(), getErrorUserInfo(false));
        }
    }

    /**
     * Adds a pin change listener that will be notified of any future Approov
     * pin changes.
     * 
     * @param listener is the pin change listener
     */
    public synchronized void addPinChangeListener(PinChangeListener listener) {
        pinChangeListeners.add(listener);
    }

    /**
     * Gets all of the pin change listeners.
     * 
     * @return List of pin change listeners
     */
    public synchronized List<PinChangeListener> getPinChangeListeners() {
        return new ArrayList<PinChangeListener>(pinChangeListeners);
    }

    /**
     * Notifies pin change listeners of a pin update.
     */
    public void notifyPinChangeListeners() {
        List<PinChangeListener> listeners = getPinChangeListeners();
        for (PinChangeListener listener : listeners) {
            listener.approovPinsUpdated();
        }
    }

    /**
     * Helper to create a WritableMap, tolerating test environments where native
     * libraries aren't loaded.
     */
    private WritableMap safeCreateMap() {
        try {
            return Arguments.createMap();
        } catch (Throwable t) {
            return new com.facebook.react.bridge.JavaOnlyMap();
        }
    }

    /**
     * Helper to create a WritableArray, tolerating test environments where native
     * libraries aren't loaded.
     */
    private WritableArray safeCreateArray() {
        try {
            return Arguments.createArray();
        } catch (Throwable t) {
            return new com.facebook.react.bridge.JavaOnlyArray();
        }
    }

    /**
     * Construct a user info map for an error result.
     *
     * @param isNetworkError is true for a network, as opposed to general, error
     *                       type
     */
    private WritableMap getErrorUserInfo(boolean isNetworkError) {
        WritableMap userInfo = safeCreateMap();
        if (isNetworkError)
            userInfo.putString("type", "network");
        else
            userInfo.putString("type", "general");
        return userInfo;
    }

    /**
     * Construct a user info map for a rejection result.
     * 
     * @param rejectionARC     the ARC or empty string if not enabled
     * @param rejectionReasons the rejection reasons or empty string if not enabled
     */
    private WritableMap getRejectionUserInfo(String rejectionARC, String rejectionReasons) {
        WritableMap userInfo = safeCreateMap();
        userInfo.putString("type", "rejection");
        userInfo.putString("rejectionARC", rejectionARC);
        userInfo.putString("rejectionReasons", rejectionReasons);
        return userInfo;
    }

    /**
     * Sets the earliest network request based on the current time plus the window
     * period if
     * the time has not been previously set.
     */
    public synchronized void setEarliestNetworkRequestTime() {
        if (earliestNetworkRequestTime == 0) {
            earliestNetworkRequestTime = System.currentTimeMillis() + STARTUP_SYNC_TIME_WINDOW;
            log(LOG_INFO, TAG, "startup sync time window started");
        }
    }

    /**
     * Clears the earliest network request time so any network requests can proceed
     * immediately.
     */
    private synchronized void clearEarliestNetworkRequestTime() {
        earliestNetworkRequestTime = 0;
    }

    /**
     * Returns the earliest time that network requests should be allowed, in
     * milliseconds, or 0
     * if they are allowed immediately. This is used to perform a synchronization on
     * any early network
     * request thats should perhaps be subject to Approov protection that are
     * performed prior to the
     * initialization.
     * 
     * @return earliest network time in milliseconds, or 0 if no delay should be
     *         imposed
     */
    public synchronized long getEarliestNetworkRequestTime() {
        return earliestNetworkRequestTime;
    }

    /**
     * Initializes the ApproovService with an account configuration and comment.
     * Resets service-layer state if the configuration changes. A same-config
     * re-initialization preserves any configuration applied after the first setup.
     * The platform SDK returns true on first initialization or false if it is
     * already initialized with the same configuration (treated as success). Any other
     * failure — such as a different-config conflict — throws and is surfaced as a
     * rejected promise.
     *
     * @param config  the configuration string, or empty string for bypass mode
     * @param comment optional comment forwarded to the native SDK, or null
     * @param promise resolved on success, rejected on failure
     */
    @ReactMethod
    public void initialize(String config, String comment, Promise promise) {
        if (config == null) {
            promise.reject("initialize", "config must not be null; pass \"\" for bypass mode",
                    getErrorUserInfo(false));
            return;
        }

        // If we are already initialized with a valid config, ignore any subsequent
        // empty config initialization
        if (isApproovEnabled() && config.isEmpty()) {
            log(LOG_INFO, TAG, "ApproovService already initialized with a valid config; ignoring empty configuration");
            promise.resolve(null);
            return;
        }

        // Detect whether this is a re-initialization with the identical config that is
        // already in force. Re-initializing with the same config (e.g. an ApproovProvider
        // remount, a React StrictMode double-invoke or Fast Refresh in development) must
        // NOT discard the runtime configuration the app set up after the first initialize()
        // call — substitution headers, exclusion URL regexes and token/binding header
        // settings. Wiping those silently would drop request mutations and, more seriously,
        // exclusion rules that are security relevant. Only a genuinely different config
        // resets the service-layer state.
        boolean configUnchanged = isInitialized && config.equals(initialConfig);

        // Initialize the platform SDK if not in bypass mode (empty config).
        // State is only modified after the SDK confirms success, preserving the current
        // operating mode (protected or bypass) if the call fails.
        try {
            if (!config.isEmpty()) {
                boolean sdkInitialized = Approov.initialize(applicationContext, config, "auto", comment);
                if (!sdkInitialized) {
                    log(LOG_DEBUG, TAG, "Approov SDK already initialized");
                }
            }
            // SDK succeeded (or bypass) — now commit new service-layer state. The runtime
            // configuration is only reset when the config actually changes; a same-config
            // re-initialization preserves any configuration applied after the first call.
            //
            // The reset and commit are performed under the instance monitor so the
            // transition is atomic relative to the interceptor, which reads isInitialized,
            // isApproovEnabled and the header/substitution/exclusion state through
            // synchronized getters on OkHttp network threads. Without this lock a request
            // racing a re-initialization could observe the transient isInitialized=false /
            // initialConfig=null window (these are non-volatile static fields written with
            // no happens-before guarantee), and forward a request that should be protected
            // without an Approov token. The platform SDK call above is intentionally left
            // outside the lock so its network work never blocks those getters. iOS performs
            // the equivalent reset inside @synchronized(initializerLock).
            synchronized (this) {
                if (!configUnchanged) {
                    isInitialized = false;
                    initialConfig = null;
                    useApproovStatusIfNoToken = false;
                    approovTokenHeader = APPROOV_TOKEN_HEADER;
                    approovTraceIDHeader = APPROOV_TRACE_ID_HEADER;
                    approovTokenPrefix = APPROOV_TOKEN_PREFIX;
                    bindingHeader = null;
                    substitutionHeaders = new HashMap<>();
                    substitutionQueryParams = new HashMap<>();
                    exclusionURLRegexs = new HashMap<>();
                    suppressLoggingUnknownURL = false;
                    sessionMetadataCollectionEnabled = true;
                    // Reset any custom service mutator so overrides do not persist across
                    // an initialization boundary (root TESTING_REQUIREMENTS.md section 2,
                    // "Service Mutator Reset"). Restore a fresh ApproovDefaultMessageSigning
                    // — the React Native default performs HTTP message signing — rather than
                    // the no-signing ApproovServiceMutator.DEFAULT, which would silently
                    // disable default signing on every re-initialization.
                    ApproovDefaultMessageSigning defaultMutator = new ApproovDefaultMessageSigning();
                    defaultMutator.setDefaultFactory(
                            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
                    serviceMutator = defaultMutator;
                }
                initialConfig = config;
                isInitialized = true;
            }
            clearEarliestNetworkRequestTime();
            if (isApproovEnabled()) {
                Approov.setUserProperty("approov-react-native");
                log(LOG_INFO, TAG, "initialized on deviceID " + Approov.getDeviceID());
                notifyPinChangeListeners();
            } else {
                log(LOG_INFO, TAG, "initialized without Approov SDK");
            }
            if (pendingPrefetch) {
                prefetch();
                pendingPrefetch = false;
            }
            promise.resolve(null);
        } catch (IllegalArgumentException e) {
            log(LOG_ERROR, TAG, "initialization failed: " + e.getMessage());
            // Release the startup sync gate so any waiting threads are not held indefinitely.
            // Service-layer state is NOT modified — previous operating mode is preserved.
            clearEarliestNetworkRequestTime();
            promise.reject("initialize", "initialize IllegalArgument: " + e.getMessage(), getErrorUserInfo(false));
        } catch (IllegalStateException e) {
            log(LOG_ERROR, TAG, "initialization failed: " + e.getMessage());
            // Release the startup sync gate so any waiting threads are not held indefinitely.
            // Service-layer state is NOT modified — previous operating mode is preserved.
            clearEarliestNetworkRequestTime();
            promise.reject("initialize", "initialize IllegalState: " + e.getMessage(), getErrorUserInfo(false));
        }
    }

    /**
     * Returns the Approov initialization status.
     * 
     * @return true if Approov is initialized, false otherwise
     */
    public synchronized boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Returns the Approov service-layer initialization status to the React Native
     * bridge.
     *
     * @param promise React Native promise resolved with true if the service layer
     *                is
     *                initialized
     */
    @ReactMethod
    public void isInitialized(Promise promise) {
        promise.resolve(isInitialized());
    }

    /**
     * Returns true when the service layer is initialized and Approov-backed
     * request protection is active.
     */
    public synchronized boolean isApproovEnabled() {
        return isInitialized && initialConfig != null && !initialConfig.isEmpty();
    }

    /**
     * Returns true when the service layer is initialized and the native Approov SDK
     * is active.
     *
     * @param promise React Native promise resolved with true if Approov-backed
     *                protection is enabled
     */
    @ReactMethod
    public void isApproovEnabled(Promise promise) {
        promise.resolve(isApproovEnabled());
    }

    /**
     * Gets the last ARC (Attestation Response Code) code.
     *
     * Always resolves with a string (ARC or empty string).
     *
     * @param promise React Native promise to resolve with ARC string or empty
     *                string
     */
    @ReactMethod
    public void getLastARC(Promise promise) {
        log(LOG_INFO, TAG, "ApproovService: getLastARC");
        if (!isInitialized || !isApproovEnabled()) {
            promise.resolve("");
            return;
        }

        // Get the dynamic pins from Approov
        Map<String, List<String>> approovPins = Approov.getPins("public-key-sha256");
        if (approovPins == null || approovPins.isEmpty()) {
            log(LOG_ERROR, TAG, "ApproovService: no host pinning information available");
            promise.resolve("");
            return;
        }
        // The approovPins contains a map of hostnames to pin strings. Skip '*' and use
        // another hostname if available.
        String hostname = null;
        for (String key : approovPins.keySet()) {
            if (!"*".equals(key)) {
                hostname = key;
                break;
            }
        }
        if (hostname != null) {
            try {
                final String fetchURL = hostname.contains("://") ? hostname : "https://" + hostname;
                Approov.fetchApproovToken(new Approov.TokenFetchCallback() {
                    @Override
                    public void approovCallback(Approov.TokenFetchResult result) {
                        if (result.getToken() != null && !result.getToken().isEmpty()) {
                            String arc = result.getARC();
                            if (arc != null) {
                                promise.resolve(arc);
                                return;
                            }
                        }
                        log(LOG_INFO, TAG, "ApproovService: ARC code unavailable");
                        promise.resolve("");
                    }
                }, fetchURL);
            } catch (Exception e) {
                log(LOG_ERROR, TAG, "ApproovService: error fetching ARC", e);
                promise.resolve("");
            }
        } else {
            log(LOG_INFO, TAG, "ApproovService: ARC code unavailable");
            promise.resolve("");
        }
    }

    /**
     * Sets an install attributes token to be sent to the server and associated with
     * this particular
     * app installation for future Approov token fetches. The token must be signed,
     * within its
     * expiry time and bound to the correct device ID for it to be accepted by the
     * server.
     * Calling this method ensures that the next call to fetch an Approov
     * token will not use a cached version, so that this information can be
     * transmitted to the server.
     *
     * @param attrs is the signed JWT holding the new install attributes
     */
    @ReactMethod
    public void setInstallAttrsInToken(String attrs, Promise promise) {
        if (!isApproovEnabled()) {
            log(LOG_ERROR, TAG, "setInstallAttrsInToken: Approov is not enabled");
            promise.reject("setInstallAttrsInToken", "Approov is not enabled", getErrorUserInfo(false));
            return;
        }
        try {
            Approov.setInstallAttrsInToken(attrs);
            log(LOG_DEBUG, TAG, "setInstallAttrsInToken");
            promise.resolve(null);
        } catch (IllegalArgumentException e) {
            log(LOG_ERROR, TAG, "setInstallAttrsInToken failed with IllegalArgument: " + e.getMessage());
            promise.reject("setInstallAttrsInToken", "IllegalArgument: " + e.getMessage(), getErrorUserInfo(false));
        } catch (IllegalStateException e) {
            log(LOG_ERROR, TAG, "setInstallAttrsInToken failed with IllegalState: " + e.getMessage());
            promise.reject("setInstallAttrsInToken", "IllegalState: " + e.getMessage(), getErrorUserInfo(false));
        }
    }

    /**
     * Sets a flag indicating if the network interceptor should allow requests when
     * the Approov token fetch fails
     * due to a networking issue. If this is set then the request is allowed to
     * proceed without adding a token,
     * which means the app can use its own user feedback if the backend cannot be
     * reached. In this case, we
     * simply log an info message indicating that this function was called, but it
     * is no longer used.
     */
    @ReactMethod
    public synchronized void setProceedOnNetworkFail() {
        log(LOG_DEBUG, TAG, "setProceedOnNetworkFail has been deprecated and does nothing");
    }

    /**
     * Sets a flag indicating if the Approov fetch status should be used as the
     * token header value if the actual token fetch fails or returns an empty token.
     *
     * @param shouldUse is true if the status should be used as the token value
     */
    @ReactMethod
    public synchronized void setUseApproovStatusIfNoToken(boolean shouldUse) {
        log(LOG_DEBUG, TAG, "setUseApproovStatusIfNoToken " + shouldUse);
        setUseApproovStatusIfNoTokenInternal(shouldUse);
    }

    /**
     * Determines if requests should proceed on a network fail or not.
     * 
     * @return true if requests should proceed after a network fail
     * @deprecated Always returns false
     */
    @Deprecated
    public synchronized boolean isProceedOnNetworkFail() {
        return false;
    }

    /**
     * Sets a development key indicating that the app is a development version and
     * it should
     * pass attestation even if the app is not registered or it is running on an
     * emulator. The
     * development key value can be rotated at any point in the account if a version
     * of the app
     * containing the development key is accidentally released. This is primarily
     * used for situations where the app package must be modified or resigned in
     * some way as part of the testing process.
     *
     * @param devKey  is the development key to be used
     * @param promise to be fulfilled once the development key has been set
     */
    @ReactMethod
    public void setDevKey(String devKey, Promise promise) {
        if (!isApproovEnabled()) {
            log(LOG_ERROR, TAG, "setDevKey: Approov is not enabled");
            promise.reject("setDevKey", "Approov is not enabled", getErrorUserInfo(false));
            return;
        }
        try {
            Approov.setDevKey(devKey);
            log(LOG_DEBUG, TAG, "setDevKey");
            promise.resolve(null);
        } catch (IllegalStateException e) {
            promise.reject("setDevKey", "IllegalState: " + e.getMessage(), getErrorUserInfo(false));
        } catch (IllegalArgumentException e) {
            promise.reject("setDevKey", "IllegalArgument: " + e.getMessage(), getErrorUserInfo(false));
        }
    }

    /**
     * Indicates that logging for unknown (and excluded) URLs should be suppressed.
     * This prevents excessive
     * logging for fetches not associated with Approov protection.
     */
    @ReactMethod
    public synchronized void setSuppressLoggingUnknownURL() {
        log(LOG_DEBUG, TAG, "setSuppressLoggingUnknownURL");
        suppressLoggingUnknownURL = true;
    }

    /**
     * iOS-specific delegate allow-list API.
     * No-op on Android to keep the JS API surface cross-platform safe.
     *
     * @param delegatePattern the delegate class name pattern
     */
    @ReactMethod
    public void addAllowedDelegate(String delegatePattern) {
        log(LOG_DEBUG, TAG, "addAllowedDelegate: no-op on Android (" + delegatePattern + ")");
    }

    /**
     * Enables or disables extended session metadata collection.
     * No-op on Android today, but retained for JS API parity.
     *
     * @param enabled true to enable metadata collection, false to disable it
     */
    @ReactMethod
    public synchronized void setSessionMetadataCollectionEnabled(boolean enabled) {
        log(LOG_DEBUG, TAG, "setSessionMetadataCollectionEnabled: no-op on Android (" + enabled + ")");
        sessionMetadataCollectionEnabled = enabled;
    }

    /**
     * Returns whether extended session metadata collection is enabled.
     *
     * @param promise resolves to the current flag value
     */
    @ReactMethod
    public synchronized void getSessionMetadataCollectionEnabled(Promise promise) {
        promise.resolve(sessionMetadataCollectionEnabled);
    }

    /**
     * Retrieves session diagnostics functionality for React Native cross-platform
     * parity.
     * Note: Android does not retain an equivalent granular session ledger today.
     *
     * @param promise resolves to the session diagnostics
     */
    @ReactMethod
    public void getSessionDiagnostics(Promise promise) {
        WritableMap diagnostics = safeCreateMap();
        diagnostics.putBoolean("enabled", sessionMetadataCollectionEnabled);
        diagnostics.putString("message", "Android does not retain an extended session ledger.");
        promise.resolve(diagnostics);
    }

    /**
     * Determines if requests should proceed on a network fail or not.
     * 
     * @return true if logging should be suppressed for unknown (and excluded) URLs.
     */
    public synchronized boolean isSuppressLoggingUnknownURL() {
        return suppressLoggingUnknownURL;
    }

    /**
     * Sets the header that the Approov token is added on, as well as an optional
     * prefix String (such as "Bearer "). By default the token is provided on
     * "Approov-Token" with no prefix.
     *
     * @param header is the header to place the Approov token on
     * @param prefix is any prefix String for the Approov token header
     */
    @ReactMethod
    public synchronized void setTokenHeader(String header, String prefix) {
        log(LOG_DEBUG, TAG, "setTokenHeader " + header + ", " + prefix);
        approovTokenHeader = header;
        approovTokenPrefix = prefix;
    }

    /**
     * Provides the Approov token header.
     * 
     * @return String token header
     */
    public synchronized String getTokenHeader() {
        return approovTokenHeader;
    }

    /**
     * Sets the header name that is used to pass any optional Approov TraceID debug
     * value. By default the TraceID is provided on "Approov-TraceID" if one is
     * available. Passing null disables adding the TraceID header.
     *
     * @param header is the name of the header on which to place the Approov
     *               TraceID, or null to disable the header
     */
    @ReactMethod
    public synchronized void setTraceIDHeader(String header) {
        log(LOG_DEBUG, TAG, "setTraceIDHeader " + header);
        approovTraceIDHeader = header;
    }

    /**
     * Provides the Approov TraceID header.
     * 
     * @param promise React Native promise to resolve with the TraceID header
     */
    @ReactMethod
    public void getTraceIDHeader(Promise promise) {
        promise.resolve(getTraceIDHeader());
    }

    /**
     * Gets the name of the header that is used to hold the optional Approov
     * TraceID.
     *
     * @return String the name of the header used for the Approov TraceID, or
     *         null if disabled
     */
    public synchronized String getTraceIDHeader() {
        return approovTraceIDHeader;
    }

    /**
     * Provides any specified Approov token prefix.
     * 
     * @return String token prefix
     */
    public synchronized String getTokenPrefix() {
        return approovTokenPrefix;
    }

    /**
     * Sets a binding header that may be present on requests being made. A header
     * should be
     * chosen whose value is unchanging for most requests (such as an Authorization
     * header).
     * If the header is present, then a hash of the header value is included in the
     * issued Approov
     * tokens to bind them to the value. This may then be verified by the backend
     * API integration.
     *
     * @param header is the header to use for Approov token binding
     */
    @ReactMethod
    public synchronized void setBindingHeader(String header) {
        log(LOG_DEBUG, TAG, "setBindingHeader " + header);
        bindingHeader = header;
    }

    /**
     * Provides any specified binding header.
     * 
     * @return String binding header or null if not set
     */
    public synchronized String getBindingHeader() {
        return bindingHeader;
    }

    /**
     * Adds the name of a header which should be subject to secure strings
     * substitution. This
     * means that if the header is present then the value will be used as a key to
     * look up a
     * secure string value which will be substituted into the header value instead.
     * This allows
     * easy migration to the use of secure strings. A required prefix may be
     * specified to deal
     * with cases such as the use of "Bearer " prefixed before values in an
     * authorization header.
     *
     * @param header         is the header to be marked for substitution
     * @param requiredPrefix is any required prefix to the value being substituted
     */
    @ReactMethod
    public synchronized void addSubstitutionHeader(String header, String requiredPrefix) {
        log(LOG_DEBUG, TAG, "addSubstitutionHeader " + header + ", " + requiredPrefix);
        substitutionHeaders.put(header, requiredPrefix);
    }

    /**
     * Removes a header previously added using addSubstitutionHeader.
     *
     * @param header is the header to be removed for substitution
     */
    @ReactMethod
    public synchronized void removeSubstitutionHeader(String header) {
        log(LOG_DEBUG, TAG, "removeSubstitutionHeader " + header);
        substitutionHeaders.remove(header);
    }

    /**
     * Gets all of the substitution headers that are currently setup in a new map.
     * 
     * @return Map<String, String> of the substitution headers mapped to their
     *         required prefix
     */
    public synchronized Map<String, String> getSubstitutionHeaders() {
        return new HashMap<>(substitutionHeaders);
    }

    /**
     * Adds a key name for a query parameter that should be subject to secure
     * strings substitution.
     * This means that if the query parameter is present in a URL then the value
     * will be used as a
     * key to look up a secure string value which will be substituted as the query
     * parameter value
     * instead. This allows easy migration to the use of secure strings.
     *
     * @param key is the query parameter key name to be added for substitution
     */
    @ReactMethod
    public synchronized void addSubstitutionQueryParam(String key) {
        try {
            Pattern pattern = Pattern.compile("[\\?&]" + key + "=([^&;]+)");
            substitutionQueryParams.put(key, pattern);
            log(LOG_DEBUG, TAG, "addSubstitutionQueryParam " + key);
        } catch (PatternSyntaxException e) {
            log(LOG_ERROR, TAG, "addSubstitutionQueryParam " + key + " error: " + e.getMessage());
        }
    }

    /**
     * Removes a query parameter key name previously added using
     * addSubstitutionQueryParam.
     *
     * @param key is the query parameter key name to be removed for substitution
     */
    @ReactMethod
    public synchronized void removeSubstitutionQueryParam(String key) {
        log(LOG_DEBUG, TAG, "removeSubstitutionQueryParam " + key);
        substitutionQueryParams.remove(key);
    }

    /**
     * Gets all of the substitution query parameters that are currently setup in a
     * new map.
     * 
     * @return Map<String, Pattern> of the substitution query parameters mapped to
     *         their regex patterns
     */
    public synchronized Map<String, Pattern> getSubstitutionQueryParams() {
        return new HashMap<>(substitutionQueryParams);
    }

    /**
     * Adds an exclusion URL regular expression. If a URL for a request matches this
     * regular expression then it will bypass Approov request mutation, such as
     * token injection, trace headers, message signing and secure string
     * substitution. If the URL belongs to a host that is protected by Approov then
     * the connection is still subject to Approov pinning. Note that this facility
     * must be used with
     * EXTREME CAUTION due to the impact of dynamic pinning. Pinning may be applied
     * to all domains added
     * using Approov, and updates to the pins are received when an Approov fetch is
     * performed. If you
     * exclude some URLs on domains that are protected with Approov, then these will
     * be protected with
     * Approov pins but without a path to update the pins until a URL is used that
     * is not excluded. Thus
     * you are responsible for ensuring that there is always a possibility of
     * calling a non-excluded
     * URL, or you should make an explicit call to fetchToken if there are
     * persistent pinning failures.
     * Conversely, use of those option may allow a connection to be established
     * before any dynamic pins
     * have been received via Approov, thus potentially opening the channel to a
     * MitM.
     *
     * @param urlRegex is the regular expression that will be compared against URLs
     *                 to exlude them
     */
    @ReactMethod
    public synchronized void addExclusionURLRegex(String urlRegex) {
        try {
            Pattern pattern = Pattern.compile(urlRegex);
            exclusionURLRegexs.put(urlRegex, pattern);
            log(LOG_DEBUG, TAG, "addExclusionURLRegex " + urlRegex);
        } catch (PatternSyntaxException e) {
            log(LOG_ERROR, TAG, "addExclusionURLRegex " + urlRegex + " error: " + e.getMessage());
        }
    }

    /**
     * Removes an exclusion URL regular expression previously added using
     * addExclusionURLRegex.
     *
     * @param urlRegex is the regular expression that will be compared against URLs
     *                 to exlude them
     */
    @ReactMethod
    public synchronized void removeExclusionURLRegex(String urlRegex) {
        log(LOG_DEBUG, TAG, "removeExclusionURLRegex " + urlRegex);
        exclusionURLRegexs.remove(urlRegex);
    }

    /**
     * Gets all of the exclusion URL regexs that are currently setup in a new map.
     * 
     * @return Map<String, Pattern> of the exclusion URL regexs mapped to their
     *         regex patterns
     */
    public synchronized Map<String, Pattern> getExclusionURLRegexs() {
        return new HashMap<>(exclusionURLRegexs);
    }

    /**
     * Performs a prefetch in the background. The domain "approov.io" is simply used
     * to initiate the
     * fetch and does not need to be a valid API for the account. This method can be
     * used to lower the
     * effective latency of a subsequent token fetch or secure strings lookup by
     * starting the operation
     * earlier so the subsequent fetch should be able to use cached results. If this
     * is called prior
     * to the initialization then a pending prefetch is setup to be executed just
     * after initialization.
     */
    @ReactMethod
    public synchronized void prefetch() {
        if (isApproovEnabled()) {
            log(LOG_INFO, TAG, "prefetch initiated");
            Approov.fetchApproovToken(new PrefetchHandler(), "approov.io");
        } else if (isInitialized) {
            log(LOG_INFO, TAG, "prefetch bypassed because Approov is disabled");
        } else {
            log(LOG_INFO, TAG, "prefetch pending");
            pendingPrefetch = true;
        }
    }

    /**
     * Gets the signature for the given message. This uses an account specific
     * message signing key.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature
     * @throws ApproovException if there was a problem
     */
    public static String getAccountMessageSignature(String message) throws ApproovException {
        if (!isInitialized || initialConfig == null || initialConfig.isEmpty()) {
            throw new ApproovException("getAccountMessageSignature: Approov is not enabled");
        }
        try {
            String signature = Approov.getMessageSignature(message);
            if (signature == null)
                throw new ApproovException("no account signature available");
            return signature;
        } catch (IllegalStateException e) {
            throw new ApproovException(e);
        } catch (IllegalArgumentException e) {
            throw new ApproovException(e);
        }
    }

    /**
     * Gets the install signature for the given message. This uses an app install
     * specific message signing key.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature in ASN.1 DER format
     * @throws ApproovException if there was a problem
     */
    public static String getInstallMessageSignature(String message) throws ApproovException {
        if (!isInitialized || initialConfig == null || initialConfig.isEmpty()) {
            throw new ApproovException("getInstallMessageSignature: Approov is not enabled");
        }
        try {
            String signature = Approov.getInstallMessageSignature(message);
            if (signature == null)
                throw new ApproovException("no install signature available");
            return signature;
        } catch (IllegalStateException e) {
            throw new ApproovException(e);
        } catch (IllegalArgumentException e) {
            throw new ApproovException(e);
        }
    }

    /**
     * Callback handler for prefetching. We simply log as we don't need the token
     * itself, as it will be returned as a cached value on a subsequent token fetch.
     */
    final class PrefetchHandler implements Approov.TokenFetchCallback {
        @Override
        public void approovCallback(Approov.TokenFetchResult result) {
            if ((result.getStatus() == Approov.TokenFetchStatus.SUCCESS) ||
                    (result.getStatus() == Approov.TokenFetchStatus.UNKNOWN_URL) ||
                    (result.getStatus() == Approov.TokenFetchStatus.UNPROTECTED_URL)) {
                log(LOG_INFO, TAG, "prefetch success");
            } else {
                log(LOG_INFO, TAG, "prefetch failure: " + result.getStatus().toString());
            }
        }
    }

    /**
     * Performs a precheck to determine if the app will pass attestation. This
     * requires secure
     * strings to be enabled for the account, although no strings need to be set up.
     * The promise
     * will be rejected if there is some problem. The promise rejection
     * userInfo.type will be
     * "network" for networking issues where a user initiated retry of the operation
     * should be
     * allowed. If the attestation is rejected then the type will be "rejected" and
     * then
     * userInfo.rejectionARC and userInfo.rejectionReasons may provide additional
     * information about
     * the Approov rejection.
     * 
     * @param promise to be fulfilled once the precheck is completed
     */
    @ReactMethod
    public void precheck(Promise promise) {
        if (!isInitialized) {
            promise.reject("approov_error", "Approov is not initialized", getErrorUserInfo(false));
            return;
        }
        if (!isApproovEnabled()) {
            promise.reject("approov_error", "Approov is disabled", getErrorUserInfo(false));
            return;
        }
        try {
            Approov.fetchSecureString(new PrecheckHandler(promise), "precheck-dummy-key", null);
        } catch (IllegalStateException e) {
            promise.reject("precheck", "IllegalState: " + e.getMessage(), getErrorUserInfo(false));
        } catch (IllegalArgumentException e) {
            promise.reject("precheck", "IllegalArgument: " + e.getMessage(), getErrorUserInfo(false));
        }
    }

    /**
     * Callback handler for prechecking that fulfills the provided promise when
     * complete.
     */
    final class PrecheckHandler implements Approov.TokenFetchCallback {
        // promise to be fulflled by the handler
        private Promise promise;

        /**
         * Construct a new PrecheckHandler.
         * 
         * @param promise is the Promise to be fulfilled by the handler
         */
        public PrecheckHandler(Promise promise) {
            this.promise = promise;
        }

        @Override
        public void approovCallback(Approov.TokenFetchResult result) {
            if (result.getStatus() == Approov.TokenFetchStatus.UNKNOWN_KEY)
                log(LOG_INFO, TAG, "precheck: passed");
            else
                log(LOG_INFO, TAG, "precheck: " + result.getStatus().toString());
            if (result.getStatus() == Approov.TokenFetchStatus.REJECTED)
                // the precheck is rejected
                promise.reject("precheck", "precheck: REJECTED " + result.getARC() + " " + result.getRejectionReasons(),
                        getRejectionUserInfo(result.getARC(), result.getRejectionReasons()));
            else if ((result.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                    (result.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                    (result.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
                // we are unable to complete the precheck due to network conditions
                promise.reject("precheck", "precheck: " + result.getStatus().toString(), getErrorUserInfo(true));
            else if ((result.getStatus() != Approov.TokenFetchStatus.SUCCESS) &&
                    (result.getStatus() != Approov.TokenFetchStatus.UNKNOWN_KEY))
                // we are unable to complete the precheck due to a more permanent error
                promise.reject("precheck", "precheck: " + result.getStatus().toString(), getErrorUserInfo(false));
            else
                // notify that the precheck is now complete
                promise.resolve(null);
        }
    }

    /**
     * Gets the device ID used by Approov to identify the particular device that the
     * SDK is running on. Note
     * that different Approov apps on the same device will return a different ID.
     * Moreover, the ID may be
     * changed by an uninstall and reinstall of the app.
     * 
     * @param promise to be fulfilled with the device ID
     */
    @ReactMethod
    public void getDeviceID(Promise promise) {
        try {
            String deviceID = Approov.getDeviceID();
            log(LOG_DEBUG, TAG, "getDeviceID: " + deviceID);
            promise.resolve(deviceID);
        } catch (IllegalStateException e) {
            promise.reject("getDeviceID", "IllegalState: " + e.getMessage(), getErrorUserInfo(false));
        }
    }

    /**
     * Directly sets the data hash to be included in subsequently fetched Approov
     * tokens. If the hash is
     * different from any previously set value then this will cause the next token
     * fetch operation to
     * fetch a new token with the correct payload data hash. The hash appears in the
     * 'pay' claim of the Approov token as a base64 encoded string of the SHA256
     * hash of the
     * data. Note that the data is hashed locally and never sent to the Approov
     * cloud service.
     * 
     * @param data    is the data to be hashed and set in the token
     * @param promise to be fulfilled once the data hash has been changed
     */
    @ReactMethod
    public void setDataHashInToken(String data, Promise promise) {
        if (!isInitialized) {
            promise.reject("approov_error", "Approov is not initialized", getErrorUserInfo(false));
            return;
        }
        if (!isApproovEnabled()) {
            promise.reject("approov_error", "Approov is disabled", getErrorUserInfo(false));
            return;
        }
        if (data == null) {
            promise.reject("setDataHashInToken", "IllegalArgument: data must not be null", getErrorUserInfo(false));
            return;
        }
        try {
            Approov.setDataHashInToken(data);
            log(LOG_DEBUG, TAG, "setDataHashInToken");
            promise.resolve(null);
        } catch (IllegalStateException e) {
            promise.reject("setDataHashInToken", "IllegalState: " + e.getMessage(), getErrorUserInfo(false));
        } catch (IllegalArgumentException e) {
            promise.reject("setDataHashInToken", "IllegalArgument: " + e.getMessage(), getErrorUserInfo(false));
        }
    }

    /**
     * Performs an Approov token fetch for the given URL. This should be used in
     * situations where it
     * is not possible to use the networking interception to add the token. This
     * will
     * likely require network access so may take some time to complete. The promise
     * will be
     * rejected if there is some problem. The promise rejection userInfo.type will
     * be "network" for
     * networking issues where a user initiated retry of the operation should be
     * allowed.
     * 
     * @param url     is the URL giving the domain for the token fetch
     * @param promise to be fulfilled with the result of the fetch
     */
    @ReactMethod
    public void fetchToken(String url, Promise promise) {
        if (!isInitialized) {
            promise.reject("approov_error", "Approov is not initialized", getErrorUserInfo(false));
            return;
        }
        if (!isApproovEnabled()) {
            promise.reject("approov_error", "Approov is disabled", getErrorUserInfo(false));
            return;
        }
        try {
            Approov.fetchApproovToken(new FetchTokenHandler(promise), url);
        } catch (IllegalStateException e) {
            promise.reject("fetchToken", "IllegalState: " + e.getMessage(), getErrorUserInfo(false));
        } catch (IllegalArgumentException e) {
            promise.reject("fetchToken", "IllegalArgument: " + e.getMessage(), getErrorUserInfo(false));
        }
    }

    /**
     * Callback handler for fetching a token that fulfills the provided promise.
     */
    final class FetchTokenHandler implements Approov.TokenFetchCallback {
        // promise to be fulflled by the handler
        private Promise promise;

        /**
         * Construct a new FetchTokenHandler.
         * 
         * @param promise is the Promise to be fulfilled by the handler
         */
        public FetchTokenHandler(Promise promise) {
            this.promise = promise;
        }

        @Override
        public void approovCallback(Approov.TokenFetchResult result) {
            log(LOG_INFO, TAG, "fetchToken: " + result.getStatus().toString());
            if ((result.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                    (result.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                    (result.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
                // we are unable to get the token due to network conditions
                promise.reject("fetchToken", "fetchToken: " + result.getStatus().toString(), getErrorUserInfo(true));
            else if (result.getStatus() != Approov.TokenFetchStatus.SUCCESS)
                // we are unable to get the token due to a more permanent error
                promise.reject("fetchToken", "fetchToken: " + result.getStatus().toString(), getErrorUserInfo(false));
            else
                // provide the Approov token result
                promise.resolve(result.getToken());
        }
    }

    /**
     * Gets the signature for the given message. This uses an account specific
     * message signing key that is
     * transmitted to the SDK after a successful fetch if the facility is enabled
     * for the account. Note
     * that if the attestation failed then the signing key provided is actually
     * random so that the
     * signature will be incorrect. An Approov token should always be included in
     * the message
     * being signed and sent alongside this signature to prevent replay attacks.
     *
     * @param message is the message whose content is to be signed
     * @param promise to be fulfilled with the signature
     */
    @ReactMethod
    public void getMessageSignature(String message, Promise promise) {
        if (!isApproovEnabled()) {
            log(LOG_ERROR, TAG, "getMessageSignature: Approov is not enabled");
            promise.reject("getMessageSignature", "Approov is not enabled", getErrorUserInfo(false));
            return;
        }
        try {
            String signature = Approov.getMessageSignature(message);
            log(LOG_DEBUG, TAG, "getMessageSignature");
            if (signature == null)
                promise.reject("getMessageSignature", "no signature available", getErrorUserInfo(false));
            else
                promise.resolve(signature);
        } catch (IllegalStateException e) {
            promise.reject("getMessageSignature", "IllegalState: " + e.getMessage(), getErrorUserInfo(false));
        } catch (IllegalArgumentException e) {
            promise.reject("getMessageSignature", "IllegalArgument: " + e.getMessage(), getErrorUserInfo(false));
        }
    }

    /**
     * Fetches a secure string with the given key. If newDef is not null then a
     * secure string for the particular app instance may be defined. In this case
     * the
     * new value is returned as the secure string. Use of an empty string for newDef
     * removes
     * the string entry. The promise will be rejected if there is some problem. The
     * promise
     * rejection userInfo.type will be "network" for networking issues where a user
     * initiated
     * retry of the operation should be allowed. If the attestation is rejected then
     * the type
     * will be "rejected" and then userInfo.rejectionARC and
     * userInfo.rejectionReasons may provide
     * additional information about the Approov rejection. Note that the returned
     * string
     * should NEVER be cached by your app, you should call this function when it is
     * needed.
     *
     * @param key     is the secure string key to be looked up
     * @param newDef  is any new definition for the secure string, or null for
     *                lookup only
     * @param promise to be fulfilled with the secure string (should not be cached
     *                by your app)
     */
    @ReactMethod
    public void fetchSecureString(String key, String newDef, Promise promise) {
        if (!isInitialized) {
            promise.reject("approov_error", "Approov is not initialized", getErrorUserInfo(false));
            return;
        }
        if (!isApproovEnabled()) {
            promise.reject("approov_error", "Approov is disabled", getErrorUserInfo(false));
            return;
        }
        // determine the type of operation as the values themselves cannot be logged
        String type = "lookup";
        if (newDef != null)
            type = "definition";

        // The Android SDK treats null/empty/overlong keys as local argument
        // failures rather than attester-driven BAD_KEY / UNKNOWN_KEY results.
        if (key == null) {
            promise.reject("fetchSecureString", "IllegalArgument: secure string key is null", getErrorUserInfo(false));
            return;
        }
        if (key.isEmpty() || key.length() > 64) {
            promise.reject("fetchSecureString", "IllegalArgument: secure string key is empty or too long",
                    getErrorUserInfo(false));
            return;
        }

        // fetch any secure string keyed by the value, catching any exceptions the SDK
        // might throw
        try {
            Approov.fetchSecureString(new FetchSecureStringHandler(promise, type, key), key, newDef);
        } catch (IllegalStateException e) {
            promise.reject("fetchSecureString", "IllegalState: " + e.getMessage(), getErrorUserInfo(false));
        } catch (IllegalArgumentException e) {
            promise.reject("fetchSecureString", "IllegalArgument: " + e.getMessage(), getErrorUserInfo(false));
        }
    }

    /**
     * Callback handler for fetching a secure string that fulfills the provided
     * promise.
     */
    final class FetchSecureStringHandler implements Approov.TokenFetchCallback {
        // promise to be fulflled by the handler
        private Promise promise;

        // type of the secure string operation
        private String type;

        // secure string key being looked up
        private String key;

        /**
         * Construct a new FetchSecureStringHandler.
         * 
         * @param promise is the Promise to be fulfilled by the handler
         * @param type    is the type of secure string operation
         * @param key     is the secure string key being processed
         */
        public FetchSecureStringHandler(Promise promise, String type, String key) {
            this.promise = promise;
            this.type = type;
            this.key = key;
        }

        @Override
        public void approovCallback(Approov.TokenFetchResult result) {
            log(LOG_INFO, TAG, "fetchSecureString " + type + " for " + key + ": " + result.getStatus().toString());
            if (result.getStatus() == Approov.TokenFetchStatus.REJECTED)
                // the secure string fetch has been rejected
                promise.reject("precheck",
                        "fetchSecureString: REJECTED " + result.getARC() + " " + result.getRejectionReasons(),
                        getRejectionUserInfo(result.getARC(), result.getRejectionReasons()));
            else if ((result.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                    (result.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                    (result.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
                // we are unable to get the secure string due to network conditions
                promise.reject("fetchSecureString", "fetchSecureString: " + result.getStatus().toString(),
                        getErrorUserInfo(true));
            else if ((result.getStatus() != Approov.TokenFetchStatus.SUCCESS) &&
                    (result.getStatus() != Approov.TokenFetchStatus.UNKNOWN_KEY))
                // we are unable to get the secure string due to a more permanent error
                promise.reject("fetchSecureString", "fetchSecureString: " + result.getStatus().toString(),
                        getErrorUserInfo(false));
            else
                // provide the secure string result
                promise.resolve(result.getSecureString());
        }
    }

    /**
     * Fetches a custom JWT with the given payload. The promise will be rejected if
     * there is
     * some problem. The promise rejection userInfo.type will be "network" for
     * networking
     * issues where a user initiated retry of the operation should be allowed. If
     * the
     * attestation is rejected then the type will be "rejected" and then
     * userInfo.rejectionARC and
     * userInfo.rejectionReasons may provide additional information about the
     * Approov rejection.
     *
     * @param payload is the marshaled JSON object for the claims to be included
     * @param promise to be fulfilled with the custom JWT (should not be cached by
     *                your app)
     */
    @ReactMethod
    public void fetchCustomJWT(String payload, Promise promise) {
        if (!isInitialized) {
            promise.reject("approov_error", "Approov is not initialized", getErrorUserInfo(false));
            return;
        }
        if (!isApproovEnabled()) {
            promise.reject("approov_error", "Approov is disabled", getErrorUserInfo(false));
            return;
        }
        try {
            new org.json.JSONObject(payload);
        } catch (org.json.JSONException e) {
            promise.reject("fetchCustomJWT", "IllegalArgument: Malformed JSON payload", getErrorUserInfo(false));
            return;
        }

        try {
            Approov.fetchCustomJWT(new FetchCustomJWTHandler(promise), payload);
        } catch (IllegalStateException e) {
            promise.reject("fetchCustomJWT", "IllegalState: " + e.getMessage(), getErrorUserInfo(false));
        } catch (IllegalArgumentException e) {
            promise.reject("fetchCustomJWT", "IllegalArgument: " + e.getMessage(), getErrorUserInfo(false));
        }
    }

    /**
     * Callback handler for fetching a custom JWT that fulfills the provided
     * promise.
     */
    final class FetchCustomJWTHandler implements Approov.TokenFetchCallback {
        // promise to be fulflled by the handler
        private Promise promise;

        /**
         * Construct a new FetchCustomJWTHandler.
         * 
         * @param promise is the Promise to be fulfilled by the handler
         */
        public FetchCustomJWTHandler(Promise promise) {
            this.promise = promise;
        }

        @Override
        public void approovCallback(Approov.TokenFetchResult result) {
            log(LOG_INFO, TAG, "fetchCustomJWT: " + result.getStatus().toString());
            if (result.getStatus() == Approov.TokenFetchStatus.REJECTED)
                // the custom JWT fetch has been rejected
                promise.reject("precheck",
                        "fetchCustomJWT: REJECTED " + result.getARC() + " " + result.getRejectionReasons(),
                        getRejectionUserInfo(result.getARC(), result.getRejectionReasons()));
            else if ((result.getStatus() == Approov.TokenFetchStatus.NO_NETWORK) ||
                    (result.getStatus() == Approov.TokenFetchStatus.POOR_NETWORK) ||
                    (result.getStatus() == Approov.TokenFetchStatus.MITM_DETECTED))
                // we are unable to get the custom JWT due to network conditions
                promise.reject("fetchCustomJWT", "fetchCustomJWT: " + result.getStatus().toString(),
                        getErrorUserInfo(true));
            else if (result.getStatus() != Approov.TokenFetchStatus.SUCCESS)
                // we are unable to get the custom JWT due to a more permanent error
                promise.reject("fetchCustomJWT", "fetchCustomJWT: " + result.getStatus().toString(),
                        getErrorUserInfo(false));
            else
                // provide the custom JWT result
                promise.resolve(result.getToken());
        }
    }

    /**
     * Gets pinning diagnostics showing if the Approov interceptor and certificate
     * pinner are present.
     * 
     * @param promise to be fulfilled with the diagnostics map
     */
    @ReactMethod
    public void getPinningDiagnostics(Promise promise) {
        try {
            OkHttpClient client = OkHttpClientProvider.getOkHttpClient();
            WritableMap diagnostics = safeCreateMap();
            boolean isInterceptorPresent = false;
            WritableArray interceptors = safeCreateArray();

            for (Interceptor interceptor : client.interceptors()) {
                String name = interceptor.getClass().getName();
                interceptors.pushString(name);
                if (name.equals("io.approov.reactnative.ApproovInterceptor"))
                    isInterceptorPresent = true;
            }

            for (Interceptor interceptor : client.networkInterceptors()) {
                String name = interceptor.getClass().getName();
                interceptors.pushString(name);
                if (name.equals("io.approov.reactnative.ApproovInterceptor"))
                    isInterceptorPresent = true;
            }

            diagnostics.putBoolean("isInterceptorPresent", isInterceptorPresent);
            diagnostics.putArray("interceptors", interceptors);

            CertificatePinner pinner = client.certificatePinner();
            boolean isPinnerPresent = (pinner != null) && !pinner.equals(CertificatePinner.DEFAULT);
            diagnostics.putBoolean("isPinnerPresent", isPinnerPresent);

            log(LOG_INFO, TAG, "getPinningDiagnostics: " + diagnostics.toString());
            promise.resolve(diagnostics);
        } catch (Exception e) {
            promise.reject("getPinningDiagnostics", "Exception: " + e.getMessage(), getErrorUserInfo(false));
        }
    }

    /**
     * Updates the OkHttpClient factory to ensure Approov is being used. This checks
     * if the current
     * OkHttpClient is already using Approov and if not then it creates a new
     * OkHttpClient that
     * includes the Approov protection and sets this as the default shared client.
     * This is useful
     * if the OkHttpClient has been overwritten by another library (such as a 3rd
     * party SDK).
     * 
     * @param wrapExisting is true if the existing OkHttpClient should be wrapped,
     *                     false to start fresh
     * @param promise      to be fulfilled with the result
     */
    @ReactMethod
    public void updateClientFactory(boolean wrapExisting, Promise promise) {
        try {
            // we obtain the NetworkingModule and the current client it is using
            NetworkingModule networkingModule = getReactApplicationContext().getNativeModule(NetworkingModule.class);
            OkHttpClient currentClient = OkHttpClientProvider.getOkHttpClient();

            // if we are wrapping the existing client then we use it as the basis for the
            // new one
            OkHttpClient.Builder builder;
            if (wrapExisting) {
                builder = currentClient.newBuilder();
                // OkHttp's newBuilder() clones the interceptor list, so remove any
                // previously-added ApproovInterceptors to avoid stacking duplicate
                // token-fetching and signature-generation on repeated recovery calls.
                builder.interceptors().removeIf(i -> i instanceof ApproovInterceptor);
            } else {
                builder = new OkHttpClient.Builder();
            }

            // add the Approov protection to the builder
            // updateClientFactory builds a one-shot recovered client snapshot, so the
            // builder should not register as a PinChangeListener.
            ApproovClientBuilder approovBuilder = new ApproovClientBuilder(this, null, true);
            approovBuilder.apply(builder);

            // build the new client
            OkHttpClient newClient = builder.build();

            // set the new client as the default shared client
            OkHttpClientProvider.setOkHttpClientFactory(new OkHttpClientFactory() {
                @Override
                public OkHttpClient createNewNetworkModuleClient() {
                    return newClient;
                }
            });

            // we also need to use reflection to set the client on the NetworkingModule
            // since it has already grasped a reference to the previous client
            try {
                Field clientField = null;
                try {
                    // React Native 0.73+ (Kotlin NetworkingModule)
                    clientField = NetworkingModule.class.getDeclaredField("client");
                } catch (NoSuchFieldException e) {
                    // React Native < 0.73 (Java NetworkingModule)
                    clientField = NetworkingModule.class.getDeclaredField("mClient");
                }
                if (clientField != null) {
                    clientField.setAccessible(true);
                    clientField.set(networkingModule, newClient);
                }
            } catch (Exception e) {
                log(LOG_ERROR, TAG, "Failed to update NetworkingModule client via reflection: " + e.getMessage());
                // we determine this is not a fatal error as the provider update should work for
                // future requests
            }

            log(LOG_INFO, TAG, "updateClientFactory: success");
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("updateClientFactory", "Exception: " + e.getMessage(), getErrorUserInfo(false));
        }
    }

    /**
     * Performs a secure fetch bypassing any global OkHttpClient interceptors.
     * It creates an isolated OkHttpClient explicitly populated with the Approov
     * interceptor and certificate pinner, ensuring other SDKs cannot interfere.
     *
     * @param url     the requested URL
     * @param options dictionary containing headers, body, method, etc.
     * @param promise promise to be fulfilled with the response
     */
    @ReactMethod
    public void fetchWithApproov(String url, ReadableMap options, Promise promise) {
        // Run network operation in a background thread to avoid blocking JS
        new Thread(() -> {
            try {
                // 1. Build the explicit OkHttp request
                Request.Builder requestBuilder = new Request.Builder().url(url);

                String method = "GET";
                if (options != null && options.hasKey("method")) {
                    ReadableType methodType = options.getType("method");
                    if (methodType != ReadableType.Null && methodType != ReadableType.String) {
                        promise.reject("bad_request", "fetchWithApproov method must be a string when provided");
                        return;
                    }
                    if (methodType == ReadableType.String) {
                        String candidateMethod = options.getString("method");
                        if (candidateMethod != null && !candidateMethod.trim().isEmpty()) {
                            method = candidateMethod.trim();
                        }
                    }
                }

                okhttp3.RequestBody requestBody = null;
                if (options != null && options.hasKey("body")) {
                    ReadableType bodyType = options.getType("body");
                    if (bodyType != ReadableType.Null && bodyType != ReadableType.String) {
                        promise.reject("bad_request", "fetchWithApproov body must be a string when provided");
                        return;
                    }
                    if (bodyType == ReadableType.String) {
                        String bodyString = options.getString("body");
                        requestBody = okhttp3.RequestBody.create(null, bodyString);
                    }
                }

                // If the user has omitted a body, but the method is one that OkHttp requires
                // a body for (like POST/PUT/etc), we must provide an empty body instead of null
                // to avoid an IllegalArgumentException. This does NOT clear existing content
                // because it only runs if requestBody is still null.
                if (requestBody == null && (method.equalsIgnoreCase("POST") ||
                        method.equalsIgnoreCase("PUT") ||
                        method.equalsIgnoreCase("PATCH") ||
                        method.equalsIgnoreCase("PROPPATCH"))) {
                    requestBody = okhttp3.RequestBody.create(null, new byte[0]);
                }

                // Add headers if provided
                if (options != null && options.hasKey("headers")) {
                    ReadableType headersType = options.getType("headers");
                    if (headersType != ReadableType.Null && headersType != ReadableType.Map) {
                        promise.reject("bad_request", "fetchWithApproov headers must be an object when provided");
                        return;
                    }
                    if (headersType == ReadableType.Map) {
                        ReadableMap headersMap = options.getMap("headers");
                        for (Map.Entry<String, Object> entry : headersMap.toHashMap().entrySet()) {
                            Object value = entry.getValue();
                            if (value != null) {
                                if (!(value instanceof String)) {
                                    promise.reject("bad_request", "fetchWithApproov header values must be strings");
                                    return;
                                }
                                requestBuilder.addHeader(entry.getKey(), (String) value);
                            }
                        }
                    }
                }

                requestBuilder.method(method, requestBody);
                Request request = requestBuilder.build();

                // 2. Build the cleanly isolated Approov OkHttpClient
                OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
                // ephemeral builder: skip PinChangeListener to avoid leaking references on
                // every fetch
                ApproovClientBuilder approovBuilder = new ApproovClientBuilder(this, null, true);
                approovBuilder.apply(clientBuilder);
                OkHttpClient secureClient = clientBuilder.build();

                // 3. Execute request safely and always close the response to release
                // the underlying connection.
                try (Response response = secureClient.newCall(request).execute()) {
                    // 4. Format the response for React Native
                    WritableMap responseMap = safeCreateMap();
                    responseMap.putInt("status", response.code());

                    WritableMap responseHeaders = safeCreateMap();
                    for (String headerName : response.headers().names()) {
                        responseHeaders.putString(headerName, response.header(headerName));
                    }
                    responseMap.putMap("headers", responseHeaders);

                    if (response.body() != null) {
                        responseMap.putString("body", response.body().string());
                    } else {
                        responseMap.putString("body", "");
                    }

                    promise.resolve(responseMap);
                }

            } catch (Exception e) {
                log(LOG_ERROR, TAG, "fetchWithApproov failed: " + e.getMessage());
                promise.reject("network_error", e.getMessage(), e);
            }
        }).start();
    }

    /**
     * iOS-specific method to set the max reswizzle attempts.
     * This is a no-op on Android since the OkHttp integration
     * does not rely on method swizzling or +load races.
     *
     * @param attempts the maximum number of recovery attempts.
     */
    @ReactMethod
    public void setMaxReswizzleAttempts(Integer attempts) {
        log(LOG_DEBUG, TAG, "setMaxReswizzleAttempts: no-op on Android");
    }

    /**
     * iOS-specific method to get the max reswizzle attempts.
     * Always returns 0 on Android since swizzling is not used.
     *
     * @param promise promise to be fulfilled with 0
     */
    @ReactMethod
    public void getMaxReswizzleAttempts(Promise promise) {
        promise.resolve(0);
    }

    /**
     * Exposes the native Approov logging facility to Javascript.
     * 
     * @param message the string message to log natively
     * @param level   the integer log level (e.g., ApproovService.Log.INFO)
     */
    @ReactMethod
    public void logMessage(String message, Integer level) {
        int mappedLevel = (level != null) ? level : LOG_INFO;
        log(mappedLevel, TAG, "JS: " + (message != null ? message : "null"));
    }
}
