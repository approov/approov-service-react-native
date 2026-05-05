export declare class ApproovService {
  /**
   * Initializes Approov for the current app session.
   *
   * The optional `comment` parameter is an advanced SDK option. Most apps
   * should omit it. It can be used to pass advanced native SDK comments,
   * such as `reinit...` for supported runtime reinitialization flows or
   * `options:...` for initialization-time options on the initial setup call.
  */
  static initialize(config: string, comment?: string | null): Promise<void>;
  /**
   * Returns true once the React Native Approov service layer has been initialized.
   *
   * This can be true even when Approov protection is disabled, such as when the
   * service is initialized with an empty config string.
   */
  static isInitialized(): Promise<boolean>;
  /**
   * Returns true only when the native Approov SDK was successfully initialized
   * with a non-empty config and active request protection is enabled.
   */
  static isApproovEnabled(): Promise<boolean>;
  /**
   * Secure fetch-compatible API for sensitive requests.
   *
   * Note: this is a subset of full React Native fetch/NetworkingModule behavior.
   * Use string bodies (JSON/text). Multipart FormData uploads, Blob/ArrayBuffer
   * request bodies, AbortController cancellation, and RN progress/event hooks are
   * not supported.
   */
  static fetchWithApproov(input: string | Request, init?: RequestInit): Promise<Response>;
  /**
   * @deprecated This function is a no-op and has no effect.
   */
  static setProceedOnNetworkFail(): void;
  static setUseApproovStatusIfNoToken(shouldUse: boolean): void;
  static setSessionMetadataCollectionEnabled(enabled: boolean): void;
  static getSessionMetadataCollectionEnabled(): Promise<boolean>;
  /**
   * Sets the maximum number of times Approov should attempt to automatically
   * re-swizzle its network interception hooks on iOS if it detects they have been
   * hijacked or overwritten by another SDK at runtime.
   *
   * @param attempts the maximum number of recovery attempts (default is 3).
   */
  static setMaxReswizzleAttempts(attempts: number): void;

  /**
   * Gets the current maximum number of times Approov should attempt to
   * automatically re-swizzle its network interception hooks on iOS.
   *
   * @return a promise resolving to the configured maximum reswizzle attempts
   */
  static getMaxReswizzleAttempts(): Promise<number>;
  static setLogLevel(level: number): void;
  static logMessage(message: string, level?: number): void;
  static addAllowedDelegate(delegatePattern: string): void;
  static Log: {
    EXTREME: number;
    DEBUG: number;
    INFO: number;
    WARN: number;
    ERROR: number;
    NONE: number;
  }
  static setSuppressLoggingUnknownURL(): void;
  static setTokenHeader(header: string, prefix: string): void;
  static setTraceIDHeader(header: string): void;
  static getTraceIDHeader(): Promise<String>;
  static setBindingHeader(header: string): void;
  static addSubstitutionHeader(header: string, requiredPrefix: string): void;
  static removeSubstitutionHeader(header: string): void;
  static addSubstitutionQueryParam(key: string): void;
  static removeSubstitutionQueryParam(key: string): void;


  static addExclusionURLRegex(urlRegex: string): void;
  static removeExclusionURLRegex(urlRegex: string): void;
  static prefetch(): void;
  static precheck(): Promise<void>;
  static getDeviceID(): Promise<String>;
  static setDataHashInToken(data: string): Promise<void>;
  static setDevKey(devKey: string): Promise<void>;
  static fetchToken(url: string): Promise<String>;
  static getMessageSignature(message: string): Promise<String>;
  static fetchSecureString(key: string, newDef: string): Promise<String>;
  static fetchCustomJWT(payload: string): Promise<String>;
  static getLastARC(): Promise<String>;
  static setInstallAttrsInToken(attrs: string): Promise<void>;
  static getPinningDiagnostics(): Promise<{
    // Android specific
    isInterceptorPresent?: boolean;
    isPinnerPresent?: boolean;
    interceptors?: string[];

    // iOS specific
    totalAuthChallenges?: number;
    totalPinned?: number;
    totalBlocked?: number;

    // Joint or Platform-equivalent
    sessionsWithPinning?: number;
    sessionsWithoutPinning?: number;
    unpinnedSessions?: Array<{
      sessionPointer?: string;
      delegateClassName?: string;
      requestCount: number;
    }>;
  }>;
  static getSessionDiagnostics(): Promise<{
    enabled?: boolean;
    message?: string;
    totalSessions?: number;
    totalRequests?: number;
    registeredSessionCount?: number;
    unregisteredSessionCount?: number;
    nilDelegateSessionCount?: number;
    policySkippedSessionCount?: number;
    taskObservedWithoutSessionCreationCount?: number;
    registeredSessions?: Array<{
      sessionPointer?: string;
      delegateClassName?: string;
      delegateImagePath?: string | null;
      delegateBundleIdentifier?: string | null;
      createdAt?: string;
      requestCount?: number;
      registeredForPinning?: boolean;
      creationDisposition?: string;
      lastObservedAt?: string | null;
      lastObservedTaskType?: string | null;
      lastObservedRequestURL?: string | null;
      lastObservedRequestMethod?: string | null;
      authChallengeCount?: number;
      pinnedChallengeCount?: number;
      blockedChallengeCount?: number;
      pinningDelegateVerified?: boolean;
    }>;
    unregisteredSessions?: Array<{
      sessionPointer?: string;
      delegateClassName?: string;
      delegateImagePath?: string | null;
      delegateBundleIdentifier?: string | null;
      createdAt?: string;
      requestCount?: number;
      registeredForPinning?: boolean;
      creationDisposition?: string;
      lastObservedAt?: string | null;
      lastObservedTaskType?: string | null;
      lastObservedRequestURL?: string | null;
      lastObservedRequestMethod?: string | null;
      authChallengeCount?: number;
      pinnedChallengeCount?: number;
      blockedChallengeCount?: number;
      pinningDelegateVerified?: boolean;
    }>;
  }>;
  static updateClientFactory(wrapExisting: boolean): Promise<boolean>;
}
export interface ApproovProviderProps {
  config: string;
  comment?: string | null;
  onInit?: () => void | Promise<void>;
  children?: React.ReactNode;
}

export declare const ApproovProvider: React.FC<ApproovProviderProps>;

export declare function useApproov(): {
  approovReady: boolean;
  approovError: any;
};

export { ApproovMonitor };
