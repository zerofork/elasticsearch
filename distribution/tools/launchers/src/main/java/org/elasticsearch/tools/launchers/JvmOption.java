/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.tools.launchers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class JvmOption {
    private final String value;
    private final String origin;

    JvmOption(String value, String origin) {
        this.value = value;
        this.origin = origin;
    }

    public Optional<String> getValue() {
        return Optional.ofNullable(value);
    }

    public String getMandatoryValue() {
        return value;
    }

    public boolean isCommandLineOrigin() {
        return "command line".equals(this.origin);
    }

    private static final Pattern OPTION = Pattern.compile(
        "^\\s*\\S+\\s+(?<flag>\\S+)\\s+:?=\\s+(?<value>\\S+)?\\s+\\{[^}]+?\\}(\\s+\\{(?<origin>[^}]+)})?"
    );

    public static Long extractMaxHeapSize(final Map<String, JvmOption> finalJvmOptions) {
        return Long.parseLong(finalJvmOptions.get("MaxHeapSize").getMandatoryValue());
    }

    public static boolean isMaxHeapSpecified(final Map<String, JvmOption> finalJvmOptions) {
        JvmOption maxHeapSize = finalJvmOptions.get("MaxHeapSize");
        return maxHeapSize != null && maxHeapSize.isCommandLineOrigin();
    }

    public static boolean isMinHeapSpecified(final Map<String, JvmOption> finalJvmOptions) {
        JvmOption minHeapSize = finalJvmOptions.get("MinHeapSize");
        return minHeapSize != null && minHeapSize.isCommandLineOrigin();
    }

    public static boolean isInitialHeapSpecified(final Map<String, JvmOption> finalJvmOptions) {
        JvmOption initialHeapSize = finalJvmOptions.get("InitialHeapSize");
        return initialHeapSize != null && initialHeapSize.isCommandLineOrigin();
    }

    public static long extractMaxDirectMemorySize(final Map<String, JvmOption> finalJvmOptions) {
        return Long.parseLong(finalJvmOptions.get("MaxDirectMemorySize").getMandatoryValue());
    }

    /**
     * Determine the options present when invoking a JVM with the given user defined options.
     */
    public static Map<String, JvmOption> findFinalOptions(final List<String> userDefinedJvmOptions) throws InterruptedException,
        IOException {
        return Collections.unmodifiableMap(
            flagsFinal(userDefinedJvmOptions).stream()
                .map(OPTION::matcher)
                .filter(Matcher::matches)
                .collect(Collectors.toMap(m -> m.group("flag"), m -> new JvmOption(m.group("value"), m.group("origin"))))
        );
    }

    private static List<String> flagsFinal(final List<String> userDefinedJvmOptions) throws InterruptedException, IOException {
        /*
         * To deduce the final set of JVM options that Elasticsearch is going to start with, we start a separate Java process with the JVM
         * options that we would pass on the command line. For this Java process we will add two additional flags, -XX:+PrintFlagsFinal and
         * -version. This causes the Java process that we start to parse the JVM options into their final values, display them on standard
         * output, print the version to standard error, and then exit. The JVM itself never bootstraps, and therefore this process is
         * lightweight. By doing this, we get the JVM options parsed exactly as the JVM that we are going to execute would parse them
         * without having to implement our own JVM option parsing logic.
         */
        final String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        final List<String> command = Collections.unmodifiableList(
            Stream.of(
                Stream.of(java),
                userDefinedJvmOptions.stream(),
                Stream.of("-Xshare:off"),
                Stream.of("-XX:+PrintFlagsFinal"),
                Stream.of("-version")
            ).reduce(Stream::concat).get().collect(Collectors.toList())
        );
        final Process process = new ProcessBuilder().command(command).start();
        final List<String> output = readLinesFromInputStream(process.getInputStream());
        final List<String> error = readLinesFromInputStream(process.getErrorStream());
        final int status = process.waitFor();
        if (status != 0) {
            final String message = String.format(
                Locale.ROOT,
                "starting java failed with [%d]\noutput:\n%s\nerror:\n%s",
                status,
                String.join("\n", output),
                String.join("\n", error)
            );
            throw new RuntimeException(message);
        } else {
            return output;
        }
    }

    private static List<String> readLinesFromInputStream(final InputStream is) throws IOException {
        try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8); BufferedReader br = new BufferedReader(isr)) {
            return Collections.unmodifiableList(br.lines().collect(Collectors.toList()));
        }
    }
}
