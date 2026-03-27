const React = require('react');
const TestRenderer = require('react-test-renderer');
const { act } = TestRenderer;
const { NativeModules } = require('../test-support/react-native');
const { ApproovProvider } = require('../approov-provider');
const { ApproovMonitor } = require('../approov-monitor');

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function setNativeService(nativeService) {
  const preservedLogLevels = NativeModules.ApproovService.Log;
  Object.keys(NativeModules.ApproovService).forEach((key) => {
    if (key !== 'Log') {
      delete NativeModules.ApproovService[key];
    }
  });
  if (preservedLogLevels) {
    NativeModules.ApproovService.Log = preservedLogLevels;
  }
  Object.assign(NativeModules.ApproovService, nativeService);
}

describe('ApproovMonitor', () => {
  beforeEach(() => {
    setNativeService({});
    jest.clearAllMocks();
  });

  test('logs startup then ready once initialization succeeds', async () => {
    const init = deferred();
    const nativeService = {
      initialize: jest.fn().mockImplementation(() => init.promise),
    };
    const logSpy = jest.spyOn(console, 'log').mockImplementation(() => {});
    setNativeService(nativeService);

    await act(async () => {
      TestRenderer.create(
        React.createElement(
          ApproovProvider,
          { config: 'cfg' },
          React.createElement(ApproovMonitor)
        )
      );
    });

    expect(logSpy).toHaveBeenCalledWith('ApproovProvider: starting');

    await act(async () => {
      init.resolve();
    });

    expect(logSpy).toHaveBeenCalledWith('ApproovProvider: ready');
  });

  test('logs the propagated initialization error', async () => {
    const nativeService = {
      initialize: jest.fn().mockRejectedValue(new Error('offline')),
    };
    const logSpy = jest.spyOn(console, 'log').mockImplementation(() => {});
    setNativeService(nativeService);

    await act(async () => {
      TestRenderer.create(
        React.createElement(
          ApproovProvider,
          { config: 'cfg' },
          React.createElement(ApproovMonitor)
        )
      );
    });

    expect(logSpy).toHaveBeenCalledWith('ApproovProvider: starting');
    expect(logSpy).toHaveBeenCalledWith('ApproovProvider: error: Error: offline');
  });
});
