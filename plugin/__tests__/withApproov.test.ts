import fs from 'fs';
import os from 'os';
import path from 'path';

import withApproov, {
  ACCOUNT_ID_ENV,
  assertAccountId,
  DEFAULT_IOS_SDK_VERSION,
  DEFAULT_IOS_VERSION,
  IOS_POD_NAME,
  ACCOUNT_ID_META_DATA,
  INIT_COMMENT_META_DATA,
  INFO_PLIST_ACCOUNT_ID,
  INFO_PLIST_INIT_COMMENT,
  modifyAndroidManifest,
  modifyAppBuildGradle,
  modifyAppDelegate,
  modifyInfoPlist,
  modifyMainApplication,
  modifyPodfile,
  modifyProjectBuildGradle,
  modifySettingsGradle,
  resolveProps,
  ResolvedProps,
} from '../src/withApproov';

// Not a real account ID: tests never carry one.
const TEST_ID = 'test-account-id-not-real';
const fixture = (name: string) => fs.readFileSync(path.join(__dirname, 'fixtures', name), 'utf8');
const sdk55 = (name: string) => fixture(path.join('sdk55', name));
const sdk58 = (name: string) => fixture(path.join('sdk58', name));
const sdk52 = (name: string) => fixture(path.join('sdk52', name));

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'approov-plugin-'));
const projectRoot = path.join(tmp, 'app');
const androidDir = path.join(projectRoot, 'android');
const gradlePluginDir = path.join(tmp, 'approov-service-android', 'approov-gradle-plugin');
const testRepoDir = path.join(tmp, 'approov-service-android', 'approov-service', 'build', 'test-maven-repository');
const iosDir = path.join(projectRoot, 'ios');
const iosPodDir = path.join(tmp, 'approov-service-ios');
fs.mkdirSync(androidDir, { recursive: true });
fs.mkdirSync(gradlePluginDir, { recursive: true });
fs.writeFileSync(path.join(gradlePluginDir, 'settings.gradle'), "rootProject.name = 'approov-gradle-plugin'\n");
fs.mkdirSync(testRepoDir, { recursive: true });
fs.mkdirSync(iosDir, { recursive: true });
fs.mkdirSync(iosPodDir, { recursive: true });
fs.writeFileSync(path.join(iosPodDir, 'approov-service-ios.podspec'), '# test podspec\n');
afterAll(() => fs.rmSync(tmp, { recursive: true, force: true }));

const resolve = (props: Record<string, unknown> = {}, env: Record<string, string> = {}): ResolvedProps =>
  resolveProps({ accountId: TEST_ID, ...props }, env, projectRoot);

const syntheticConfig = (size: number) => {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
  let out = '';
  for (let i = 0; i < size; i++) out += alphabet[(i * 7 + 3) % alphabet.length];
  return out;
};

const twice = <T>(fn: (input: T) => T, input: T) => fn(fn(input));
const SDKS = [52, 53, 54, 55, 56, 57, 58];
/** The template file of an Expo SDK: its own fixture when it differs from SDK 55's, else SDK 55's. */
const template = (sdk: number, name: string) => {
  const own = path.join(__dirname, 'fixtures', `sdk${sdk}`, name);
  return fs.existsSync(own) ? fs.readFileSync(own, 'utf8') : sdk55(name);
};

/** Runs one registered mod of the plugin the way expo prebuild does, on the given modResults. */
const runMod = async (props: Record<string, unknown>, platform: 'android' | 'ios', mod: string, modResults: unknown) => {
  const config: any = withApproov({ name: 'app', slug: 'app', _internal: { projectRoot } } as any, props as any);
  const platformProjectRoot = platform === 'android' ? androidDir : iosDir;
  const result = await config.mods[platform][mod]({
    ...config,
    modResults,
    modRequest: { projectRoot, platformProjectRoot, platform, modName: mod, introspect: false },
  });
  return result.modResults;
};
const withoutEnvAccountId = async (fn: () => Promise<void>) => {
  const saved = process.env[ACCOUNT_ID_ENV];
  delete process.env[ACCOUNT_ID_ENV];
  try {
    await fn();
  } finally {
    if (saved !== undefined) process.env[ACCOUNT_ID_ENV] = saved;
  }
};
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
      ios: { version: DEFAULT_IOS_VERSION, sdkVersion: DEFAULT_IOS_SDK_VERSION, podPath: undefined },
    });
    expect(DEFAULT_IOS_VERSION).toBe('3.8.0');
    expect(DEFAULT_IOS_SDK_VERSION).toBe('3.5.3');
    expect(IOS_POD_NAME).toBe('approov-service-ios');
  });

  it('accepts a full SDK config string as long as an account ID, unchanged', () => {
    // synthetic: base64-alphabet ASCII of the size of an `approov sdk -getConfig` string, not a real config
    const long = syntheticConfig(12 * 1024);
    expect(resolve({ accountId: long }).accountId).toBe(long);
    expect(resolveProps({}, { [ACCOUNT_ID_ENV]: long }, projectRoot).accountId).toBe(long);
  });

  it('resolves the iOS pod tags or a local pod path', () => {
    expect(resolve({ ios: { version: '1.2.3' } }).ios).toEqual({
      version: '1.2.3',
      sdkVersion: DEFAULT_IOS_SDK_VERSION,
      podPath: undefined,
    });
    expect(resolve({ ios: { sdkVersion: '3.8.1' } }).ios.sdkVersion).toBe('3.8.1');
    const r = resolve({ ios: { podPath: path.relative(projectRoot, iosPodDir), sdkVersion: '3.8.1' } });
    expect(r.ios).toEqual({ version: DEFAULT_IOS_VERSION, sdkVersion: '3.8.1', podPath: iosPodDir });
  });

  it('rejects malformed iOS options', () => {
    expect(() => resolve({ ios: 'x' })).toThrow(/"ios" must be an object/);
    expect(() => resolve({ ios: { version: '1 0' } })).toThrow(/ios.version/);
    expect(() => resolve({ ios: { version: '1 0' } })).toThrow(/such as 3\.8\.0/);
    expect(() => resolve({ ios: { sdkVersion: '1 0' } })).toThrow(/ios.sdkVersion/);
    expect(() => resolve({ ios: { sdkVersion: 3 } })).toThrow(/ios.sdkVersion/);
    expect(() => resolve({ ios: { podPath: './nowhere' } })).toThrow(/ios.podPath/);
    expect(() => resolve({ ios: { podPath: path.relative(projectRoot, gradlePluginDir) } })).toThrow(
      /approov-service-ios.podspec/,
    );
    expect(() => resolve({ ios: { version: '3.8.0', podPath: iosPodDir } })).toThrow(/not both/);
    expect(() => resolve({ ios: { podspec: 'x' } })).toThrow(/unknown option "ios.podspec"/);
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

  it('trims the account ID, so a trailing newline from an env file does not reach the app', () => {
    expect(resolveProps({}, { [ACCOUNT_ID_ENV]: ' from-env\n' }, projectRoot).accountId).toBe('from-env');
    expect(resolve({ accountId: '  from-option ' }).accountId).toBe('from-option');
  });

  it('treats only http and https entries as repository URLs', () => {
    expect(resolve({ android: { repositories: ['https://repo.example.com/maven'] } }).android.repositories).toEqual([
      { kind: 'url', url: 'https://repo.example.com/maven' },
    ]);
    // any other scheme is a path, which must exist
    expect(() => resolve({ android: { repositories: ['git://repo.example.com/maven'] } })).toThrow(/does not exist/);
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

  it('limits a URL repository to io.approov', () => {
    const r = resolve({ android: { repositories: ['https://repo.example.com/maven'] } });
    const out = modifyProjectBuildGradle(sdk55('build.gradle'), r, androidDir);
    expect(count(out, "url = uri('https://repo.example.com/maven')")).toBe(2);
    expect(count(out, 'includeGroup("io.approov")')).toBe(2);
  });

  it('escapes a quote in a local repository path, so it stays one Groovy string', () => {
    const quoted = path.join(tmp, "o'brien-repo");
    fs.mkdirSync(quoted, { recursive: true });
    const out = modifyProjectBuildGradle(sdk55('build.gradle'), resolve({ android: { repositories: [quoted] } }), androidDir);
    expect(out).toContain("url = uri(new File(rootDir, '../../o\\'brien-repo'))");
  });

  it('is idempotent', () => {
    const r = resolve({ android: { repositories: ['mavenLocal', 'mavenCentral'] } });
    const once = modifyProjectBuildGradle(sdk55('build.gradle'), r, androidDir);
    expect(modifyProjectBuildGradle(once, r, androidDir)).toBe(once);
  });

  it('fails clearly on a template it does not recognise', () => {
    expect(() => modifyProjectBuildGradle('apply plugin: "x"\n', resolve(), androidDir)).toThrow(/buildscript/);
  });

  it.each(SDKS)('edits the Gradle files of the Expo SDK %i template, idempotently', (sdk) => {
    const r = resolve({ android: { repositories: ['mavenLocal', 'mavenCentral'], cronetDependencyPackages: [] } });
    const settings = twice((s: string) => modifySettingsGradle(s, r, androidDir), template(sdk, 'settings.gradle'));
    expect(settings).toBe(template(sdk, 'settings.gradle'));
    const project = twice((s: string) => modifyProjectBuildGradle(s, r, androidDir), template(sdk, 'build.gradle'));
    expect(count(project, 'classpath("io.approov:service.android-gradle-plugin:3.8.0")')).toBe(1);
    expect(count(project, 'mavenLocal {')).toBe(2);
    const app = twice((s: string) => modifyAppBuildGradle(s, r), template(sdk, 'app-build.gradle'));
    expect(count(app, 'apply plugin: "io.approov.gradle"')).toBe(1);
    expect(app.indexOf('apply plugin: "io.approov.gradle"')).toBeGreaterThan(app.indexOf('apply plugin: "com.android.application"'));
    expect(count(app, 'implementation("io.approov:service.android:3.8.0")')).toBe(1);
    expect(count(app, 'cronetDependencyPackages = []')).toBe(1);
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
    // the comment from the manifest is passed, not dropped
    expect(out).toContain(`val approovComment = approovMetaData?.get("${INIT_COMMENT_META_DATA}")?.toString()`);
    expect(out).toContain('io.approov.service.android.ApproovService.initialize(this, approovAccountId, approovComment)');
    // no account ID in the manifest: logged, then bypass mode, not an uninitialized layer
    const missing = out.slice(out.indexOf('if (approovAccountId.isNullOrEmpty()) {'), out.indexOf('} else {'));
    expect(missing).toContain('android.util.Log.e("ApproovInit"');
    expect(missing).toContain('io.approov.service.android.ApproovService.initialize(this, "", null)');
    // on a rejection only the exception class is logged (an SDK message may quote the value it received)
    const caught = out.slice(out.indexOf('catch (e: Exception)'));
    expect(caught).toContain('${e.javaClass.name}');
    expect(caught).not.toMatch(/e\.message|e\.localizedMessage|\$e\b|\$\{e\}/);
    expect(caught).toContain('io.approov.service.android.ApproovService.initialize(this, "", null)');
    expect(out).toMatchSnapshot();
  });

  it('initializes Approov in Java', () => {
    const out = modifyMainApplication(fixture('MainApplication.java'), 'java', resolve());
    expect(out.indexOf('ApproovService.initialize(this, approovAccountId')).toBeGreaterThan(
      out.indexOf('super.onCreate();'),
    );
    expect(out).toContain('catch (Exception e)');
    expect(out).toContain(`approovMetaData.get("${ACCOUNT_ID_META_DATA}")`);
    expect(out).toContain(`approovMetaData.get("${INIT_COMMENT_META_DATA}")`);
    expect(out).toContain(
      'io.approov.service.android.ApproovService.initialize(this, approovAccountId, approovComment == null ? null : approovComment.toString());',
    );
    const missing = out.slice(out.indexOf('if (approovAccountId == null || approovAccountId.isEmpty()) {'), out.indexOf('} else {'));
    expect(missing).toContain('io.approov.service.android.ApproovService.initialize(this, "", null);');
    const caught = out.slice(out.indexOf('catch (Exception e)'));
    expect(caught).toContain('e.getClass().getName()');
    expect(caught).not.toMatch(/getMessage|getLocalizedMessage|\+ e\s*[);+]/);
    expect(caught).toContain('io.approov.service.android.ApproovService.initialize(this, "", null);');
    expect(out).toMatchSnapshot();
  });

  it('fails clearly on a MainApplication language it cannot edit', () => {
    expect(() => modifyMainApplication(sdk55('MainApplication.kt'), 'groovy', resolve())).toThrow(/not supported/);
  });

  it.each(SDKS)('initializes first thing in onCreate() of the Expo SDK %i template, once', (sdk) => {
    const src = template(sdk, 'MainApplication.kt');
    const out = twice((s: string) => modifyMainApplication(s, 'kt', resolve()), src);
    expect(count(out, '@generated begin approov-initialize')).toBe(1);
    const onCreate = out.slice(out.indexOf('override fun onCreate()'));
    const init = onCreate.indexOf('ApproovService.initialize(this, approovAccountId');
    expect(init).toBeGreaterThan(onCreate.indexOf('super.onCreate()'));
    for (const start of ['SoLoader.init(', 'loadReactNative(this)', 'ApplicationLifecycleDispatcher.onApplicationCreate(this)']) {
      if (onCreate.includes(start)) expect(init).toBeLessThan(onCreate.indexOf(start));
    }
    expect(modifyMainApplication(out, 'kt', resolve({ nativeInitialize: false }))).toBe(src);
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

  it('removes the meta-data written earlier when the account ID is no longer set', async () => {
    const before = modifyAndroidManifest(await parseManifest(sdk55('AndroidManifest.xml')), resolve({ comment: 'c' }));
    const r = resolveProps({ nativeInitialize: false }, {}, projectRoot);
    expect(metaData(modifyAndroidManifest(before, r))).toEqual([]);
  });

  it('writes an empty comment, which the SDK treats differently from none', async () => {
    const out = modifyAndroidManifest(await parseManifest(sdk55('AndroidManifest.xml')), resolve({ comment: '' }));
    expect(metaData(out).map((m: any) => m['android:name'])).toEqual([ACCOUNT_ID_META_DATA, INIT_COMMENT_META_DATA]);
    expect(metaData(out)[1]['android:value']).toBe('');
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

  it('writes a 12 KB config string unchanged', async () => {
    const long = syntheticConfig(12 * 1024);
    const out = modifyAndroidManifest(await parseManifest(sdk55('AndroidManifest.xml')), resolve({ accountId: long }));
    const { XML } = require('@expo/config-plugins');
    const reparsed = await parseManifest(XML.format(out));
    expect(metaData(reparsed)).toEqual([{ 'android:name': ACCOUNT_ID_META_DATA, 'android:value': long }]);
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

  it('writes an empty comment, which the SDK treats differently from none', () => {
    expect(modifyInfoPlist({}, resolve({ comment: '' }))[INFO_PLIST_INIT_COMMENT]).toBe('');
  });

  it('writes nothing without an account ID', () => {
    const r = resolveProps({ nativeInitialize: false }, {}, projectRoot);
    expect(modifyInfoPlist({ CFBundleName: 'x' }, r)).toEqual({ CFBundleName: 'x' });
  });

  it('writes a 12 KB config string unchanged', () => {
    const long = syntheticConfig(12 * 1024);
    expect(modifyInfoPlist({}, resolve({ accountId: long }))[INFO_PLIST_ACCOUNT_ID]).toBe(long);
  });
});

describe('Podfile', () => {
  const SDK_LINE = `pod 'approov-ios-sdk', :git => 'https://github.com/approov/approov-ios-sdk.git', :tag => '${DEFAULT_IOS_SDK_VERSION}'`;
  const LAYER_LINE = `pod 'approov-service-ios', :git => 'https://github.com/approov/approov-service-ios.git', :tag => '${DEFAULT_IOS_VERSION}'`;

  it('adds the SDK and the universal iOS layer from GitHub tags inside the app target, once', () => {
    const out = twice((s: string) => modifyPodfile(s, resolve(), iosDir), sdk55('Podfile'));
    expect(count(out, "pod 'approov-service-ios'")).toBe(1);
    expect(count(out, "pod 'approov-ios-sdk'")).toBe(1);
    expect(out).toContain(SDK_LINE);
    expect(out).toContain(LAYER_LINE);
    const lines = out.split('\n');
    const sdk = lines.findIndex((l) => l.includes("pod 'approov-ios-sdk'"));
    const pod = lines.findIndex((l) => l.includes("pod 'approov-service-ios'"));
    expect(sdk).toBeLessThan(pod);
    expect(sdk).toBeGreaterThan(lines.findIndex((l) => l.includes('use_expo_modules!')));
    expect(pod).toBeLessThan(lines.findIndex((l) => l.includes('use_native_modules!')));
    expect(out).toMatchSnapshot();
  });

  it('adds a local development layer by path and still the SDK from GitHub', () => {
    const out = modifyPodfile(sdk55('Podfile'), resolve({ ios: { podPath: iosPodDir } }), iosDir);
    expect(out).toContain(`pod 'approov-service-ios', :path => '${path.relative(iosDir, iosPodDir)}'`);
    expect(out).not.toContain('approov-service-ios.git');
    expect(out).toContain(SDK_LINE);
  });

  it('replaces previous entries when the options change', () => {
    const once = modifyPodfile(sdk55('Podfile'), resolve(), iosDir);
    const out = modifyPodfile(once, resolve({ ios: { version: '3.8.1', sdkVersion: '3.8.2' } }), iosDir);
    expect(count(out, "pod 'approov-service-ios'")).toBe(1);
    expect(count(out, "pod 'approov-ios-sdk'")).toBe(1);
    expect(out).toContain("approov-service-ios.git', :tag => '3.8.1'");
    expect(out).toContain("approov-ios-sdk.git', :tag => '3.8.2'");
  });

  it('adds the pod with nativeInitialize false too, the layer is needed for JavaScript initialization', () => {
    const r = resolveProps({ nativeInitialize: false }, {}, projectRoot);
    expect(modifyPodfile(sdk55('Podfile'), r, iosDir)).toContain("pod 'approov-service-ios'");
  });

  it('escapes a quote in a local pod path, so it stays one Ruby string', () => {
    const quoted = path.join(tmp, "o'brien-ios");
    fs.mkdirSync(quoted, { recursive: true });
    fs.writeFileSync(path.join(quoted, 'approov-service-ios.podspec'), '# test podspec\n');
    const out = modifyPodfile(sdk55('Podfile'), resolve({ ios: { podPath: quoted } }), iosDir);
    expect(out).toContain("pod 'approov-service-ios', :path => '../../o\\'brien-ios'");
  });

  it.each(SDKS)('adds the pods inside the app target of the Expo SDK %i template, once', (sdk) => {
    const src = template(sdk, 'Podfile');
    const out = twice((s: string) => modifyPodfile(s, resolve(), iosDir), src);
    expect(count(out, SDK_LINE)).toBe(1);
    expect(count(out, LAYER_LINE)).toBe(1);
    const lines = out.split('\n');
    const target = lines.findIndex((l) => /^\s*target\s+['"].+['"]\s+do\s*$/.test(l));
    const pod = lines.findIndex((l) => l.includes(LAYER_LINE));
    expect(pod).toBeGreaterThan(target);
    expect(lines.slice(target, pod).some((l) => /^end\s*$/.test(l))).toBe(false);
  });

  it('fails clearly on a Podfile without a target block', () => {
    expect(() => modifyPodfile("platform :ios, '15.1'\n", resolve(), iosDir)).toThrow(/no "target \.\.\. do" block/);
  });
});

describe('AppDelegate', () => {
  const initCall = 'try ApproovService.initialize(approovAccountId, comment: approovComment)';

  it('initializes Approov in the SDK 55 Swift AppDelegate before React Native starts', () => {
    const out = modifyAppDelegate(sdk55('AppDelegate.swift'), 'swift', resolve());
    const init = out.indexOf(initCall);
    expect(init).toBeGreaterThan(out.indexOf('didFinishLaunchingWithOptions launchOptions'));
    expect(init).toBeLessThan(out.indexOf('ExpoReactNativeFactory(delegate: delegate)'));
    expect(init).toBeLessThan(out.indexOf('factory.startReactNative('));
    expect(init).toBeLessThan(out.indexOf('return super.application(application, didFinishLaunchingWithOptions'));
    // imported once, after the template's own imports
    expect(count(out, 'import ApproovService')).toBe(1);
    expect(out.indexOf('import ApproovService')).toBeGreaterThan(out.indexOf('import ReactAppDependencyProvider'));
    expect(out.indexOf('import ApproovService')).toBeLessThan(out.indexOf('@main'));
    // read from Info.plist, never inlined
    expect(out).toContain(`Bundle.main.object(forInfoDictionaryKey: "${INFO_PLIST_ACCOUNT_ID}")`);
    expect(out).toContain(`Bundle.main.object(forInfoDictionaryKey: "${INFO_PLIST_INIT_COMMENT}")`);
    expect(out).not.toContain(TEST_ID);
    // guarded: only the error type is logged, then bypass mode (TESTING_REQUIREMENTS 9.3)
    expect(out).toContain('} catch {');
    expect(out).toContain('type(of: error)');
    expect(out).not.toMatch(/\\\(error\)|localizedDescription/);
    expect(out).toContain('try? ApproovService.initialize("")');
    // no account ID in Info.plist: logged, then bypass mode
    const missing = out.slice(out.indexOf('if approovAccountId.isEmpty {'), out.indexOf('} else {'));
    expect(missing).toContain('NSLog(');
    expect(missing).toContain('try ApproovService.initialize("")');
    expect(out).toContain('ApproovService.isApproovServiceEnabled()');
    expect(out).toContain('ApproovService.isApproovProtectionEnabled()');
    expect(out).toMatchSnapshot();
  });

  it('initializes Approov in the SDK 58 Swift AppDelegate (React Native started by the SceneDelegate)', () => {
    const out = modifyAppDelegate(sdk58('AppDelegate.swift'), 'swift', resolve());
    const init = out.indexOf(initCall);
    expect(init).toBeGreaterThan(out.indexOf('didFinishLaunchingWithOptions launchOptions'));
    expect(init).toBeLessThan(out.indexOf('ExpoReactNativeFactory(delegate: delegate)'));
    expect(init).toBeLessThan(out.indexOf('return super.application('));
  });

  it.each(SDKS.filter((sdk) => sdk >= 53))(
    'initializes Approov before React Native in the Expo SDK %i Swift AppDelegate, once',
    (sdk) => {
      const src = template(sdk, 'AppDelegate.swift');
      const out = twice((s: string) => modifyAppDelegate(s, 'swift', resolve()), src);
      expect(count(out, initCall)).toBe(1);
      expect(count(out, 'import ApproovService')).toBe(1);
      const init = out.indexOf(initCall);
      expect(init).toBeGreaterThan(out.indexOf('didFinishLaunchingWithOptions launchOptions'));
      for (const start of ['ExpoReactNativeFactory(delegate:', 'factory.startReactNative(', 'return super.application(application, didFinishLaunchingWithOptions']) {
        if (out.includes(start)) expect(init).toBeLessThan(out.indexOf(start));
      }
      expect(modifyAppDelegate(out, 'swift', resolve({ nativeInitialize: false }))).toBe(src);
    },
  );

  it('is idempotent', () => {
    const once = modifyAppDelegate(sdk55('AppDelegate.swift'), 'swift', resolve());
    expect(modifyAppDelegate(once, 'swift', resolve())).toBe(once);
  });

  it('is unchanged with nativeInitialize false, and removes a previous insertion', () => {
    const r = resolve({ nativeInitialize: false });
    expect(modifyAppDelegate(sdk55('AppDelegate.swift'), 'swift', r)).toBe(sdk55('AppDelegate.swift'));
    const once = modifyAppDelegate(sdk55('AppDelegate.swift'), 'swift', resolve());
    expect(modifyAppDelegate(once, 'swift', r)).toBe(sdk55('AppDelegate.swift'));
  });

  it('fails clearly on the Objective-C AppDelegate of Expo SDK 52 and earlier unless nativeInitialize is false', () => {
    expect(() => modifyAppDelegate(sdk52('AppDelegate.mm'), 'objcpp', resolve())).toThrow(/Objective-C AppDelegate/);
    expect(() => modifyAppDelegate(sdk52('AppDelegate.mm'), 'objcpp', resolve())).toThrow(/nativeInitialize/);
    expect(modifyAppDelegate(sdk52('AppDelegate.mm'), 'objcpp', resolve({ nativeInitialize: false }))).toBe(
      sdk52('AppDelegate.mm'),
    );
  });

  it('fails clearly without application(_:didFinishLaunchingWithOptions:)', () => {
    expect(() => modifyAppDelegate('import UIKit\nclass AppDelegate {}\n', 'swift', resolve())).toThrow(
      /didFinishLaunchingWithOptions/,
    );
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
    expect(Object.keys(config.mods.ios).sort()).toEqual(['appDelegate', 'infoPlist', 'podfile']);
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

  it.each([
    ['android', 'manifest', () => parseManifest(sdk55('AndroidManifest.xml'))],
    ['android', 'mainApplication', async () => ({ contents: sdk55('MainApplication.kt'), language: 'kt' })],
    ['ios', 'infoPlist', async () => ({ CFBundleName: 'app' })],
    ['ios', 'appDelegate', async () => ({ contents: sdk55('AppDelegate.swift'), language: 'swift' })],
  ] as const)('fails the %s %s mod when the account ID is missing, so one-platform prebuilds fail too', async (platform, mod, input) => {
    await withoutEnvAccountId(async () => {
      await expect(runMod({}, platform, mod, await input())).rejects.toThrow(/account ID is missing/);
      await expect(runMod({ accountId: TEST_ID }, platform, mod, await input())).resolves.toBeDefined();
      await expect(runMod({ nativeInitialize: false }, platform, mod, await input())).resolves.toBeDefined();
    });
  });

  it.each([
    ['settingsGradle', 'android/settings.gradle', () => sdk55('settings.gradle')],
    ['projectBuildGradle', 'android/build.gradle', () => sdk55('build.gradle')],
    ['appBuildGradle', 'android/app/build.gradle', () => sdk55('app-build.gradle')],
  ])('fails the %s mod on a Kotlin DSL file instead of skipping it', async (mod, file, contents) => {
    await expect(runMod({ accountId: TEST_ID }, 'android', mod, { contents: contents(), language: 'kt' })).rejects.toThrow(
      new RegExp(`${file.replace(/[/.]/g, '\\$&')} must be Groovy`),
    );
    await expect(runMod({ accountId: TEST_ID }, 'android', mod, { contents: contents(), language: 'groovy' })).resolves.toBeDefined();
  });

  it('fails clearly on a generated block whose end marker was removed', () => {
    const once = modifyMainApplication(sdk55('MainApplication.kt'), 'kt', resolve());
    const broken = once.replace(/^.*@generated end approov-initialize.*\n/m, '');
    expect(() => modifyMainApplication(broken, 'kt', resolve())).toThrow(/unterminated generated block "approov-initialize"/);
  });

  it('is exported by app.plugin.js', () => {
    expect(typeof require('../../app.plugin.js')).toBe('function');
  });
});
