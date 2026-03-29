globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const originalConsoleError = console.error.bind(console);

beforeAll(() => {
  jest.spyOn(console, 'error').mockImplementation((...args) => {
    const first = args[0];
    if (typeof first === 'string') {
      if (first.includes('react-test-renderer is deprecated')) {
        return;
      }
      if (first.includes('The current testing environment is not configured to support act')) {
        return;
      }
      if (first.includes('An update to Root inside a test was not wrapped in act')) {
        return;
      }
    }
    originalConsoleError(...args);
  });
});

afterAll(() => {
  console.error.mockRestore();
});
