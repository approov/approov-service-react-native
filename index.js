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
const { ApproovService } = NativeModules

const defaultPinningDiagnostics = {
    totalAuthChallenges: 0,
    totalPinned: 0,
    totalBlocked: 0,
    sessionsWithPinning: 0,
    sessionsWithoutPinning: 0,
    unpinnedSessions: [],
}

// Keep JS API stable even when older native binaries are installed.
if (typeof ApproovService.getPinningDiagnostics !== 'function') {
    ApproovService.getPinningDiagnostics = () => Promise.resolve(defaultPinningDiagnostics)
}

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
