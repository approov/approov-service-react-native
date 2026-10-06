/**
 * Expo config plugin for the universal Approov Android layer (approov-service-android) and its
 * Gradle plugin io.approov.gradle, which weaves OkHttp, HttpsURLConnection, Volley and Cronet
 * (react-native-nitro-fetch included) at build time.
 *
 * Android: resolves io.approov.gradle onto the buildscript classpath (published coordinate, or a local
 * source build with gradlePluginPath), applies it right after com.android.application, adds the
 * universal library, optionally configures approov.cronetDependencyPackages, writes the account ID to
 * the manifest meta-data and inserts a guarded native ApproovService.initialize in MainApplication.
 * iOS: writes the account ID to Info.plist only (native iOS initialization is not inserted yet).
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
  withInfoPlist,
  withMainApplication,
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

export const DEFAULT_SERVICE_DEPENDENCY = 'io.approov:service.android:3.8.0';
export const DEFAULT_GRADLE_PLUGIN_DEPENDENCY = 'io.approov:approov-gradle-plugin:3.8.0';
const APPROOV_GROUP = 'io.approov';
const LOG_TAG = 'ApproovInit';

export type AndroidProps = {
  /** group:artifact:version of the universal Android library. */
  serviceDependency?: string;
  /** group:artifact:version of the io.approov.gradle plugin artifact (buildscript classpath). */
  gradlePluginDependency?: string;
  /** Repositories for both artifacts: mavenCentral, mavenLocal, google, gradlePluginPortal, a URL or a path. */
  repositories?: string[];
  /** Local development: the approov-gradle-plugin source directory, used as an included build. */
  gradlePluginPath?: string;
  /** approov { cronetDependencyPackages = [...] }; left to the Gradle plugin default when unset. */
  cronetDependencyPackages?: string[];
};

export type ApproovPluginProps = {
  /** The Approov account ID; else the APPROOV_ACCOUNT_ID environment variable at prebuild. */
  accountId?: string;
  /** Initialization comment passed to the SDK unchanged; null (default) for none. */
  comment?: string | null;
  /** Insert native initialization in MainApplication (default true). */
  nativeInitialize?: boolean;
  android?: AndroidProps;
};

type KeywordRepository = 'mavenCentral' | 'mavenLocal' | 'google' | 'gradlePluginPortal';
export type Repository = { kind: KeywordRepository } | { kind: 'path'; path: string } | { kind: 'url'; url: string };

export type ResolvedProps = {
  accountId?: string;
  comment: string | null;
  nativeInitialize: boolean;
  android: {
    serviceDependency: string;
    gradlePluginDependency: string;
    repositories: Repository[];
    gradlePluginPath?: string;
    cronetDependencyPackages?: string[];
  };
};

const KEYWORD_REPOSITORIES: KeywordRepository[] = ['mavenCentral', 'mavenLocal', 'google', 'gradlePluginPortal'];
const TOP_LEVEL_KEYS = ['accountId', 'comment', 'nativeInitialize', 'android'];
const ANDROID_KEYS = [
  'serviceDependency',
  'gradlePluginDependency',
  'repositories',
  'gradlePluginPath',
  'cronetDependencyPackages',
];
const COORDINATE = /^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+$/;
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

/** Validates the plugin options and applies the defaults. Throws a clear error on any invalid option. */
export function resolveProps(props: unknown, env: NodeJS.ProcessEnv, projectRoot: string): ResolvedProps {
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
  if (accountId === undefined && nativeInitialize) {
    fail(
      `the Approov account ID is missing. Set the "accountId" option or the ${ACCOUNT_ID_ENV} environment ` +
        'variable when running expo prebuild, or set "nativeInitialize": false to initialize Approov from ' +
        'JavaScript only.',
    );
  }
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

  const coordinate = (option: string, value: unknown, fallback: string): string => {
    if (value === undefined) return fallback;
    if (typeof value !== 'string' || !COORDINATE.test(value)) {
      fail(`"android.${option}" must be a Maven coordinate group:artifact:version`);
    }
    return value;
  };

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

  return {
    accountId,
    comment,
    nativeInitialize,
    android: {
      serviceDependency: coordinate('serviceDependency', a.serviceDependency, DEFAULT_SERVICE_DEPENDENCY),
      gradlePluginDependency: coordinate(
        'gradlePluginDependency',
        a.gradlePluginDependency,
        DEFAULT_GRADLE_PLUGIN_DEPENDENCY,
      ),
      repositories,
      gradlePluginPath,
      cronetDependencyPackages,
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

/** Info.plist: the account ID (and comment). Native iOS initialization is not inserted yet. */
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

// ---------------------------------------------------------------------------------------------

const withApproov: ConfigPlugin<ApproovPluginProps | void> = (config, props) => {
  const projectRoot = (config as { _internal?: { projectRoot?: string } })._internal?.projectRoot ?? process.cwd();
  const resolved = resolveProps(props, process.env, projectRoot);

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
    c.modResults = modifyAndroidManifest(c.modResults, resolved);
    return c;
  });
  config = withMainApplication(config, (c) => {
    c.modResults.contents = modifyMainApplication(c.modResults.contents, c.modResults.language, resolved);
    return c;
  });
  config = withInfoPlist(config, (c) => {
    c.modResults = modifyInfoPlist(c.modResults, resolved);
    return c;
  });
  return config;
};

export default createRunOncePlugin(withApproov, pkg.name, pkg.version);
