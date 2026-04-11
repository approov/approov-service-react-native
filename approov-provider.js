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

import React, { useContext, useEffect, useState } from 'react'
import { NativeModules } from 'react-native'
const { ApproovService } = NativeModules

const ApproovContext = React.createContext()

const ApproovProvider = ({ config, comment = null, onInit, children }) => {
  const [status, setStatus] = useState({
    approovReady: false,
    approovError: null,
  })

  useEffect(() => {
    let isMounted = true

    const initializeApproov = async () => {
      try {
        // execute onInit function before initialization and support async setup
        if (onInit) await Promise.resolve(onInit())

        // initialize Approov
        await ApproovService.initialize(config, comment)
        if (!isMounted) return

        setStatus({ approovReady: true, approovError: null })
        if (ApproovService.logMessage) {
          ApproovService.logMessage("React Native: ApproovService.initialize() promise resolved successfully.", 2 /* INFO */);
        }
      } catch (error) {
        if (!isMounted) return

        // This is a runtime error so set in context so program can notify user
        // Most common cause is a missing config string.
        setStatus({ approovReady: false, approovError: error })
        if (ApproovService.logMessage) {
          const details = (error && error.message) ? error.message : String(error)
          ApproovService.logMessage("React Native: ApproovService.initialize() promise rejected: " + details, 4 /* ERROR */);
        }
      }
    }

    initializeApproov()

    return () => {
      isMounted = false
    }
  }, [])

  return (
    <ApproovContext.Provider value={status}>{children}</ApproovContext.Provider>
  )
}

const useApproov = () => {
  const context = useContext(ApproovContext)
  if (context === undefined) {
    // This is a logical program error so throw exception.
    // This should not occur at runtime in a well constricted component tree.
    throw new Error('useApproov must be used within an ApproovProvider')
  }

  return context
}

export { ApproovProvider, useApproov }
