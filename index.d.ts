export declare class ApproovService {
  static initialize(config: string): Promise<void>;
  static setProceedOnNetworkFail(): void;
  static setUseApproovStatusIfNoToken(shouldUse: boolean): void;
  static setLogLevel(level: number): void;
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
    sessionsWithPinning: number;
    sessionsWithoutPinning: number;
    unpinnedSessions: Array<{ sessionId: string; requestCount: number }>;
  }>;
  static updateClientFactory(wrapExisting: boolean): Promise<boolean>;
}
import { ApproovProvider } from "./approov-provider";
import { ApproovMonitor } from "./approov-monitor";
import { useApproov } from "./approov-provider";
export { ApproovProvider, ApproovMonitor, useApproov };
