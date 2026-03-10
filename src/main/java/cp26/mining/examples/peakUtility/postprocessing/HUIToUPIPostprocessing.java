/*
 * This file is part of cp26:peakutility (https://gitlab.com/chaver/choco-mining)
 *
 * Copyright (c) 2026, IMT Atlantique
 *
 * Licensed under the MIT license.
 *
 * See LICENSE file in the project root for full license information.
 */
package cp26.mining.examples.peakUtility.postprocessing;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Postprocessing HUI -> UPI.
 *
 * Input format (one pattern per line):
 *   item1 item2 ... itemN #UTIL: value
 *
 * A pattern P is kept in UPI output iff:
 * - for every item x in P: u(P\\{x}) < u(P)
 * - for every item y not in P: u(P U {y}) < u(P)
 *
 * Utilities are taken from the input HUI file.
 */
public class HUIToUPIPostprocessing {

    private static final String DEFAULT_DATASET = "foodmart_utility_spmf";
    private static final Pattern LINE_PATTERN = Pattern.compile("^(.+?)\\s+#UTIL:\\s*(\\d+)\\s*$");
    private static final long NO_TIMEOUT_DEADLINE = Long.MAX_VALUE;

    public static void main(String[] args) throws IOException {
        long start = System.currentTimeMillis();

        PathConfig paths = resolvePaths(args);
        String inputPath = paths.inputPath;
        String outputPath = paths.outputPath;
        long timeoutSec = paths.timeoutSec;
        long deadlineNs = timeoutSec > 0 ? System.nanoTime() + timeoutSec * 1_000_000_000L : NO_TIMEOUT_DEADLINE;

        IndexResult index = buildIndex(inputPath, deadlineNs);
        FilterResult result;
        if (index.timedOut) {
            result = new FilterResult(0, true);
        } else {
            result = filterAndWriteUPI(inputPath, outputPath, index.utilityByKey, index.maxItemId, deadlineNs);
        }

        System.out.println("==============================================================");
        System.out.println("HUI -> UPI Postprocessing");
        System.out.println("Input file      : " + inputPath);
        System.out.println("Output file     : " + outputPath);
        System.out.println("Timeout (sec)   : " + (timeoutSec > 0 ? timeoutSec : "none"));
        System.out.println("HUI count       : " + index.huiCount);
        System.out.println("UPI count       : " + result.upiCount);
        System.out.println("Max item id     : " + index.maxItemId);
        System.out.println("Timeout reached : " + (index.timedOut || result.timedOut));
        System.out.println("Execution time  : " + (System.currentTimeMillis() - start) + " ms");
        System.out.println("==============================================================");
    }

    private static PathConfig resolvePaths(String[] args) {
        // Usage modes:
        // 1) no args -> dataset=foodmart
        // 2) <datasetName> [timeoutSec]
        // 3) <datasetName> <outputFile.txt> [timeoutSec]
        // 4) <inputFile.txt> [timeoutSec]
        // 5) <inputFile.txt> <outputFile.txt> [timeoutSec]
        if (args.length == 0) {
            return fromDatasetName(DEFAULT_DATASET, 0L);
        }

        String first = args[0];
        if (first.toLowerCase().endsWith(".txt")) {
            if (args.length > 1 && isPositiveInteger(args[1])) {
                long timeoutSec = Long.parseLong(args[1]);
                return new PathConfig(first, defaultOutputForInput(first), timeoutSec);
            }
            String output = args.length > 1 ? args[1] : defaultOutputForInput(first);
            long timeoutSec = args.length > 2 ? parseTimeoutSec(args[2]) : 0L;
            return new PathConfig(first, output, timeoutSec);
        }

        if (args.length > 1 && isPositiveInteger(args[1])) {
            return fromDatasetName(first, Long.parseLong(args[1]));
        }
        PathConfig byDataset = fromDatasetName(first, 0L);
        if (args.length > 1) {
            String output = args[1];
            long timeoutSec = args.length > 2 ? parseTimeoutSec(args[2]) : 300000L;
            return new PathConfig(byDataset.inputPath, output, timeoutSec);
        }
        return byDataset;
    }

    private static PathConfig fromDatasetName(String datasetName, long timeoutSec) {
        String input = "datasets/patterns/" + datasetName + "/HUI.txt";
        String output = "datasets/patterns/" + datasetName + "/UPI.txt";
        return new PathConfig(input, output, timeoutSec);
    }

    private static long parseTimeoutSec(String value) {
        if (!isPositiveInteger(value)) {
            throw new IllegalArgumentException("timeoutSec must be a positive integer");
        }
        return Long.parseLong(value);
    }

    private static boolean isPositiveInteger(String value) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String defaultOutputForInput(String inputPath) {
        String normalized = inputPath.replace('\\', '/');
        if (normalized.endsWith("/HUI.txt")) {
            return inputPath.substring(0, inputPath.length() - "HUI.txt".length()) + "UPI.txt";
        }
        int dot = inputPath.lastIndexOf('.');
        if (dot < 0) {
            return inputPath + "_UPI";
        }
        return inputPath.substring(0, dot) + "_UPI" + inputPath.substring(dot);
    }

    private static IndexResult buildIndex(String inputPath, long deadlineNs) throws IOException {
        Map<Key128, Long> utilityByKey = new HashMap<>(1 << 20);
        int maxItemId = -1;
        int huiCount = 0;
        boolean timedOut = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(inputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isTimedOut(deadlineNs)) {
                    timedOut = true;
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                ParsedPattern rec = parseLine(line);
                if (rec == null) {
                    continue;
                }
                huiCount++;
                Key128 key = hashFull(rec.items);
                Long prev = utilityByKey.get(key);
                if (prev == null || rec.utility > prev) {
                    utilityByKey.put(key, rec.utility);
                }
                for (int item : rec.items) {
                    if (item > maxItemId) {
                        maxItemId = item;
                    }
                }
            }
        }
        return new IndexResult(utilityByKey, maxItemId, huiCount, timedOut);
    }

    private static ParsedPattern parseLine(String line) {
        Matcher m = LINE_PATTERN.matcher(line);
        if (!m.matches()) {
            return null;
        }

        String[] tokens = m.group(1).trim().split("\\s+");
        int[] items = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            items[i] = Integer.parseInt(tokens[i]);
        }
        Arrays.sort(items);

        long utility = Long.parseLong(m.group(2));
        return new ParsedPattern(items, utility);
    }

    private static FilterResult filterAndWriteUPI(String inputPath,
                                                  String outputPath,
                                                  Map<Key128, Long> utilityByKey,
                                                  int maxItemId,
                                                  long deadlineNs) throws IOException {
        int upiCount = 0;
        boolean timedOut = false;
        Key128 probe = new Key128(0L, 0L);
        File out = new File(outputPath);
        File tmp = new File(outputPath + ".tmp");
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(inputPath));
             BufferedWriter writer = new BufferedWriter(new FileWriter(tmp))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isTimedOut(deadlineNs)) {
                    timedOut = true;
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                ParsedPattern p = parseLine(line);
                if (p == null) {
                    continue;
                }
                if (isPeakPattern(p, utilityByKey, maxItemId, probe)) {
                    writer.write(line);
                    writer.newLine();
                    upiCount++;
                }
            }
        }
        if (timedOut) {
            Files.deleteIfExists(tmp.toPath());
            return new FilterResult(0, true);
        }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return new FilterResult(upiCount, false);
    }

    private static boolean isTimedOut(long deadlineNs) {
        return deadlineNs != NO_TIMEOUT_DEADLINE && System.nanoTime() >= deadlineNs;
    }

    private static boolean isPeakPattern(ParsedPattern p, Map<Key128, Long> utilityByKey, int maxItemId, Key128 probe) {
        long u = p.utility;

        // Remove one item: utility must strictly decrease
        for (int i = 0; i < p.items.length; i++) {
            Key128 subsetKey = hashWithoutIndex(p.items, i);
            setProbe(probe, subsetKey);
            Long subsetU = utilityByKey.get(probe);
            if (subsetU != null && subsetU >= u) {
                return false;
            }
        }

        // Add one item: utility must strictly decrease
        BitSet present = new BitSet(maxItemId + 1);
        for (int item : p.items) {
            present.set(item);
        }
        for (int item = 0; item <= maxItemId; item++) {
            if (present.get(item)) {
                continue;
            }
            Key128 supKey = hashWithInsertedItem(p.items, item);
            setProbe(probe, supKey);
            Long supU = utilityByKey.get(probe);
            if (supU != null && supU >= u) {
                return false;
            }
        }

        return true;
    }

    private static void setProbe(Key128 probe, Key128 key) {
        probe.h1 = key.h1;
        probe.h2 = key.h2;
    }

    private static Key128 hashFull(int[] sortedItems) {
        long h1 = 0x9E3779B97F4A7C15L;
        long h2 = 0xC2B2AE3D27D4EB4FL;
        for (int value : sortedItems) {
            long v = (value + 0x9E3779B9L);
            h1 = mix64(h1 ^ v);
            h2 = mix64(h2 + (v * 0x9E3779B97F4A7C15L));
        }
        h1 = mix64(h1 ^ sortedItems.length);
        h2 = mix64(h2 ^ (sortedItems.length * 0xD6E8FEB86659FD93L));
        return new Key128(h1, h2);
    }

    private static Key128 hashWithoutIndex(int[] sortedItems, int removeIndex) {
        long h1 = 0x9E3779B97F4A7C15L;
        long h2 = 0xC2B2AE3D27D4EB4FL;
        for (int i = 0; i < sortedItems.length; i++) {
            if (i == removeIndex) {
                continue;
            }
            long v = (sortedItems[i] + 0x9E3779B9L);
            h1 = mix64(h1 ^ v);
            h2 = mix64(h2 + (v * 0x9E3779B97F4A7C15L));
        }
        int len = sortedItems.length - 1;
        h1 = mix64(h1 ^ len);
        h2 = mix64(h2 ^ (len * 0xD6E8FEB86659FD93L));
        return new Key128(h1, h2);
    }

    private static Key128 hashWithInsertedItem(int[] sortedItems, int newItem) {
        long h1 = 0x9E3779B97F4A7C15L;
        long h2 = 0xC2B2AE3D27D4EB4FL;
        boolean inserted = false;
        for (int value : sortedItems) {
            if (!inserted && newItem < value) {
                long v = (newItem + 0x9E3779B9L);
                h1 = mix64(h1 ^ v);
                h2 = mix64(h2 + (v * 0x9E3779B97F4A7C15L));
                inserted = true;
            }
            long v2 = (value + 0x9E3779B9L);
            h1 = mix64(h1 ^ v2);
            h2 = mix64(h2 + (v2 * 0x9E3779B97F4A7C15L));
        }
        if (!inserted) {
            long v = (newItem + 0x9E3779B9L);
            h1 = mix64(h1 ^ v);
            h2 = mix64(h2 + (v * 0x9E3779B97F4A7C15L));
        }
        int len = sortedItems.length + 1;
        h1 = mix64(h1 ^ len);
        h2 = mix64(h2 ^ (len * 0xD6E8FEB86659FD93L));
        return new Key128(h1, h2);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    private static class ParsedPattern {
        final int[] items;
        final long utility;

        ParsedPattern(int[] items, long utility) {
            this.items = items;
            this.utility = utility;
        }
    }

    private static class Key128 {
        long h1;
        long h2;

        Key128(long h1, long h2) {
            this.h1 = h1;
            this.h2 = h2;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key128)) return false;
            Key128 key128 = (Key128) o;
            return h1 == key128.h1 && h2 == key128.h2;
        }

        @Override
        public int hashCode() {
            long x = h1 * 31L + h2;
            return (int) (x ^ (x >>> 32));
        }
    }

    private static class IndexResult {
        final Map<Key128, Long> utilityByKey;
        final int maxItemId;
        final int huiCount;
        final boolean timedOut;

        IndexResult(Map<Key128, Long> utilityByKey, int maxItemId, int huiCount, boolean timedOut) {
            this.utilityByKey = utilityByKey;
            this.maxItemId = maxItemId;
            this.huiCount = huiCount;
            this.timedOut = timedOut;
        }
    }

    private static class FilterResult {
        final int upiCount;
        final boolean timedOut;

        FilterResult(int upiCount, boolean timedOut) {
            this.upiCount = upiCount;
            this.timedOut = timedOut;
        }
    }

    private static class PathConfig {
        final String inputPath;
        final String outputPath;
        final long timeoutSec;

        PathConfig(String inputPath, String outputPath, long timeoutSec) {
            this.inputPath = inputPath;
            this.outputPath = outputPath;
            this.timeoutSec = timeoutSec;
        }
    }
}
