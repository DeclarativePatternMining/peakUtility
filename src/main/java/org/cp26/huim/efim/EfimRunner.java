/*
 * This file is part of io.gitlab.chaver:choco-mining (https://gitlab.com/chaver/choco-mining)
 *
 * Copyright (c) 2026, IMT Atlantique
 *
 * Licensed under the MIT license.
 *
 * See LICENSE file in the project root for full license information.
 */
package org.cp26.huim.efim;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.*;

/**
 * Minimal executable launcher for SPMF EFIM.
 *
 * Usage:
 *   java EfimRunner <dataset> <minUtil>
 *   java EfimRunner <dataset> <minUtil> <spmfJarPath>
 *
 * Behavior:
 * - Resolves input in data/ and writes output to data/patterns/<dataset>/HUI.txt.
 * - First tries to run EFIM through the SPMF API (if SPMF classes are in classpath).
 * - Otherwise runs SPMF jar with "run EFIM ...".
 * - Stops after 15 minutes.
 */
public class EfimRunner {
    private static final long DEFAULT_TIMEOUT_MINUTES = 15L;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            printUsageAndExit();
        }

        String datasetArg = args[0];
        int minUtil = parseMinUtil(args[1]);
        String jarPath = args.length >= 3 ? args[2] : findDefaultSpmfJar();
        long timeoutMinutes = args.length >= 4 ? parseTimeoutMinutes(args[3]) : DEFAULT_TIMEOUT_MINUTES;

        String input = resolveDatasetPath(datasetArg);
        String datasetKey = datasetKeyFromPath(input);
        String output = buildOutputPath(datasetKey);

        ensureInputExists(input);
        ensureOutputParentExists(output);

        System.out.println("Dataset : " + input);
        System.out.println("Output  : " + output);
        System.out.println("minUtil : " + minUtil);
        System.out.println("Timeout : " + timeoutMinutes + " minutes");

        if (tryRunViaApiWithTimeout(input, output, minUtil, timeoutMinutes, TimeUnit.MINUTES)) {
            System.out.println("EFIM finished via SPMF API within timeout.");
            printResultLine(output);
            return;
        }

        if (jarPath == null || jarPath.isBlank()) {
            throw new IllegalStateException(
                    "SPMF API not found in classpath and no spmf.jar provided.\n"
                            + "Provide jar path as 3rd argument.");
        }

        runViaJar(input, output, minUtil, jarPath, timeoutMinutes, TimeUnit.MINUTES);
        System.out.println("EFIM finished via spmf.jar within timeout.");
        printResultLine(output);
    }

    private static void printUsageAndExit() {
        System.err.println("Usage:");
        System.err.println("  java EfimRunner <dataset> <minUtil>");
        System.err.println("  java EfimRunner <dataset> <minUtil> <spmfJarPath>");
        System.err.println("  java EfimRunner <dataset> <minUtil> <spmfJarPath> <timeoutMinutes>");
        System.err.println();
        System.err.println("<dataset> can be:");
        System.err.println("  - a file in data/ (e.g., foodmart or foodmart.txt)");
        System.err.println("  - an explicit path to a .txt dataset");
        System.err.println("Output is always: data/patterns/<dataset>/HUI.txt");
        System.err.println();
        System.err.println("Example:");
        System.err.println("  java -cp SPMF EfimRunner foodmart 10000 SPMF/spmf.jar");
        System.exit(1);
    }

    private static int parseMinUtil(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("minUtil must be an integer: " + value, e);
        }
    }

    private static long parseTimeoutMinutes(String value) {
        try {
            long minutes = Long.parseLong(value);
            if (minutes <= 0) {
                throw new IllegalArgumentException("timeoutMinutes must be > 0: " + value);
            }
            return minutes;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("timeoutMinutes must be an integer: " + value, e);
        }
    }

    private static void printResultLine(String output) throws IOException {
        long lineCount = 0L;
        Path path = Path.of(output);
        if (Files.exists(path)) {
            try (var lines = Files.lines(path)) {
                lineCount = lines.count();
            }
        }
        System.out.println("RESULT\talgorithm=EFIM\tpatterns=" + lineCount);
    }

    private static void ensureInputExists(String input) {
        File file = new File(input);
        if (!file.exists()) {
            throw new IllegalArgumentException("Input file not found: " + input);
        }
    }

    private static void ensureOutputParentExists(String output) {
        File out = new File(output);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    private static boolean tryRunViaApiWithTimeout(String input,
                                                   String output,
                                                   int minUtil,
                                                   long timeout,
                                                   TimeUnit unit) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> future = executor.submit(() -> tryRunViaApi(input, output, minUtil));
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("EFIM API execution timed out after " + timeout + " " + unit.toString().toLowerCase());
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean tryRunViaApi(String input, String output, int minUtil) throws Exception {
        try {
            Class<?> efimClass = Class.forName("ca.pfv.spmf.algorithms.frequentpatterns.efim.AlgoEFIM");
            Object efim = efimClass.getDeclaredConstructor().newInstance();
            try {
                Method run = efimClass.getMethod("runAlgorithm", int.class, String.class, String.class, boolean.class, int.class, boolean.class);
                run.invoke(efim, minUtil, input, output, false, Integer.MAX_VALUE, true);
                tryPrintStats(efimClass, efim);
                return true;
            } catch (NoSuchMethodException e) {
                Method run = efimClass.getMethod("runAlgorithm", int.class, String.class, String.class);
                run.invoke(efim, minUtil, input, output);
                tryPrintStats(efimClass, efim);
                return true;
            }
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void tryPrintStats(Class<?> efimClass, Object efim) {
        try {
            Method printStats = efimClass.getMethod("printStats");
            printStats.invoke(efim);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
        }
    }

    private static void runViaJar(String input,
                                  String output,
                                  int minUtil,
                                  String jarPath,
                                  long timeout,
                                  TimeUnit unit)
            throws IOException, InterruptedException {
        File jarFile = new File(jarPath);
        if (!jarFile.exists()) {
            throw new IllegalArgumentException("SPMF jar not found: " + jarPath);
        }
        ProcessBuilder pb = new ProcessBuilder(
                "java", "-jar", jarPath, "run", "EFIM", input, output, String.valueOf(minUtil)
        );
        pb.inheritIO();
        Process process = pb.start();
        boolean finished = process.waitFor(timeout, unit);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("EFIM jar execution timed out after " + timeout + " " + unit.toString().toLowerCase());
        }
        int exit = process.exitValue();
        if (exit != 0) {
            throw new IllegalStateException("SPMF jar execution failed with exit code " + exit
                    + " command=" + Arrays.asList("java", "-jar", jarPath, "run", "EFIM", input, output, String.valueOf(minUtil)));
        }
    }

    private static String findDefaultSpmfJar() {
        String[] candidates = new String[]{
                "SPMF/spmf.jar",
                "SPMF/SPMF.jar",
                "spmf.jar",
                "src/main/java/org/cp26/huim/efim/spmf.jar"
        };
        for (String candidate : candidates) {
            if (new File(candidate).exists()) {
                return candidate;
            }
        }
        return null;
    }

    private static String resolveDatasetPath(String datasetArg) {
        File explicit = new File(datasetArg);
        if (explicit.exists()) {
            return explicit.getPath();
        }
        String candidate = datasetArg;
        if (!candidate.toLowerCase().endsWith(".txt")) {
            candidate = candidate + ".txt";
        }
        return new File("data", candidate).getPath();
    }

    private static String datasetKeyFromPath(String path) {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return name.substring(0, dot);
        }
        return name;
    }

    private static String buildOutputPath(String datasetKey) {
        return new File("data/patterns/" + datasetKey, "HUI.txt").getPath();
    }
}
