
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

import { NativeModules } from 'react-native'

const NativeApproovService = NativeModules.ApproovService

function requestMayHaveBody(request) {
    if (!request || /^(GET|HEAD)$/i.test(request.method || 'GET')) {
        return false
    }
    if ('bodyUsed' in request && request.bodyUsed) {
        return true
    }
    if ('body' in request && request.body != null) {
        return true
    }
    return false
}

async function extractRequestBody(request) {
    if (!requestMayHaveBody(request)) {
        return undefined
    }

    if (typeof request.text === 'function' && typeof request.clone === 'function') {
        try {
            const requestForBody = request.clone()
            const bodyText = await requestForBody.text()
            return bodyText
        } catch (error) {
            console.warn(
                'ApproovService.fetchWithApproov(): unable to extract the Request body via Request.clone().text(). ' +
                'Pass a string body in the init argument for non-text or already-consumed Request bodies.',
                error
            )
            return undefined
        }
    }

    console.warn(
        'ApproovService.fetchWithApproov(): Request body extraction is unavailable in this environment. ' +
        'Pass a string body in the init argument.'
    )
    return undefined
}

// Use a Proxy so all native module methods are accessible regardless of
// enumerability. Spreading NativeApproovService in New Architecture loses
// non-enumerable Proxy-trapped methods (e.g. setUseApproovStatusIfNoToken).
const ApproovService = new Proxy(NativeApproovService || {}, {
    get(target, prop) {
        if (prop === 'setProceedOnNetworkFail') {
            return () => {
                // No-op for backwards compatibility. This function no longer does anything.
                console.warn('ApproovService.setProceedOnNetworkFail() is deprecated and has no effect.')
            }
        }
        if (prop === 'fetchWithApproov') {
            return async (input, init = {}) => {
                let url;
                let options = { ...init };

                // Handle if the first argument is a Request object
                if (typeof Request !== 'undefined' && typeof input === 'object' && input instanceof Request) {
                    url = input.url;
                    options.method = options.method || input.method;

                    // Extract headers from the Request object safely
                    const requestHeaders = {};
                    if (input.headers && typeof input.headers.forEach === 'function') {
                        input.headers.forEach((value, key) => {
                            requestHeaders[key] = value;
                        });
                    }

                    // Merge with any headers provided in the init object
                    const initHeaders = {};
                    if (init.headers) {
                        const h = new Headers(init.headers);
                        h.forEach((value, key) => {
                            initHeaders[key] = value;
                        });
                    }

                    options.headers = { ...requestHeaders, ...initHeaders };

                    if (options.body === undefined || options.body === null) {
                        const extractedBody = await extractRequestBody(input)
                        if (extractedBody !== undefined) {
                            options.body = extractedBody
                        }
                    }
                } else {
                    url = input;

                    // Ensure headers are a plain object for the NativeBridge
                    if (options.headers) {
                        const plainHeaders = {};
                        const h = new Headers(options.headers);
                        h.forEach((value, key) => {
                            plainHeaders[key] = value;
                        });
                        options.headers = plainHeaders;
                    }
                }

                // Call the native implementation
                const nativeResponse = await NativeApproovService.fetchWithApproov(url, options);

                // Construct a WHATWG Response object to return
                return new Response(nativeResponse.body, {
                    status: nativeResponse.status,
                    headers: new Headers(nativeResponse.headers || {})
                });
            }
        }
        if (prop === 'initialize') {
            return (config, comment = null) => target.initialize(config, comment)
        }
        if (prop === 'setServiceMutatorType') {
            // Keep the 1-arg ergonomic JS call; always pass the boolean sign flag
            // (default true) to native. A null/undefined options argument is treated
            // as no options. sign:false proceeds per the mask but forwards unsigned.
            return (mask, options = {}) => target.setServiceMutatorType(mask, (options || {}).sign !== false)
        }
        return target[prop]
    }
})

// Add log levels
ApproovService.Log = {
    EXTREME: 0,
    DEBUG: 1,
    INFO: 2,
    WARN: 3,
    ERROR: 4,
    NONE: 5
}

// Maskable FAILURE statuses. Bit values MUST match PolicyMutator.BIT_* (Android) and the iOS
// equivalents — do not change. SUCCESS/UNKNOWN_URL/UNPROTECTED_URL always proceed and are
// intentionally NOT exposed as bits.
ApproovService.ReturnDecision = {
  NO_APPROOV_SERVICE:     1 << 0,
  BAD_URL:                1 << 1,
  MITM_DETECTED:          1 << 2,
  NO_NETWORK:             1 << 3,
  POOR_NETWORK:           1 << 4,
  REJECTED:               1 << 5,
  UNKNOWN_KEY:            1 << 6,
  INTERNAL_ERROR:         1 << 7,
  NO_NETWORK_PERMISSION:  1 << 8,
  MISSING_LIB_DEPENDENCY: 1 << 9,
  DISABLED:               1 << 10,
}
const _RD = ApproovService.ReturnDecision
ApproovService.MutatorPreset = {
  DEFAULT: -1,                                              // restore the built-in signing default
  ALWAYS_PROCEED: Object.values(_RD).reduce((a, b) => a | b, 0),   // = 2047
  PROCEED_IF_UNAVAILABLE: _RD.NO_APPROOV_SERVICE,           // UNKNOWN_URL/UNPROTECTED_URL already always proceed
  PROCEED_DEV_CLEARTEXT: _RD.BAD_URL,                       // forward non-https (Metro dev) traffic
}

import { ApproovProvider, useApproov } from './approov-provider'
import { ApproovMonitor } from './approov-monitor'

export { ApproovService, ApproovProvider, ApproovMonitor, useApproov }
