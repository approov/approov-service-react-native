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

// Jest mock of @approov/approov-service-react-native, for an app's own unit tests. It is plain
// CommonJS with no JSX and no react-native import, so Jest loads it without transforming
// node_modules and without the native module. Nothing here talks to Approov: promises resolve
// with placeholder values and fetchWithApproov passes through to the global fetch, so the app's
// own fetch mocks apply. Every method is a jest.fn, so tests can assert calls or override results.
//
//   jest.mock('@approov/approov-service-react-native', () =>
//     require('@approov/approov-service-react-native/jest'))

const React = require('react')

const fn = (implementation) =>
  (typeof jest !== 'undefined' && jest.fn) ? jest.fn(implementation) : implementation

const resolved = (value) => fn(() => Promise.resolve(value))

const Log = {
  EXTREME: 0,
  DEBUG: 1,
  INFO: 2,
  WARN: 3,
  ERROR: 4,
  NONE: 5,
}

const ReturnDecision = {
  NO_APPROOV_SERVICE: 1 << 0,
  BAD_URL: 1 << 1,
  MITM_DETECTED: 1 << 2,
  NO_NETWORK: 1 << 3,
  POOR_NETWORK: 1 << 4,
  REJECTED: 1 << 5,
  UNKNOWN_KEY: 1 << 6,
  INTERNAL_ERROR: 1 << 7,
  NO_NETWORK_PERMISSION: 1 << 8,
  MISSING_LIB_DEPENDENCY: 1 << 9,
  DISABLED: 1 << 10,
}

const MutatorPreset = {
  DEFAULT: -1,
  ALWAYS_PROCEED: Object.values(ReturnDecision).reduce((a, b) => a | b, 0),
  PROCEED_IF_UNAVAILABLE: ReturnDecision.NO_APPROOV_SERVICE,
  PROCEED_DEV_CLEARTEXT: ReturnDecision.BAD_URL,
}

const ApproovService = {
  Log,
  ReturnDecision,
  MutatorPreset,

  // initialization and status
  initialize: resolved(undefined),
  isInitialized: resolved(true),
  isApproovEnabled: resolved(true),
  isInterceptorActive: resolved(true),
  precheck: resolved(undefined),
  prefetch: fn(() => {}),

  // requests: fetchWithApproov behaves as fetch, so the app's fetch mocks apply
  fetchWithApproov: fn((input, init) => fetch(input, init)),

  // configuration
  setProceedOnNetworkFail: fn(() => {}),
  setUseApproovStatusIfNoToken: fn(() => {}),
  setSessionMetadataCollectionEnabled: fn(() => {}),
  getSessionMetadataCollectionEnabled: resolved(true),
  setMaxReswizzleAttempts: fn(() => {}),
  getMaxReswizzleAttempts: resolved(0),
  setLogLevel: fn(() => {}),
  logMessage: fn(() => {}),
  addAllowedDelegate: fn(() => {}),
  setServiceMutatorType: resolved(undefined),
  setSuppressLoggingUnknownURL: fn(() => {}),
  setTokenHeader: fn(() => {}),
  setTraceIDHeader: fn(() => {}),
  getTraceIDHeader: resolved('Approov-TraceID'),
  setBindingHeader: fn(() => {}),
  addSubstitutionHeader: fn(() => {}),
  removeSubstitutionHeader: fn(() => {}),
  addSubstitutionQueryParam: fn(() => {}),
  removeSubstitutionQueryParam: fn(() => {}),
  addExclusionURLRegex: fn(() => {}),
  removeExclusionURLRegex: fn(() => {}),
  updateClientFactory: resolved(true),

  // values from the SDK
  getDeviceID: resolved('mock-device-id'),
  setDataHashInToken: resolved(undefined),
  setDevKey: resolved(undefined),
  fetchToken: resolved('mock-approov-token'),
  getMessageSignature: resolved('mock-message-signature'),
  fetchSecureString: fn((key, newDef) => Promise.resolve(newDef == null ? null : newDef)),
  fetchCustomJWT: resolved('mock-custom-jwt'),
  getLastARC: resolved(''),
  setInstallAttrsInToken: resolved(undefined),

  // diagnostics
  getPinningDiagnostics: resolved({}),
  getSessionDiagnostics: resolved({ enabled: false }),
}

const ApproovContext = React.createContext()

// Same sequence as the real provider: onInit, initialize, onInitialized, then ready (or the error).
const ApproovProvider = ({ config, comment = null, onInit, onInitialized, children }) => {
  const [status, setStatus] = React.useState({
    approovReady: false,
    approovError: null,
    approovInitCount: 0,
  })

  React.useEffect(() => {
    let isMounted = true
    const initializeApproov = async () => {
      try {
        if (onInit) await Promise.resolve(onInit())
        await ApproovService.initialize(config, comment)
        if (!isMounted) return
        if (onInitialized) await Promise.resolve(onInitialized())
        if (!isMounted) return
        setStatus((previous) => ({
          approovReady: true,
          approovError: null,
          approovInitCount: previous.approovInitCount + 1,
        }))
      } catch (error) {
        if (!isMounted) return
        setStatus((previous) => ({
          approovReady: false,
          approovError: error,
          approovInitCount: previous.approovInitCount,
        }))
      }
    }
    initializeApproov()
    return () => {
      isMounted = false
    }
  }, [])

  return React.createElement(ApproovContext.Provider, { value: status }, children)
}

const useApproov = () => {
  const context = React.useContext(ApproovContext)
  if (context === undefined) {
    throw new Error('useApproov must be used within an ApproovProvider')
  }
  return context
}

// renders nothing; the real monitor only logs provider state
const ApproovMonitor = () => null

module.exports = {
  __esModule: true,
  ApproovService,
  ApproovProvider,
  ApproovMonitor,
  useApproov,
}
