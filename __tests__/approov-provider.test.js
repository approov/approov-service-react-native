const React = require('react');
const TestRenderer = require('react-test-renderer');
const { act } = TestRenderer;
const { NativeModules } = require('../test-support/react-native');
const { ApproovProvider, useApproov } = require('../approov-provider');

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

describe('ApproovProvider', () => {
  beforeEach(() => {
    setNativeService({});
    jest.clearAllMocks();
  });

  test('initializes Approov after onInit and exposes the ready state', async () => {
    const order = [];
    const nativeService = {
      initialize: jest.fn().mockImplementation(async (config, comment) => {
        order.push(`initialize:${config}:${comment}`);
      }),
      logMessage: jest.fn(),
    };
    const onInit = jest.fn().mockImplementation(async () => {
      order.push('onInit');
    });

    setNativeService(nativeService);
    let latestState = null;
    function CaptureState() {
      latestState = useApproov();
      return null;
    }

    await act(async () => {
      TestRenderer.create(
        React.createElement(
          ApproovProvider,
          { config: 'cfg', comment: 'reinit:test', onInit },
          React.createElement(CaptureState)
        )
      );
    });

    expect(order).toEqual(['onInit', 'initialize:cfg:reinit:test']);
    expect(nativeService.initialize).toHaveBeenCalledWith('cfg', 'reinit:test');
    expect(latestState).toEqual({
      approovReady: true,
      approovError: null,
      approovInitCount: 1,
    });
    expect(nativeService.logMessage).toHaveBeenCalledWith(
      expect.stringContaining('promise resolved successfully'),
      2
    );
  });

  test('approovInitCount increments per successful initialization so effects re-run', async () => {
    // approovReady latches true on the first success, so an effect keyed only on it
    // cannot observe a later re-initialization - and every successful initialization
    // is a reset boundary that discards the service mutator. approovInitCount is the
    // signal that actually changes, so a re-mount produces a distinct value.
    const nativeService = {
      initialize: jest.fn().mockResolvedValue(undefined),
      logMessage: jest.fn(),
    };
    setNativeService(nativeService);

    // An effect keyed on [approovReady, approovInitCount] must fire for the
    // initialization, carrying a count that distinguishes it from the initial
    // pre-initialization state.
    const effectFirings = [];
    function CaptureState() {
      const { approovReady, approovInitCount } = useApproov();
      React.useEffect(() => {
        effectFirings.push({ approovReady, approovInitCount });
      }, [approovReady, approovInitCount]);
      return null;
    }

    await act(async () => {
      TestRenderer.create(
        React.createElement(
          ApproovProvider,
          { config: 'cfg' },
          React.createElement(CaptureState)
        )
      );
    });

    expect(nativeService.initialize).toHaveBeenCalledTimes(1);
    // fires once before initialization completes and once after, with the count
    // incremented - the transition an effect keyed on approovReady alone also sees,
    // but here it is the count that carries the "which initialization" information
    expect(effectFirings).toEqual([
      { approovReady: false, approovInitCount: 0 },
      { approovReady: true, approovInitCount: 1 },
    ]);
  });

  test('passes a null initialization comment by default', async () => {
    const nativeService = {
      initialize: jest.fn().mockResolvedValue(undefined),
      logMessage: jest.fn(),
    };
    setNativeService(nativeService);

    await act(async () => {
      TestRenderer.create(
        React.createElement(ApproovProvider, { config: 'cfg' }, null)
      );
    });

    expect(nativeService.initialize).toHaveBeenCalledWith('cfg', null);
  });

  test('captures initialization failures in context and logs them', async () => {
    const error = new Error('missing config');
    const nativeService = {
      initialize: jest.fn().mockRejectedValue(error),
      logMessage: jest.fn(),
    };
    setNativeService(nativeService);
    let latestState = null;

    function CaptureState() {
      latestState = useApproov();
      return null;
    }

    await act(async () => {
      TestRenderer.create(
        React.createElement(
          ApproovProvider,
          { config: 'bad-config' },
          React.createElement(CaptureState)
        )
      );
    });

    expect(latestState).toEqual({
      approovReady: false,
      approovError: error,
      approovInitCount: 0,
    });
    expect(nativeService.logMessage).toHaveBeenCalledWith(
      expect.stringContaining('promise rejected: missing config'),
      4
    );
  });

  test('does not update or log after the provider is unmounted', async () => {
    let resolveInitialize;
    const nativeService = {
      initialize: jest.fn().mockImplementation(
        () =>
          new Promise((resolve) => {
            resolveInitialize = resolve;
          })
      ),
      logMessage: jest.fn(),
    };
    setNativeService(nativeService);

    let renderer;
    await act(async () => {
      renderer = TestRenderer.create(
        React.createElement(ApproovProvider, { config: 'cfg' }, null)
      );
    });

    await act(async () => {
      renderer.unmount();
    });

    await act(async () => {
      resolveInitialize();
    });

    expect(nativeService.logMessage).not.toHaveBeenCalled();
  });

  test('useApproov throws when used outside the provider', () => {
    setNativeService({});

    function InvalidConsumer() {
      useApproov();
      return null;
    }

    expect(() => {
      act(() => {
        TestRenderer.create(React.createElement(InvalidConsumer));
      });
    }).toThrow('useApproov must be used within an ApproovProvider');
  });
});
