/*
 * Copyright (c) 2026 Approov Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.approov.reactnative;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Guards the layer's package namespace.
 *
 * Every Approov Android service layer used to compile the shared helpers as
 * {@code io.approov.util.http.sfv} and {@code io.approov.util.sig}. An app that
 * depends on two layers (for example this one plus {@code io.approov:service.okhttp})
 * then fails R8 with "Type ... is defined multiple times". The helpers are relocated
 * to {@code io.approov.internal.reactnative.util}; this test fails if any class this
 * layer ships falls outside its own namespaces, so the collision cannot come back
 * unnoticed. See approov/core-project-approov#770.
 */
public class ApproovPackageNamespaceTest {

    private static final String[] OWNED_PREFIXES = {
        "io/approov/reactnative/",
        "io/approov/internal/reactnative/"
    };

    @Test
    public void shippedClassesStayInsideLayerNamespaces() throws IOException {
        List<String> classes = shippedApproovClasses();
        assertFalse("no io/approov classes found in the layer's own code source", classes.isEmpty());

        List<String> strays = new ArrayList<>();
        for (String name : classes) {
            boolean owned = false;
            for (String prefix : OWNED_PREFIXES) {
                if (name.startsWith(prefix)) {
                    owned = true;
                    break;
                }
            }
            if (!owned) {
                strays.add(name);
            }
        }
        assertTrue("classes outside the layer namespace would collide with other Approov layers: " + strays,
            strays.isEmpty());
    }

    @Test
    public void sharedHelpersAreRelocated() {
        assertTrue(classExists("io.approov.internal.reactnative.util.http.sfv.Parser"));
        assertTrue(classExists("io.approov.internal.reactnative.util.sig.SignatureBaseBuilder"));
        assertFalse(classExists("io.approov.util.http.sfv.Parser"));
        assertFalse(classExists("io.approov.util.sig.SignatureBaseBuilder"));
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, ApproovPackageNamespaceTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Lists every io/approov class in the code source that holds this layer's main classes. */
    private static List<String> shippedApproovClasses() throws IOException {
        URL location = ApproovService.class.getProtectionDomain().getCodeSource().getLocation();
        File source = new File(location.getPath());
        List<String> out = new ArrayList<>();
        if (source.isDirectory()) {
            Path root = source.toPath();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> root.relativize(p).toString().replace(File.separatorChar, '/'))
                    .filter(n -> n.startsWith("io/approov/"))
                    .forEach(out::add);
            }
        } else {
            try (JarFile jar = new JarFile(source)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String n = entries.nextElement().getName();
                    if (n.endsWith(".class") && n.startsWith("io/approov/")) {
                        out.add(n);
                    }
                }
            }
        }
        return out;
    }
}
