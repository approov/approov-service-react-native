
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
                if (typeof input === 'object' && input instanceof Request) {
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

                    // Note: This simple wrapper cannot easily extract a stream or FormData body 
                    // from a Request object asynchronously in a synchronous-looking wrapper.
                    // It relies on standard string/JSON bodies if passed. 
                    if (!options.body && input._bodyText) {
                        options.body = input._bodyText;
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

import { ApproovProvider, useApproov } from './approov-provider'
import { ApproovMonitor } from './approov-monitor'

export { ApproovService, ApproovProvider, ApproovMonitor, useApproov }
