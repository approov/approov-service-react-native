const fs = require('fs');
const path = require('path');
const React = require('react');
const TestRenderer = require('react-test-renderer');
const { act } = TestRenderer;

// The Jest mock apps use in place of the package (jest/index.js) must match the real API, load
// without react-native or the native module, and run the provider in the same order as the real one.

const mock = require('../jest');
const real = require('../index');

// static members declared on ApproovService in index.d.ts
function declaredStatics() {
  const typings = fs.readFileSync(path.join(__dirname, '..', 'index.d.ts'), 'utf8');
  const body = typings.slice(typings.indexOf('export declare class ApproovService'));
  const classBody = body.slice(0, body.indexOf('\n}\n'));
  const methods = [...classBody.matchAll(/^\s*static (\w+)\(/gm)].map((m) => m[1]);
  const constants = [...classBody.matchAll(/^\s*static (\w+): \{/gm)].map((m) => m[1]);
  return { methods, constants };
}

describe('Jest mock', () => {
  test('provides every declared ApproovService method as a jest.fn', () => {
    const { methods } = declaredStatics();
    expect(methods.length).toBeGreaterThan(30);
    for (const name of methods) {
      expect(typeof mock.ApproovService[name]).toBe('function');
      expect(jest.isMockFunction(mock.ApproovService[name])).toBe(true);
    }
  });

  test('has the same constants as the real module', () => {
    const { constants } = declaredStatics();
    expect(constants.sort()).toEqual(['Log', 'MutatorPreset', 'ReturnDecision']);
    for (const name of constants) {
      expect(mock.ApproovService[name]).toEqual(real.ApproovService[name]);
    }
  });

  test('exports what the package exports', () => {
    expect(Object.keys(mock).filter((k) => k !== '__esModule').sort())
      .toEqual(Object.keys(real).filter((k) => k !== '__esModule').sort());
  });

  test('loads without react-native or the native module', () => {
    jest.isolateModules(() => {
      jest.doMock('react-native', () => {
        throw new Error('the mock must not load react-native');
      });
      expect(() => require('../jest')).not.toThrow();
    });
  });

  test('fetchWithApproov passes through to the global fetch', async () => {
    const response = { status: 200 };
    const originalFetch = global.fetch;
    global.fetch = jest.fn().mockResolvedValue(response);
    try {
      await expect(mock.ApproovService.fetchWithApproov('https://example.com', { method: 'POST' }))
        .resolves.toBe(response);
      expect(global.fetch).toHaveBeenCalledWith('https://example.com', { method: 'POST' });
    } finally {
      global.fetch = originalFetch;
    }
  });

  test('provider runs onInit, initialize and onInitialized before it is ready', async () => {
    const order = [];
    mock.ApproovService.initialize.mockImplementationOnce(async (config, comment) => {
      order.push(`initialize:${config}:${comment}`);
    });
    let state = null;
    function Capture() {
      state = mock.useApproov();
      return null;
    }

    await act(async () => {
      TestRenderer.create(
        React.createElement(mock.ApproovProvider, {
          config: 'config',
          onInit: () => order.push('onInit'),
          onInitialized: () => order.push('onInitialized'),
        }, React.createElement(Capture)));
    });

    expect(order).toEqual(['onInit', 'initialize:config:null', 'onInitialized']);
    expect(state).toEqual({ approovReady: true, approovError: null, approovInitCount: 1 });
  });

  test('provider reports an initialization failure', async () => {
    const failure = new Error('bad config');
    mock.ApproovService.initialize.mockImplementationOnce(() => Promise.reject(failure));
    let state = null;
    function Capture() {
      state = mock.useApproov();
      return null;
    }

    await act(async () => {
      TestRenderer.create(
        React.createElement(mock.ApproovProvider, { config: 'config' }, React.createElement(Capture)));
    });

    expect(state).toEqual({ approovReady: false, approovError: failure, approovInitCount: 0 });
  });

  test('useApproov outside a provider throws, as the real hook does', () => {
    function Bare() {
      mock.useApproov();
      return null;
    }
    expect(() => {
      act(() => {
        TestRenderer.create(React.createElement(Bare));
      });
    }).toThrow('useApproov must be used within an ApproovProvider');
  });
});
