const { NativeModules } = require('../test-support/react-native');
const { ApproovService } = require('../index');

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

describe('ApproovService JS interface', () => {
  beforeEach(() => {
    setNativeService({});
    jest.clearAllMocks();
  });

  test('fetchWithApproov forwards plain URL requests and returns a WHATWG Response', async () => {
    const nativeFetch = jest.fn().mockResolvedValue({
      status: 201,
      body: '{"ok":true}',
      headers: { 'content-type': 'application/json', 'x-test': 'value' },
    });
    const nativeService = { fetchWithApproov: nativeFetch, ping: jest.fn() };
    setNativeService(nativeService);

    const response = await ApproovService.fetchWithApproov('https://example.com/path', {
      method: 'POST',
      headers: new Headers({ 'X-Api-Key': 'secret' }),
      body: 'payload',
    });

    expect(nativeFetch).toHaveBeenCalledWith('https://example.com/path', {
      method: 'POST',
      headers: { 'x-api-key': 'secret' },
      body: 'payload',
    });
    expect(response).toBeInstanceOf(Response);
    expect(response.status).toBe(201);
    expect(await response.text()).toBe('{"ok":true}');
    expect(response.headers.get('content-type')).toBe('application/json');
    expect(response.headers.get('x-test')).toBe('value');
    expect(ApproovService.ping).toBe(nativeService.ping);
  });

  test('initialize forwards the optional comment and defaults it to null', async () => {
    const initialize = jest.fn().mockResolvedValue(undefined);
    setNativeService({ initialize });

    await ApproovService.initialize('cfg', 'reinit:test');
    await ApproovService.initialize('cfg');

    expect(initialize).toHaveBeenNthCalledWith(1, 'cfg', 'reinit:test');
    expect(initialize).toHaveBeenNthCalledWith(2, 'cfg', null);
  });

  test('status methods forward to the native bridge and preserve initialized versus enabled semantics', async () => {
    const isInitialized = jest.fn()
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(true);
    const isApproovEnabled = jest.fn()
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(false);
    setNativeService({ isInitialized, isApproovEnabled });

    await expect(ApproovService.isInitialized()).resolves.toBe(false);
    await expect(ApproovService.isApproovEnabled()).resolves.toBe(false);
    await expect(ApproovService.isInitialized()).resolves.toBe(true);
    await expect(ApproovService.isApproovEnabled()).resolves.toBe(false);
  });

  test('fetchWithApproov extracts Request headers and body before crossing the native bridge', async () => {
    const nativeFetch = jest.fn().mockResolvedValue({
      status: 200,
      body: 'ok',
      headers: { 'x-native': '1' },
    });
    setNativeService({ fetchWithApproov: nativeFetch });
    const request = new Request('https://example.com/reply', {
      method: 'PUT',
      headers: { Authorization: 'Bearer old', 'X-Request': 'present' },
      body: 'request-body',
    });

    const response = await ApproovService.fetchWithApproov(request, {
      headers: { Authorization: 'Bearer new', 'X-Init': 'override' },
    });

    expect(nativeFetch).toHaveBeenCalledWith('https://example.com/reply', {
      method: 'PUT',
      headers: {
        authorization: 'Bearer new',
        'content-type': 'text/plain;charset=UTF-8',
        'x-init': 'override',
        'x-request': 'present',
      },
      body: 'request-body',
    });
    expect(response.status).toBe(200);
    expect(response.headers.get('x-native')).toBe('1');
  });

  test('fetchWithApproov preserves synthetic native retry responses as normal Responses', async () => {
    const nativeFetch = jest.fn().mockResolvedValue({
      status: 503,
      body: '',
      headers: { 'content-type': 'text/plain' },
    });
    setNativeService({ fetchWithApproov: nativeFetch });

    const response = await ApproovService.fetchWithApproov('https://example.com/reply');

    expect(nativeFetch).toHaveBeenCalledWith('https://example.com/reply', {});
    expect(response).toBeInstanceOf(Response);
    expect(response.status).toBe(503);
    expect(await response.text()).toBe('');
    expect(response.headers.get('content-type')).toBe('text/plain');
  });

  test('fetchWithApproov warns and omits the body if Request extraction fails', async () => {
    const nativeFetch = jest.fn().mockResolvedValue({
      status: 202,
      body: '',
      headers: {},
    });
    const warnSpy = jest.spyOn(console, 'warn').mockImplementation(() => {});
    setNativeService({ fetchWithApproov: nativeFetch });
    const request = new Request('https://example.com/upload', {
      method: 'POST',
      body: 'body-to-lose',
    });

    Object.defineProperty(request, 'clone', {
      configurable: true,
      value: () => ({
        text: async () => {
          throw new Error('body unavailable');
        },
      }),
    });

    await ApproovService.fetchWithApproov(request);

    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('unable to extract the Request body'),
      expect.any(Error)
    );
    expect(nativeFetch).toHaveBeenCalledWith('https://example.com/upload', {
      method: 'POST',
      headers: { 'content-type': 'text/plain;charset=UTF-8' },
    });
  });

  test('setProceedOnNetworkFail remains a deprecation no-op', () => {
    const warnSpy = jest.spyOn(console, 'warn').mockImplementation(() => {});
    setNativeService({});

    expect(() => ApproovService.setProceedOnNetworkFail()).not.toThrow();
    expect(warnSpy).toHaveBeenCalledWith(
      'ApproovService.setProceedOnNetworkFail() is deprecated and has no effect.'
    );
  });

  test('exposes stable log level constants', () => {
    setNativeService({});

    expect(ApproovService.Log).toEqual({
      EXTREME: 0,
      DEBUG: 1,
      INFO: 2,
      WARN: 3,
      ERROR: 4,
      NONE: 5,
    });
  });
});
