import fs from 'fs';
import os from 'os';
import path from 'path';

import withApproov, {
  ACCOUNT_ID_ENV,
  assertAccountId,
  ACCOUNT_ID_META_DATA,
  INIT_COMMENT_META_DATA,
  INFO_PLIST_ACCOUNT_ID,
  INFO_PLIST_INIT_COMMENT,
  modifyAndroidManifest,
  modifyAppBuildGradle,
  modifyInfoPlist,
  modifyMainApplication,
  modifyProjectBuildGradle,
  modifySettingsGradle,
  resolveProps,
  ResolvedProps,
} from '../src/withApproov';

// Not a real account ID: tests never carry one.
const TEST_ID = 'test-account-id-not-real';
const fixture = (name: string) => fs.readFileSync(path.join(__dirname, 'fixtures', name), 'utf8');
const sdk55 = (name: string) => fixture(path.join('sdk55', name));

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'approov-plugin-'));
const projectRoot = path.join(tmp, 'app');
const androidDir = path.join(projectRoot, 'android');
const gradlePluginDir = path.join(tmp, 'approov-service-android', 'approov-gradle-plugin');
const testRepoDir = path.join(tmp, 'approov-service-android', 'approov-service', 'build', 'test-maven-repository');
fs.mkdirSync(androidDir, { recursive: true });
fs.mkdirSync(gradlePluginDir, { recursive: true });
fs.writeFileSync(path.join(gradlePluginDir, 'settings.gradle'), "rootProject.name = 'approov-gradle-plugin'\n");
fs.mkdirSync(testRepoDir, { recursive: true });
afterAll(() => fs.rmSync(tmp, { recursive: true, force: true }));

const resolve = (props: Record<string, unknown> = {}, env: Record<string, string> = {}): ResolvedProps =>
  resolveProps({ accountId: TEST_ID, ...props }, env, projectRoot);

const twice = <T>(fn: (input: T) => T, input: T) => fn(fn(input));
const count = (haystack: string, needle: string) => haystack.split(needle).length - 1;

const parseManifest = async (xml: string) => {
  const { AndroidConfig } = require('@expo/config-plugins');
  const tmpFile = path.join(tmp, `manifest-${Math.random()}.xml`);
  fs.writeFileSync(tmpFile, xml);
  return AndroidConfig.Manifest.readAndroidManifestAsync(tmpFile);
};
const metaData = (manifest: any) =>
  (manifest.manifest.application[0]['meta-data'] || []).map((m: any) => m.$);

describe('resolveProps', () => {
  it('derives the library and the Gradle plugin from one version so they cannot drift', () => {
    const r = resolve({ android: { version: '3.9.1' } });
    expect(r.android.serviceDependency).toBe('io.approov:service.android:3.9.1');
    expect(r.android.gradlePluginDependency).toBe('io.approov:service.android-gradle-plugin:3.9.1');
  });

  it('no longer accepts separate coordinates for the library and the plugin', () => {
    expect(() => resolve({ android: { serviceDependency: 'io.approov:service.android:3.8.0' } })).toThrow(
      /unknown option "android.serviceDependency"/,
    );
    expect(() => resolve({ android: { gradlePluginDependency: 'io.approov:x:3.8.0' } })).toThrow(
      /unknown option "android.gradlePluginDependency"/,
    );
  });

  it('applies the defaults', () => {
    const r = resolve();
    expect(r).toEqual({
      accountId: TEST_ID,
      comment: null,
      nativeInitialize: true,
      android: {
        version: '3.8.0',
        serviceDependency: 'io.approov:service.android:3.8.0',
        gradlePluginDependency: 'io.approov:service.android-gradle-plugin:3.8.0',
        repositories: [{ kind: 'mavenCentral' }],
        gradlePluginPath: undefined,
        cronetDependencyPackages: undefined,
      },
    });
  });

  it('fails with a clear message when the account ID is missing', () => {
    expect(() => resolveProps({}, {}, projectRoot)).toThrow(/account ID is missing/);
    expect(() => resolveProps({}, {}, projectRoot)).toThrow(new RegExp(ACCOUNT_ID_ENV));
    expect(() => resolveProps({}, {}, projectRoot)).toThrow(/nativeInitialize/);
    expect(() => resolveProps(undefined, {}, projectRoot)).toThrow(/account ID is missing/);
  });

  it('allows a missing account ID when nativeInitialize is false', () => {
    const r = resolveProps({ nativeInitialize: false }, {}, projectRoot);
    expect(r.accountId).toBeUndefined();
    expect(r.nativeInitialize).toBe(false);
  });

  it('reads the account ID from the environment, the option winning', () => {
    expect(resolveProps({}, { [ACCOUNT_ID_ENV]: 'from-env' }, projectRoot).accountId).toBe('from-env');
    expect(resolveProps({ accountId: 'from-option' }, { [ACCOUNT_ID_ENV]: 'from-env' }, projectRoot).accountId).toBe(
      'from-option',
    );
    expect(() => resolveProps({}, { [ACCOUNT_ID_ENV]: '   ' }, projectRoot)).toThrow(/account ID is missing/);
  });

  it('rejects malformed values', () => {
    expect(() => resolve({ accountId: 'has space' })).toThrow(/accountId/);
    expect(() => resolve({ accountId: '<your-approov-account-id>' })).toThrow(/placeholder/);
    expect(() => resolve({ accountId: 42 })).toThrow(/accountId/);
    expect(() => resolve({ comment: 7 })).toThrow(/comment/);
    expect(() => resolve({ nativeInitialize: 'yes' })).toThrow(/nativeInitialize/);
    expect(() => resolve({ android: { version: 3 } })).toThrow(/android.version/);
    expect(() => resolve({ android: { version: '3.8 0' } })).toThrow(/android.version/);
    expect(() => resolve({ android: { version: '' } })).toThrow(/android.version/);
    expect(() => resolve({ android: { cronetDependencyPackages: ['com.ok', 'not a package'] } })).toThrow(
      /cronetDependencyPackages/,
    );
    expect(() => resolve({ android: { cronetDependencyPackages: 'com.ok' } })).toThrow(/cronetDependencyPackages/);
    expect(() => resolve({ android: { repositories: [] } })).toThrow(/repositories/);
    expect(() => resolve({ android: { repositories: ['./does-not-exist'] } })).toThrow(/does not exist/);
    expect(() => resolve({ android: { gradlePluginPath: './nowhere' } })).toThrow(/gradlePluginPath/);
  });

  it('rejects unknown options, so a typo is not silently ignored', () => {
    expect(() => resolve({ acountId: 'x' })).toThrow(/unknown option "acountId"/);
    expect(() => resolve({ android: { cronetPackages: [] } })).toThrow(/unknown option "android.cronetPackages"/);
    expect(() => resolve({ android: 'x' })).toThrow(/android/);
  });

  it('resolves local development sources against the project root', () => {
    const r = resolve({
      android: {
        repositories: ['mavenLocal', path.relative(projectRoot, testRepoDir), 'google', 'gradlePluginPortal'],
        gradlePluginPath: path.relative(projectRoot, gradlePluginDir),
        cronetDependencyPackages: [],
      },
    });
    expect(r.android.repositories).toEqual([
      { kind: 'mavenLocal' },
      { kind: 'path', path: testRepoDir },
      { kind: 'google' },
      { kind: 'gradlePluginPortal' },
    ]);
    expect(r.android.gradlePluginPath).toBe(gradlePluginDir);
    expect(r.android.cronetDependencyPackages).toEqual([]);
  });
});

describe('settings.gradle', () => {
  it('is unchanged for a published plugin', () => {
    expect(modifySettingsGradle(sdk55('settings.gradle'), resolve(), androidDir)).toBe(sdk55('settings.gradle'));
  });

  it('includes the local Gradle plugin build, once', () => {
    const r = resolve({ android: { gradlePluginPath: gradlePluginDir } });
    const out = twice((s: string) => modifySettingsGradle(s, r, androidDir), sdk55('settings.gradle'));
    expect(count(out, 'includeBuild(')).toBe(count(sdk55('settings.gradle'), 'includeBuild(') + 1);
    expect(out).toContain("includeBuild('../../approov-service-android/approov-gradle-plugin')");
    expect(out).toMatchSnapshot();
  });
});

describe('android/build.gradle', () => {
  it('puts the Gradle plugin on the buildscript classpath, published from Maven Central', () => {
    const out = modifyProjectBuildGradle(sdk55('build.gradle'), resolve(), androidDir);
    expect(out).toContain('classpath("io.approov:service.android-gradle-plugin:3.8.0")');
    // mavenCentral() is already in both repository blocks of the template: not added again
    expect(count(out, 'mavenCentral()')).toBe(2);
    const lines = out.split('\n');
    const classpath = lines.findIndex((l) => l.includes('io.approov:service.android-gradle-plugin'));
    const buildscriptDeps = lines.findIndex((l) => /^\s*dependencies\s*\{/.test(l));
    expect(classpath).toBeGreaterThan(buildscriptDeps);
    expect(classpath).toBeLessThan(lines.findIndex((l) => l.startsWith('allprojects')));
    expect(out).toMatchSnapshot();
  });

  it('adds mavenLocal and a path repository, limited to io.approov, to both repository blocks', () => {
    const r = resolve({ android: { repositories: ['mavenLocal', testRepoDir, 'gradlePluginPortal'] } });
    const out = modifyProjectBuildGradle(sdk55('build.gradle'), r, androidDir);
    expect(count(out, 'mavenLocal {')).toBe(2);
    expect(count(out, 'includeGroup("io.approov")')).toBe(4);
    expect(count(out, 'gradlePluginPortal()')).toBe(2);
    expect(out).toContain(`url = uri(new File(rootDir, '${path.relative(androidDir, testRepoDir)}'))`);
    expect(out.indexOf('mavenLocal {')).toBeLessThan(out.indexOf('google()'));
    expect(out).toMatchSnapshot();
  });

  it('is idempotent', () => {
    const r = resolve({ android: { repositories: ['mavenLocal', 'mavenCentral'] } });
    const once = modifyProjectBuildGradle(sdk55('build.gradle'), r, androidDir);
    expect(modifyProjectBuildGradle(once, r, androidDir)).toBe(once);
  });

  it('fails clearly on a template it does not recognise', () => {
    expect(() => modifyProjectBuildGradle('apply plugin: "x"\n', resolve(), androidDir)).toThrow(/buildscript/);
  });
});

describe('android/app/build.gradle', () => {
  it('applies io.approov.gradle right after com.android.application and adds the library', () => {
    const out = modifyAppBuildGradle(sdk55('app-build.gradle'), resolve());
    const lines = out.split('\n');
    const android = lines.findIndex((l) => l.includes('apply plugin: "com.android.application"'));
    const approov = lines.findIndex((l) => l.includes('apply plugin: "io.approov.gradle"'));
    const kotlin = lines.findIndex((l) => l.includes('apply plugin: "org.jetbrains.kotlin.android"'));
    expect(android).toBeGreaterThanOrEqual(0);
    expect(approov).toBeGreaterThan(android);
    expect(approov).toBeLessThan(kotlin);
    const deps = lines.findIndex((l) => /^dependencies\s*\{/.test(l));
    const lib = lines.findIndex((l) => l.includes('implementation("io.approov:service.android:3.8.0")'));
    expect(lib).toBeGreaterThan(deps);
    expect(out).not.toContain('approov {');
    expect(out).toMatchSnapshot();
  });

  it('writes the cronetDependencyPackages block when set, including an empty list', () => {
    const r = resolve({
      android: {
        version: '3.8.0-local',
        cronetDependencyPackages: ['com.margelo.nitro.nitrofetch', 'com.example.cronet'],
      },
    });
    const out = modifyAppBuildGradle(sdk55('app-build.gradle'), r);
    expect(out).toContain('implementation("io.approov:service.android:3.8.0-local")');
    expect(out).toContain("cronetDependencyPackages = ['com.margelo.nitro.nitrofetch', 'com.example.cronet']");
    expect(out).toMatchSnapshot();
    const empty = modifyAppBuildGradle(sdk55('app-build.gradle'), resolve({ android: { cronetDependencyPackages: [] } }));
    expect(empty).toContain('cronetDependencyPackages = []');
  });

  it('supports a plugins {} block', () => {
    const src = 'plugins {\n    id("com.android.application")\n    id("org.jetbrains.kotlin.android")\n}\n\ndependencies {\n}\n';
    const out = modifyAppBuildGradle(src, resolve());
    expect(out.indexOf('id("io.approov.gradle")')).toBeGreaterThan(out.indexOf('id("com.android.application")'));
    expect(out.indexOf('id("io.approov.gradle")')).toBeLessThan(out.indexOf('id("org.jetbrains.kotlin.android")'));
  });

  it('is idempotent', () => {
    const r = resolve({ android: { cronetDependencyPackages: ['com.margelo.nitro.nitrofetch'] } });
    const once = modifyAppBuildGradle(sdk55('app-build.gradle'), r);
    expect(modifyAppBuildGradle(once, r)).toBe(once);
    expect(count(once, 'apply plugin: "io.approov.gradle"')).toBe(1);
  });

  it('fails clearly without the Android application plugin', () => {
    expect(() => modifyAppBuildGradle('apply plugin: "com.android.library"\ndependencies {\n}\n', resolve())).toThrow(
      /com\.android\.application/,
    );
  });
});

describe('MainApplication', () => {
  it('initializes Approov in Kotlin after super.onCreate() and before React Native starts', () => {
    const out = modifyMainApplication(sdk55('MainApplication.kt'), 'kt', resolve());
    const superCall = out.indexOf('super.onCreate()');
    const init = out.indexOf('io.approov.service.android.ApproovService.initialize(this, approovAccountId');
    expect(init).toBeGreaterThan(superCall);
    expect(init).toBeLessThan(out.indexOf('loadReactNative(this)'));
    expect(out).toContain(`"${ACCOUNT_ID_META_DATA}"`);
    expect(out).toContain(`"${INIT_COMMENT_META_DATA}"`);
    // guarded: a rejected or missing account ID is logged and the app continues in bypass mode
    expect(out).toContain('catch (e: Exception)');
    expect(out).toContain('io.approov.service.android.ApproovService.initialize(this, "", null)');
    expect(out).not.toContain(TEST_ID);
    expect(out).toMatchSnapshot();
  });

  it('initializes Approov in Java', () => {
    const out = modifyMainApplication(fixture('MainApplication.java'), 'java', resolve());
    expect(out.indexOf('ApproovService.initialize(this, approovAccountId')).toBeGreaterThan(
      out.indexOf('super.onCreate();'),
    );
    expect(out).toContain('catch (Exception e)');
    expect(out).toMatchSnapshot();
  });

  it('is idempotent', () => {
    const once = modifyMainApplication(sdk55('MainApplication.kt'), 'kt', resolve());
    expect(modifyMainApplication(once, 'kt', resolve())).toBe(once);
  });

  it('is unchanged with nativeInitialize false', () => {
    const r = resolve({ nativeInitialize: false });
    expect(modifyMainApplication(sdk55('MainApplication.kt'), 'kt', r)).toBe(sdk55('MainApplication.kt'));
  });

  it('removes a previous insertion when nativeInitialize turns false', () => {
    const once = modifyMainApplication(sdk55('MainApplication.kt'), 'kt', resolve());
    expect(modifyMainApplication(once, 'kt', resolve({ nativeInitialize: false }))).toBe(sdk55('MainApplication.kt'));
  });

  it('fails clearly without super.onCreate()', () => {
    expect(() => modifyMainApplication('class MainApplication : Application()\n', 'kt', resolve())).toThrow(
      /super\.onCreate/,
    );
  });
});

describe('AndroidManifest.xml', () => {
  it('writes the account ID and the comment as application meta-data, once', async () => {
    const r = resolve({ comment: 'expo-native' });
    const out = twice((m: any) => modifyAndroidManifest(m, r), await parseManifest(sdk55('AndroidManifest.xml')));
    expect(metaData(out)).toEqual([
      { 'android:name': ACCOUNT_ID_META_DATA, 'android:value': TEST_ID },
      { 'android:name': INIT_COMMENT_META_DATA, 'android:value': 'expo-native' },
    ]);
  });

  it('writes no comment when none is set', async () => {
    const withComment = modifyAndroidManifest(
      await parseManifest(sdk55('AndroidManifest.xml')),
      resolve({ comment: 'old' }),
    );
    expect(metaData(modifyAndroidManifest(withComment, resolve()))).toEqual([
      { 'android:name': ACCOUNT_ID_META_DATA, 'android:value': TEST_ID },
    ]);
  });

  it('writes the meta-data with nativeInitialize false when an account ID is given, nothing otherwise', async () => {
    const m1 = modifyAndroidManifest(await parseManifest(sdk55('AndroidManifest.xml')), resolve({ nativeInitialize: false }));
    expect(metaData(m1)).toHaveLength(1);
    const r = resolveProps({ nativeInitialize: false }, {}, projectRoot);
    const m2 = modifyAndroidManifest(await parseManifest(sdk55('AndroidManifest.xml')), r);
    expect(metaData(m2)).toEqual([]);
  });

  it('renders as expected', async () => {
    const { XML } = require('@expo/config-plugins');
    const out = modifyAndroidManifest(await parseManifest(sdk55('AndroidManifest.xml')), resolve({ comment: 'c' }));
    expect(XML.format(out)).toMatchSnapshot();
  });
});

describe('Info.plist', () => {
  it('writes the account ID and the comment', () => {
    const out = modifyInfoPlist({ CFBundleName: 'x' }, resolve({ comment: 'expo-native' }));
    expect(out).toEqual({
      CFBundleName: 'x',
      [INFO_PLIST_ACCOUNT_ID]: TEST_ID,
      [INFO_PLIST_INIT_COMMENT]: 'expo-native',
    });
    expect(modifyInfoPlist(out, resolve())).toEqual({ CFBundleName: 'x', [INFO_PLIST_ACCOUNT_ID]: TEST_ID });
  });

  it('writes nothing without an account ID', () => {
    const r = resolveProps({ nativeInitialize: false }, {}, projectRoot);
    expect(modifyInfoPlist({ CFBundleName: 'x' }, r)).toEqual({ CFBundleName: 'x' });
  });
});

describe('withApproov', () => {
  it('registers the Android and iOS mods', () => {
    const config: any = withApproov({ name: 'app', slug: 'app', _internal: { projectRoot } } as any, {
      accountId: TEST_ID,
    });
    expect(Object.keys(config.mods.android).sort()).toEqual(
      ['appBuildGradle', 'mainApplication', 'manifest', 'projectBuildGradle', 'settingsGradle'].sort(),
    );
    expect(Object.keys(config.mods.ios)).toEqual(['infoPlist']);
  });

  it('reads the config without an account ID, so expo config and the expo-constants build step work', () => {
    const saved = process.env[ACCOUNT_ID_ENV];
    delete process.env[ACCOUNT_ID_ENV];
    try {
      expect(() => withApproov({ name: 'app', slug: 'app', _internal: { projectRoot } } as any, {})).not.toThrow();
      // invalid options still fail as soon as the config is read
      expect(() =>
        withApproov({ name: 'app', slug: 'app', _internal: { projectRoot } } as any, { nativeInitialize: 1 } as any),
      ).toThrow(/nativeInitialize/);
    } finally {
      if (saved !== undefined) process.env[ACCOUNT_ID_ENV] = saved;
    }
  });

  it('fails at prebuild (in the mods) when the account ID is missing', () => {
    const r = resolveProps({}, {}, projectRoot, { requireAccountId: false });
    expect(r.accountId).toBeUndefined();
    expect(() => assertAccountId(r)).toThrow(/account ID is missing/);
    expect(() => assertAccountId(resolve())).not.toThrow();
    expect(() => assertAccountId(resolveProps({ nativeInitialize: false }, {}, projectRoot))).not.toThrow();
  });

  it('is exported by app.plugin.js', () => {
    expect(typeof require('../../app.plugin.js')).toBe('function');
  });
});
