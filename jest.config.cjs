module.exports = {
  clearMocks: true,
  moduleNameMapper: {
    '^react-native$': '<rootDir>/test-support/react-native.js',
  },
  setupFilesAfterEnv: ['<rootDir>/jest.setup.cjs'],
  testEnvironment: 'node',
  testMatch: ['**/__tests__/**/*.test.js'],
};
