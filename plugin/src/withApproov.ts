/**
 * Expo config plugin for the universal Approov Android layer (io.approov:service.android) and its
 * Gradle plugin io.approov.gradle, which weaves OkHttp, HttpsURLConnection, Volley and Cronet
 * (react-native-nitro-fetch included) at build time.
 *
 * Android: resolves io.approov.gradle onto the buildscript classpath (published coordinate, or a local
 * source build with gradlePluginPath), applies it right after com.android.application, adds the
 * universal library, optionally configures approov.cronetDependencyPackages, writes the account ID to
 * the manifest meta-data and inserts a guarded native ApproovService.initialize in MainApplication.
 * iOS: adds the universal iOS pod (approov-service-ios, by version or from a local path) to the Podfile,
 * writes the account ID to Info.plist and inserts a guarded native ApproovService.initialize at the start
 * of application(_:didFinishLaunchingWithOptions:) in the Swift AppDelegate (Expo SDK 53 and later).
 *
 * Every generated block is tagged, so running prebuild again replaces it rather than duplicating it.
 */
import fs from 'fs';
import path from 'path';

import {
  AndroidConfig,
  ConfigPlugin,
  createRunOncePlugin,
  InfoPlist,
  withAndroidManifest,
  withAppBuildGradle,
  withAppDelegate,
  withInfoPlist,
  withMainApplication,
  withPodfile,
  withProjectBuildGradle,
  withSettingsGradle,
} from '@expo/config-plugins';

// eslint-disable-next-line @typescript-eslint/no-var-requires
const pkg: { name: string; version: string } = require('../../package.json');

export const ACCOUNT_ID_ENV = 'APPROOV_ACCOUNT_ID';
export const ACCOUNT_ID_META_DATA = 'io.approov.ACCOUNT_ID';
export const INIT_COMMENT_META_DATA = 'io.approov.INIT_COMMENT';
export const INFO_PLIST_ACCOUNT_ID = 'ApproovAccountID';
export const INFO_PLIST_INIT_COMMENT = 'ApproovInitComment';

// One version for the library and the Gradle plugin: they are released together and the plugin fails
// the build when the library it resolves has a different version, so both coordinates derive from it.
export const DEFAULT_ANDROID_VERSION = '3.8.0';
export const SERVICE_ARTIFACT = 'io.approov:service.android';
export const GRADLE_PLUGIN_ARTIFACT = 'io.approov:service.android-gradle-plugin';
const APPROOV_GROUP = 'io.approov';

// The universal iOS layer (approov-service-ios), as a CocoaPods pod. Its version is independent of the
// Android one. Until the pod is published (or vendored into this package's pod, D2), use ios.podPath.
export const IOS_POD_NAME = 'approov-service-ios';
export const DEFAULT_IOS_VERSION = '3.8.0';
const LOG_TAG = 'ApproovInit';

export type AndroidProps = {
  /**
   * Version of io.approov:service.android and of io.approov:service.android-gradle-plugin (always the
   * same version; default 3.8.0). Use 3.8.0-local with the mavenLocal repository for a local build.
   */
  version?: string;
  /** Repositories for both artifacts: mavenCentral, mavenLocal, google, gradlePluginPortal, a URL or a path. */
  repositories?: string[];
  /** Local development: the approov-gradle-plugin source directory, used as an included build. */
  gradlePluginPath?: string;
  /** approov { cronetDependencyPackages = [...] }; left to the Gradle plugin default when unset. */
  cronetDependencyPackages?: string[];
};

export type IosProps = {
  /** Version of the approov-service-ios pod (default 3.8.0). */
  version?: string;
  /** Local development: the approov-service-ios source directory (with its podspec), used as a :path pod. */
  podPath?: string;
};

export type ApproovPluginProps = {
  /** The Approov account ID; else the APPROOV_ACCOUNT_ID environment variable at prebuild. */
  accountId?: string;
  /** Initialization comment passed to the SDK unchanged; null (default) for none. */
  comment?: string | null;
  /** Insert native initialization in MainApplication and AppDelegate (default true). */
  nativeInitialize?: boolean;
  android?: AndroidProps;
  ios?: IosProps;
};

type KeywordRepository = 'mavenCentral' | 'mavenLocal' | 'google' | 'gradlePluginPortal';
export type Repository = { kind: KeywordRepository } | { kind: 'path'; path: string } | { kind: 'url'; url: string };

export type ResolvedProps = {
  accountId?: string;
  comment: string | null;
  nativeInitialize: boolean;
  android: {
    version: string;
    serviceDependency: string;
    gradlePluginDependency: string;
    repositories: Repository[];
    gradlePluginPath?: string;
    cronetDependencyPackages?: string[];
  };
  ios: {
    version: string;
    podPath?: string;
  };
};

const KEYWORD_REPOSITORIES: KeywordRepository[] = ['mavenCentral', 'mavenLocal', 'google', 'gradlePluginPortal'];
const TOP_LEVEL_KEYS = ['accountId', 'comment', 'nativeInitialize', 'android', 'ios'];
const IOS_KEYS = ['version', 'podPath'];
const ANDROID_KEYS = [
  'version',
  'repositories',
  'gradlePluginPath',
  'cronetDependencyPackages',
];
const VERSION = /^[A-Za-z0-9][A-Za-z0-9_.+-]*$/;
const JAVA_PACKAGE = /^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$/;

function fail(message: string): never {
  throw new Error(`${pkg.name} config plugin: ${message}`);
}

function checkKeys(value: Record<string, unknown>, allowed: string[], prefix: string) {
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) fail(`unknown option "${prefix}${key}" (allowed: ${allowed.join(', ')})`);
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function resolveDirectory(option: string, value: unknown, projectRoot: string): string {
  if (typeof value !== 'string' || value.trim() === '') fail(`"${option}" must be a non-empty path`);
  const resolved = path.resolve(projectRoot, value);
  if (!fs.existsSync(resolved) || !fs.statSync(resolved).isDirectory()) {
    fail(`"${option}" ${resolved} does not exist or is not a directory`);
  }
  return resolved;
}

function failMissingAccountId(): never {
  fail(
    `the Approov account ID is missing. Set the "accountId" option or the ${ACCOUNT_ID_ENV} environment ` +
      'variable when running expo prebuild, or set "nativeInitialize": false to initialize Approov from ' +
      'JavaScript only.',
  );
}

/**
 * Prebuild-time check: native initialization needs the account ID. Called from the mods, which run only
 * when native files are generated, not when Expo merely reads the config (expo config, expo start, the
 * expo-constants build step, EAS Update), where the environment variable may legitimately be absent.
 */
export function assertAccountId(props: ResolvedProps): void {
  if (props.nativeInitialize && props.accountId === undefined) failMissingAccountId();
}

/**
 * Validates the plugin options and applies the defaults. Throws a clear error on any invalid option and,
 * unless requireAccountId is false, on a missing account ID with nativeInitialize.
 */
export function resolveProps(
  props: unknown,
  env: NodeJS.ProcessEnv,
  projectRoot: string,
  { requireAccountId = true }: { requireAccountId?: boolean } = {},
): ResolvedProps {
  const p = props === undefined || props === null ? {} : props;
  if (!isPlainObject(p)) fail('the options must be an object');
  checkKeys(p, TOP_LEVEL_KEYS, '');

  if (p.nativeInitialize !== undefined && typeof p.nativeInitialize !== 'boolean') {
    fail('"nativeInitialize" must be true or false');
  }
  const nativeInitialize = p.nativeInitialize !== false;

  if (p.accountId !== undefined && typeof p.accountId !== 'string') fail('"accountId" must be a string');
  const fromEnv = env[ACCOUNT_ID_ENV];
  const rawId = p.accountId !== undefined ? p.accountId : fromEnv;
  const accountId = typeof rawId === 'string' && rawId.trim() !== '' ? rawId.trim() : undefined;
  if (requireAccountId && accountId === undefined && nativeInitialize) failMissingAccountId();
  if (accountId !== undefined) {
    if (/[<>]/.test(accountId)) fail('"accountId" is still the placeholder: set your Approov account ID');
    if (/\s/.test(accountId)) fail('"accountId" must not contain whitespace');
  }

  if (p.comment !== undefined && p.comment !== null && typeof p.comment !== 'string') {
    fail('"comment" must be a string or null');
  }
  const comment = typeof p.comment === 'string' ? p.comment : null;

  const a = p.android === undefined ? {} : p.android;
  if (!isPlainObject(a)) fail('"android" must be an object');
  checkKeys(a, ANDROID_KEYS, 'android.');

  if (a.version !== undefined && (typeof a.version !== 'string' || !VERSION.test(a.version))) {
    fail('"android.version" must be a version string such as 3.8.0');
  }
  const version = typeof a.version === 'string' ? a.version : DEFAULT_ANDROID_VERSION;

  let repositories: Repository[] = [{ kind: 'mavenCentral' }];
  if (a.repositories !== undefined) {
    if (!Array.isArray(a.repositories) || a.repositories.length === 0) {
      fail('"android.repositories" must be a non-empty list');
    }
    repositories = a.repositories.map((entry: unknown): Repository => {
      if (typeof entry !== 'string' || entry.trim() === '') fail('"android.repositories" entries must be strings');
      if ((KEYWORD_REPOSITORIES as string[]).includes(entry)) return { kind: entry as KeywordRepository };
      if (/^https?:\/\//.test(entry)) return { kind: 'url', url: entry };
      return { kind: 'path', path: resolveDirectory('android.repositories', entry, projectRoot) };
    });
  }

  const gradlePluginPath =
    a.gradlePluginPath === undefined ? undefined : resolveDirectory('android.gradlePluginPath', a.gradlePluginPath, projectRoot);

  let cronetDependencyPackages: string[] | undefined;
  if (a.cronetDependencyPackages !== undefined) {
    const list = a.cronetDependencyPackages;
    if (!Array.isArray(list) || !list.every((x) => typeof x === 'string' && JAVA_PACKAGE.test(x))) {
      fail('"android.cronetDependencyPackages" must be a list of dotted Java package names');
    }
    cronetDependencyPackages = list as string[];
  }

  const i = p.ios === undefined ? {} : p.ios;
  if (!isPlainObject(i)) fail('"ios" must be an object');
  checkKeys(i, IOS_KEYS, 'ios.');
  if (i.version !== undefined && (typeof i.version !== 'string' || !VERSION.test(i.version))) {
    fail('"ios.version" must be a version string such as 3.8.0');
  }
  if (i.version !== undefined && i.podPath !== undefined) fail('set "ios.version" or "ios.podPath", not both');
  let podPath: string | undefined;
  if (i.podPath !== undefined) {
    podPath = resolveDirectory('ios.podPath', i.podPath, projectRoot);
    if (!fs.existsSync(path.join(podPath, `${IOS_POD_NAME}.podspec`))) {
      fail(`"ios.podPath" ${podPath} has no ${IOS_POD_NAME}.podspec`);
    }
  }

  return {
    accountId,
    comment,
    nativeInitialize,
    android: {
      version,
      serviceDependency: `${SERVICE_ARTIFACT}:${version}`,
      gradlePluginDependency: `${GRADLE_PLUGIN_ARTIFACT}:${version}`,
      repositories,
      gradlePluginPath,
      cronetDependencyPackages,
    },
    ios: {
      version: typeof i.version === 'string' ? i.version : DEFAULT_IOS_VERSION,
      podPath,
    },
  };
}

// ---------------------------------------------------------------------------------------------
// Tagged blocks: every insertion sits between begin/end markers and is removed before reinsertion.

const beginMarker = (tag: string, comment: string) => `${comment} @generated begin ${tag} - expo prebuild (DO NOT MODIFY)`;
const endMarker = (tag: string, comment: string) => `${comment} @generated end ${tag}`;

function removeBlock(src: string, tag: string, comment = '//'): string {
  const lines = src.split('\n');
  for (;;) {
    const start = lines.findIndex((l) => l.trim() === beginMarker(tag, comment));
    if (start < 0) return lines.join('\n');
    const end = lines.findIndex((l, i) => i > start && l.trim() === endMarker(tag, comment));
    if (end < 0) fail(`unterminated generated block "${tag}": remove it by hand or run prebuild --clean`);
    lines.splice(start, end - start + 1);
  }
}

function insertBlock(lines: string[], index: number, tag: string, body: string[], indent: string, comment = '//') {
  lines.splice(
    index,
    0,
    indent + beginMarker(tag, comment),
    ...body.map((l) => (l === '' ? '' : indent + l)),
    indent + endMarker(tag, comment),
  );
}

const indentOf = (line: string) => (/^\s*/.exec(line) as RegExpExecArray)[0];

/** Index of the line closing the brace block opened on line `start`. */
function blockEnd(lines: string[], start: number): number {
  let depth = 0;
  for (let i = start; i < lines.length; i++) {
    for (const ch of lines[i]) {
      if (ch === '{') depth++;
      else if (ch === '}' && --depth === 0) return i;
    }
  }
  return -1;
}

function findAfter(lines: string[], from: number, re: RegExp): number {
  for (let i = from; i < lines.length; i++) if (re.test(lines[i])) return i;
  return -1;
}

/** Groovy single-quoted string literal (no interpolation). */
const groovyString = (s: string) => `'${s.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;
const gradlePath = (from: string, to: string) => path.relative(from, to).split(path.sep).join('/');

function checkGroovy(language: string, file: string) {
  if (language !== 'groovy') fail(`${file} must be Groovy (build.gradle); ${language} is not supported yet`);
}

// ---------------------------------------------------------------------------------------------
// Android: Gradle

/** settings.gradle: includes the local io.approov.gradle source build when gradlePluginPath is set. */
export function modifySettingsGradle(src: string, props: ResolvedProps, androidDir: string): string {
  const tag = 'approov-gradle-plugin-build';
  const out = removeBlock(src, tag);
  if (!props.android.gradlePluginPath) return out;
  const lines = out.replace(/\n+$/, '').split('\n');
  insertBlock(
    lines,
    lines.length,
    tag,
    [
      '// Approov: local source build of the io.approov.gradle plugin (substitutes its classpath dependency)',
      `includeBuild(${groovyString(gradlePath(androidDir, props.android.gradlePluginPath))})`,
    ],
    '',
  );
  return lines.join('\n') + '\n';
}

function repositoryLines(repo: Repository, androidDir: string): string[] {
  const onlyApproov = ['    content {', `        includeGroup("${APPROOV_GROUP}")`, '    }'];
  switch (repo.kind) {
    case 'mavenLocal':
      return ['mavenLocal {', ...onlyApproov, '}'];
    case 'path':
      return [
        'maven {',
        `    url = uri(new File(rootDir, ${groovyString(gradlePath(androidDir, repo.path))}))`,
        ...onlyApproov,
        '}',
      ];
    case 'url':
      return ['maven {', `    url = uri(${groovyString(repo.url)})`, ...onlyApproov, '}'];
    default:
      return [`${repo.kind}()`];
  }
}

function upsertRepositories(lines: string[], ownerRe: RegExp, tag: string, props: ResolvedProps, androidDir: string) {
  const owner = findAfter(lines, 0, ownerRe);
  const repos = owner < 0 ? -1 : findAfter(lines, owner, /^\s*repositories\s*\{/);
  if (owner < 0 || repos < 0 || repos > blockEnd(lines, owner)) {
    fail(`android/build.gradle: no "repositories {" in the ${ownerRe.source.replace(/[^a-z]/g, '')} block`);
  }
  const existing = lines.slice(repos, blockEnd(lines, repos) + 1).map((l) => l.trim());
  const body = props.android.repositories
    .filter((r) => !(KEYWORD_REPOSITORIES as string[]).includes(r.kind) || r.kind === 'mavenLocal' || !existing.includes(`${r.kind}()`))
    .flatMap((r) => repositoryLines(r, androidDir));
  if (body.length) insertBlock(lines, repos + 1, tag, body, indentOf(lines[repos]) + '  ');
}

/**
 * android/build.gradle: repositories for the plugin (buildscript) and the library (allprojects), and
 * io.approov.gradle on the buildscript classpath, so the app module can apply it after AGP.
 */
export function modifyProjectBuildGradle(src: string, props: ResolvedProps, androidDir: string): string {
  let out = src;
  for (const tag of ['approov-buildscript-repositories', 'approov-classpath', 'approov-allprojects-repositories']) {
    out = removeBlock(out, tag);
  }
  const lines = out.split('\n');
  upsertRepositories(lines, /^\s*buildscript\s*\{/, 'approov-buildscript-repositories', props, androidDir);
  const buildscript = findAfter(lines, 0, /^\s*buildscript\s*\{/);
  const deps = findAfter(lines, buildscript, /^\s*dependencies\s*\{/);
  if (deps < 0 || deps > blockEnd(lines, buildscript)) fail('android/build.gradle: no buildscript dependencies block');
  insertBlock(
    lines,
    deps + 1,
    'approov-classpath',
    [`classpath("${props.android.gradlePluginDependency}")`],
    indentOf(lines[deps]) + '  ',
  );
  upsertRepositories(lines, /^\s*allprojects\s*\{/, 'approov-allprojects-repositories', props, androidDir);
  return lines.join('\n');
}

/**
 * android/app/build.gradle: applies io.approov.gradle right after com.android.application (the plugin
 * requires AGP first), adds the universal library and, when set, approov.cronetDependencyPackages.
 */
export function modifyAppBuildGradle(src: string, props: ResolvedProps): string {
  let out = src;
  for (const tag of ['approov-apply-plugin', 'approov-dependency', 'approov-cronet']) out = removeBlock(out, tag);
  const lines = out.split('\n');

  const applyLine = findAfter(lines, 0, /^\s*apply\s+plugin:\s*["']com\.android\.application["']/);
  const idLine = findAfter(lines, 0, /^\s*id\s*\(?\s*["']com\.android\.application["']\s*\)?\s*$/);
  if (applyLine >= 0) {
    insertBlock(lines, applyLine + 1, 'approov-apply-plugin', ['apply plugin: "io.approov.gradle"'], indentOf(lines[applyLine]));
  } else if (idLine >= 0) {
    insertBlock(lines, idLine + 1, 'approov-apply-plugin', ['id("io.approov.gradle")'], indentOf(lines[idLine]));
  } else {
    fail('android/app/build.gradle does not apply com.android.application; io.approov.gradle must follow it');
  }

  const deps = findAfter(lines, 0, /^dependencies\s*\{/);
  if (deps < 0) fail('android/app/build.gradle: no top-level dependencies block');
  insertBlock(lines, deps + 1, 'approov-dependency', [`implementation("${props.android.serviceDependency}")`], '    ');

  const packages = props.android.cronetDependencyPackages;
  if (packages !== undefined) {
    while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
    lines.push('');
    insertBlock(
      lines,
      lines.length,
      'approov-cronet',
      [
        'approov {',
        "    // dependency packages whose Cronet builder creation io.approov.gradle weaves; [] = the app's own classes only",
        `    cronetDependencyPackages = [${packages.map((x) => groovyString(x)).join(', ')}]`,
        '}',
      ],
      '',
    );
    lines.push('');
  }
  return lines.join('\n');
}

// ---------------------------------------------------------------------------------------------
// Android: manifest and MainApplication

/** AndroidManifest.xml: the account ID (and comment) as application meta-data, read by the native init. */
export function modifyAndroidManifest(
  manifest: AndroidConfig.Manifest.AndroidManifest,
  props: ResolvedProps,
): AndroidConfig.Manifest.AndroidManifest {
  const app = AndroidConfig.Manifest.getMainApplicationOrThrow(manifest);
  if (props.accountId !== undefined) {
    AndroidConfig.Manifest.addMetaDataItemToMainApplication(app, ACCOUNT_ID_META_DATA, props.accountId);
  } else {
    AndroidConfig.Manifest.removeMetaDataItemFromMainApplication(app, ACCOUNT_ID_META_DATA);
  }
  if (props.accountId !== undefined && props.comment !== null) {
    AndroidConfig.Manifest.addMetaDataItemToMainApplication(app, INIT_COMMENT_META_DATA, props.comment);
  } else {
    AndroidConfig.Manifest.removeMetaDataItemFromMainApplication(app, INIT_COMMENT_META_DATA);
  }
  return manifest;
}

const SVC = 'io.approov.service.android.ApproovService';
const BYPASS_NOTE = 'continuing without Approov protection';

function kotlinInit(): string[] {
  return [
    '// Approov: initialize the universal Android layer before React Native starts, with the account ID',
    `// the ${pkg.name} config plugin wrote to the manifest. A missing or rejected`,
    '// account ID is logged and the app continues in bypass mode, without Approov protection.',
    'try {',
    '  @Suppress("DEPRECATION")',
    '  val approovMetaData = packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA).metaData',
    '  @Suppress("DEPRECATION")',
    `  val approovAccountId = approovMetaData?.get("${ACCOUNT_ID_META_DATA}")?.toString()`,
    '  if (approovAccountId.isNullOrEmpty()) {',
    `    android.util.Log.e("${LOG_TAG}", "No Approov account ID in the manifest meta-data ${ACCOUNT_ID_META_DATA}: ${BYPASS_NOTE}")`,
    `    ${SVC}.initialize(this, "", null)`,
    '  } else {',
    '    @Suppress("DEPRECATION")',
    `    val approovComment = approovMetaData?.get("${INIT_COMMENT_META_DATA}")?.toString()`,
    `    ${SVC}.initialize(this, approovAccountId, approovComment)`,
    '  }',
    '} catch (e: Exception) {',
    `  android.util.Log.e("${LOG_TAG}", "Approov initialization failed (\${e.javaClass.name}): ${BYPASS_NOTE}")`,
    '  try {',
    `    ${SVC}.initialize(this, "", null)`,
    '  } catch (bypass: Exception) {',
    `    android.util.Log.e("${LOG_TAG}", "Approov bypass initialization failed (\${bypass.javaClass.name})")`,
    '  }',
    '}',
    `android.util.Log.i("${LOG_TAG}", "Approov service enabled=\${${SVC}.isApproovServiceEnabled()} " +`,
    `  "protection enabled=\${${SVC}.isApproovProtectionEnabled()}")`,
  ];
}

function javaInit(): string[] {
  return [
    '// Approov: initialize the universal Android layer before React Native starts, with the account ID',
    `// the ${pkg.name} config plugin wrote to the manifest. A missing or rejected`,
    '// account ID is logged and the app continues in bypass mode, without Approov protection.',
    'try {',
    '  android.os.Bundle approovMetaData = getPackageManager().getApplicationInfo(getPackageName(),',
    '      android.content.pm.PackageManager.GET_META_DATA).metaData;',
    `  Object approovAccountIdValue = approovMetaData == null ? null : approovMetaData.get("${ACCOUNT_ID_META_DATA}");`,
    '  String approovAccountId = approovAccountIdValue == null ? null : approovAccountIdValue.toString();',
    '  if (approovAccountId == null || approovAccountId.isEmpty()) {',
    `    android.util.Log.e("${LOG_TAG}", "No Approov account ID in the manifest meta-data ${ACCOUNT_ID_META_DATA}: ${BYPASS_NOTE}");`,
    `    ${SVC}.initialize(this, "", null);`,
    '  } else {',
    `    Object approovComment = approovMetaData.get("${INIT_COMMENT_META_DATA}");`,
    `    ${SVC}.initialize(this, approovAccountId, approovComment == null ? null : approovComment.toString());`,
    '  }',
    '} catch (Exception e) {',
    `  android.util.Log.e("${LOG_TAG}", "Approov initialization failed (" + e.getClass().getName() + "): ${BYPASS_NOTE}");`,
    '  try {',
    `    ${SVC}.initialize(this, "", null);`,
    '  } catch (Exception bypass) {',
    `    android.util.Log.e("${LOG_TAG}", "Approov bypass initialization failed (" + bypass.getClass().getName() + ")");`,
    '  }',
    '}',
    `android.util.Log.i("${LOG_TAG}", "Approov service enabled=" + ${SVC}.isApproovServiceEnabled()`,
    `    + " protection enabled=" + ${SVC}.isApproovProtectionEnabled());`,
  ];
}

/** MainApplication: guarded native initialize right after super.onCreate(), before React Native starts. */
export function modifyMainApplication(src: string, language: string, props: ResolvedProps): string {
  const tag = 'approov-initialize';
  const out = removeBlock(src, tag);
  if (!props.nativeInitialize) return out;
  if (language !== 'kt' && language !== 'java') fail(`MainApplication language "${language}" is not supported`);
  const lines = out.split('\n');
  const onCreate = findAfter(lines, 0, language === 'kt' ? /fun\s+onCreate\s*\(\s*\)/ : /void\s+onCreate\s*\(\s*\)/);
  const superCall = onCreate < 0 ? -1 : findAfter(lines, onCreate, /^\s*super\.onCreate\(\)\s*;?\s*$/);
  if (superCall < 0) fail('MainApplication: no super.onCreate() in onCreate() to insert Approov initialization after');
  insertBlock(lines, superCall + 1, tag, language === 'kt' ? kotlinInit() : javaInit(), indentOf(lines[superCall]));
  return lines.join('\n');
}

// ---------------------------------------------------------------------------------------------
// iOS

/** Info.plist: the account ID (and comment), read by the native initialization in the AppDelegate. */
export function modifyInfoPlist(plist: InfoPlist, props: ResolvedProps): InfoPlist {
  const out: InfoPlist = { ...plist };
  delete out[INFO_PLIST_ACCOUNT_ID];
  delete out[INFO_PLIST_INIT_COMMENT];
  if (props.accountId !== undefined) {
    out[INFO_PLIST_ACCOUNT_ID] = props.accountId;
    if (props.comment !== null) out[INFO_PLIST_INIT_COMMENT] = props.comment;
  }
  return out;
}


/** Ruby single-quoted string literal. */
const rubyString = (s: string) => `'${s.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;

/**
 * ios/Podfile: the universal iOS layer as a pod in the app target, from a local path (ios.podPath) or by
 * version. Added with nativeInitialize false too: JavaScript initialization needs the layer as well.
 */
export function modifyPodfile(src: string, props: ResolvedProps, iosDir: string): string {
  const tag = 'approov-pod';
  const out = removeBlock(src, tag, '#');
  const lines = out.split('\n');
  const expoModules = findAfter(lines, 0, /^\s*use_expo_modules!/);
  const target = findAfter(lines, 0, /^\s*target\s+['"].+['"]\s+do\s*$/);
  if (target < 0) fail(`ios/Podfile: no "target ... do" block to add the ${IOS_POD_NAME} pod to`);
  const after = expoModules > target ? expoModules : target;
  const source = props.ios.podPath
    ? `:path => ${rubyString(path.relative(iosDir, props.ios.podPath).split(path.sep).join('/'))}`
    : rubyString(props.ios.version);
  insertBlock(
    lines,
    after + 1,
    tag,
    ['# Approov: the universal iOS service layer (one approov-ios-sdk for every Approov pod)', `pod ${rubyString(IOS_POD_NAME)}, ${source}`],
    expoModules > target ? indentOf(lines[expoModules]) : indentOf(lines[target]) + '  ',
    '#',
  );
  return lines.join('\n');
}

function swiftInit(): string[] {
  const log = (msg: string) => `NSLog("%@", "${LOG_TAG}: ${msg}")`;
  return [
    '// Approov: initialize the universal iOS layer before React Native starts, with the account ID',
    `// the ${pkg.name} config plugin wrote to Info.plist. A missing or rejected`,
    '// account ID is logged and the app continues in bypass mode, without Approov protection.',
    'do {',
    `  let approovAccountId = Bundle.main.object(forInfoDictionaryKey: "${INFO_PLIST_ACCOUNT_ID}") as? String ?? ""`,
    '  if approovAccountId.isEmpty {',
    `    ${log(`No Approov account ID in Info.plist ${INFO_PLIST_ACCOUNT_ID}: ${BYPASS_NOTE}`)}`,
    '    try ApproovService.initialize("")',
    '  } else {',
    `    let approovComment = Bundle.main.object(forInfoDictionaryKey: "${INFO_PLIST_INIT_COMMENT}") as? String`,
    '    try ApproovService.initialize(approovAccountId, comment: approovComment)',
    '  }',
    '} catch {',
    `  ${log(`Approov initialization failed (\\(String(reflecting: type(of: error)))): ${BYPASS_NOTE}`)}`,
    '  try? ApproovService.initialize("")',
    '}',
    `NSLog("%@", "${LOG_TAG}: Approov service enabled=\\(ApproovService.isApproovServiceEnabled()) " +`,
    '  "protection enabled=\\(ApproovService.isApproovProtectionEnabled())")',
  ];
}

/**
 * AppDelegate: `import ApproovService` and a guarded native initialize as the first statements of
 * application(_:didFinishLaunchingWithOptions:), before the React Native factory is created. Swift only
 * (Expo SDK 53 and later); the Objective-C AppDelegate of SDK 52 and earlier cannot call the Swift-only
 * universal API and fails with a clear message unless nativeInitialize is false.
 */
export function modifyAppDelegate(src: string, language: string, props: ResolvedProps): string {
  const importTag = 'approov-import';
  const initTag = 'approov-initialize';
  const out = removeBlock(removeBlock(src, importTag), initTag);
  if (!props.nativeInitialize) return out;
  if (language !== 'swift') {
    fail(
      `native iOS initialization needs a Swift AppDelegate (Expo SDK 53 and later); this project has an ` +
        `Objective-C AppDelegate (${language}). Set "nativeInitialize": false and initialize Approov from ` +
        'JavaScript, or call ApproovService.initialize from Swift by hand.',
    );
  }
  const lines = out.split('\n');
  const signature = findAfter(lines, 0, /didFinishLaunchingWithOptions\s+launchOptions/);
  const bodyOpen = signature < 0 ? -1 : findAfter(lines, signature, /\)\s*->\s*Bool\s*\{\s*$/);
  if (bodyOpen < 0) fail('AppDelegate: no application(_:didFinishLaunchingWithOptions:) to insert Approov initialization in');
  const firstStatement = findAfter(lines, bodyOpen + 1, /\S/);
  const indent = firstStatement < 0 ? indentOf(lines[bodyOpen]) + '  ' : indentOf(lines[firstStatement]);
  insertBlock(lines, bodyOpen + 1, initTag, swiftInit(), indent);

  let lastImport = -1;
  for (let i = 0; i < lines.length && i < signature; i++) {
    if (/^\s*(@\w+\s+)*((public|internal|private|fileprivate|package)\s+)?import\s+\w/.test(lines[i])) lastImport = i;
  }
  insertBlock(lines, lastImport + 1, importTag, ['import ApproovService'], '');
  return lines.join('\n');
}

// ---------------------------------------------------------------------------------------------

const withApproov: ConfigPlugin<ApproovPluginProps | void> = (config, props) => {
  const projectRoot = (config as { _internal?: { projectRoot?: string } })._internal?.projectRoot ?? process.cwd();
  // the account ID is checked in the mods (assertAccountId): reading the config must not need it
  const resolved = resolveProps(props, process.env, projectRoot, { requireAccountId: false });

  config = withSettingsGradle(config, (c) => {
    checkGroovy(c.modResults.language, 'android/settings.gradle');
    c.modResults.contents = modifySettingsGradle(c.modResults.contents, resolved, c.modRequest.platformProjectRoot);
    return c;
  });
  config = withProjectBuildGradle(config, (c) => {
    checkGroovy(c.modResults.language, 'android/build.gradle');
    c.modResults.contents = modifyProjectBuildGradle(c.modResults.contents, resolved, c.modRequest.platformProjectRoot);
    return c;
  });
  config = withAppBuildGradle(config, (c) => {
    checkGroovy(c.modResults.language, 'android/app/build.gradle');
    c.modResults.contents = modifyAppBuildGradle(c.modResults.contents, resolved);
    return c;
  });
  config = withAndroidManifest(config, (c) => {
    assertAccountId(resolved);
    c.modResults = modifyAndroidManifest(c.modResults, resolved);
    return c;
  });
  config = withMainApplication(config, (c) => {
    assertAccountId(resolved);
    c.modResults.contents = modifyMainApplication(c.modResults.contents, c.modResults.language, resolved);
    return c;
  });
  config = withInfoPlist(config, (c) => {
    assertAccountId(resolved);
    c.modResults = modifyInfoPlist(c.modResults, resolved);
    return c;
  });
  config = withPodfile(config, (c) => {
    c.modResults.contents = modifyPodfile(c.modResults.contents, resolved, c.modRequest.platformProjectRoot);
    return c;
  });
  config = withAppDelegate(config, (c) => {
    assertAccountId(resolved);
    c.modResults.contents = modifyAppDelegate(c.modResults.contents, c.modResults.language, resolved);
    return c;
  });
  return config;
};

export default createRunOncePlugin(withApproov, pkg.name, pkg.version);
