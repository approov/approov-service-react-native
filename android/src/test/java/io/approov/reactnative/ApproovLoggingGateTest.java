package io.approov.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Every native log line must go through the single level-gated sink on each platform, so
 * ApproovService.setLogLevel controls all of them identically: ApproovService.log(...) on
 * Android and the ApproovLog* functions in ApproovUtils.m on iOS (the Swift bridge mirrors
 * the level and uses its own gated os_log helper). A raw android.util.Log, NSLog or os_log
 * call elsewhere would ignore setLogLevel(NONE) and appear at a level the JS caller never
 * chose. This test reads the sources, so it fails as soon as such a call is added.
 */
public class ApproovLoggingGateTest {
    // Gradle runs unit tests from the android module directory.
    private static final Path ANDROID_SOURCES = Paths.get("src/main/java");
    private static final Path IOS_SOURCES = Paths.get("../ios");

    private static final Pattern RAW_ANDROID_LOG = Pattern.compile(
            "\\bLog\\.(v|d|i|w|e|wtf|println)\\(|import\\s+static\\s+android\\.util\\.Log\\."
                    + "|\\bjava\\.util\\.logging\\b|\\bLogger\\.getLogger\\(|\\bTimber\\.");
    private static final Pattern RAW_STD_STREAM = Pattern.compile("System\\.(out|err)\\.print|printStackTrace\\(");
    private static final Pattern RAW_IOS_LOG = Pattern.compile(
            "\\bNSLogv?\\s*\\(|\\bos_log\\w*\\s*\\(|\\b(print|debugPrint|dump|printf|fprintf)\\s*\\("
                    + "|\\bLogger\\s*\\(");

    private static List<Path> sources(Path root, String... extensions) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> Stream.of(extensions).anyMatch(e -> p.toString().endsWith(e)))
                    .collect(Collectors.toList());
        }
    }

    // Drops comments and the body of the named method, found by brace matching.
    private static String codeWithout(String source, String methodSignature) {
        String code = source;
        if (methodSignature != null) {
            int start = code.indexOf(methodSignature);
            assertTrue("sink not found: " + methodSignature, start >= 0);
            int depth = 0;
            int i = code.indexOf('{', start);
            for (; i < code.length(); i++) {
                if (code.charAt(i) == '{') depth++;
                if (code.charAt(i) == '}' && --depth == 0) break;
            }
            code = code.substring(0, start) + code.substring(i + 1);
        }
        // Drop comments by line: a line starting with // and every line of a block comment that
        // starts a line. A line starting with * outside a comment (such as *error = ...) is code.
        List<String> kept = new ArrayList<>();
        boolean inBlock = false;
        for (String line : code.split("\n")) {
            String t = line.trim();
            if (inBlock) {
                if (t.contains("*/"))
                    inBlock = false;
                continue;
            }
            if (t.startsWith("/*")) {
                inBlock = !t.contains("*/");
                continue;
            }
            if (!t.startsWith("//"))
                kept.add(line);
        }
        return String.join("\n", kept);
    }

    private static List<String> matches(Path file, String code, Pattern pattern) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(code);
        while (m.find()) {
            int lineStart = code.lastIndexOf('\n', m.start()) + 1;
            int lineEnd = code.indexOf('\n', m.start());
            found.add(file + ": " + code.substring(lineStart, lineEnd < 0 ? code.length() : lineEnd).trim());
        }
        return found;
    }

    @Test
    public void androidLogsOnlyThroughTheGatedSink() throws IOException {
        List<String> raw = new ArrayList<>();
        for (Path file : sources(ANDROID_SOURCES, ".java")) {
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String sink = file.endsWith("ApproovService.java")
                    ? "static void log(int level, String tag, String msg, Throwable tr)" : null;
            String code = codeWithout(source, sink);
            raw.addAll(matches(file, code, RAW_ANDROID_LOG));
            raw.addAll(matches(file, code, RAW_STD_STREAM));
        }
        assertEquals("raw logging bypasses setLogLevel: " + raw, 0, raw.size());
    }

    @Test
    public void iosLogsOnlyThroughTheGatedSinks() throws IOException {
        List<String> raw = new ArrayList<>();
        for (Path file : sources(IOS_SOURCES, ".h", ".m", ".mm", ".swift")) {
            String name = file.getFileName().toString();
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String sink = null;
            if (name.equals("ApproovUtils.m"))
                sink = "static void ApproovLogWithType(";
            else if (name.equals("ApproovServiceMutatorBridge.swift"))
                sink = "static func logError(";
            raw.addAll(matches(file, codeWithout(source, sink), RAW_IOS_LOG));
        }
        assertEquals("raw logging bypasses setLogLevel: " + raw, 0, raw.size());
    }
}
