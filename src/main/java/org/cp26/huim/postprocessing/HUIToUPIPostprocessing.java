/*
 * This file is part of io.gitlab.chaver:choco-mining (https://gitlab.com/chaver/choco-mining)
 *
 * Copyright (c) 2026, IMT Atlantique
 *
 * Licensed under the MIT license.
 *
 * See LICENSE file in the project root for full license information.
 */

package org.cp26.huim.postprocessing;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
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

    private static final String DEFAULT_DATASET = "tiny_manual_spmf";
    private static final Pattern LINE_PATTERN = Pattern.compile("^(.+?)\\s+#UTIL:\\s*(\\d+)\\s*$");

    public static void main(String[] args) throws IOException {
        long start = System.currentTimeMillis();

        PathConfig paths = resolvePaths(args);
        String inputPath = paths.inputPath;
        String outputPath = paths.outputPath;

        IndexResult index = buildIndex(inputPath);
        FilterResult result = filterAndWriteUPI(inputPath, outputPath, index.utilityByKey, index.maxItemId);

        System.out.println("==============================================================");
        System.out.println("HUI -> UPI Postprocessing");
        System.out.println("Input file      : " + inputPath);
        System.out.println("Output file     : " + outputPath);
        System.out.println("HUI count       : " + index.huiCount);
        System.out.println("UPI count       : " + result.upiCount);
        System.out.println("Max item id     : " + index.maxItemId);
        System.out.println("Execution time  : " + (System.currentTimeMillis() - start) + " ms");
        System.out.println("==============================================================");
    }

    private static PathConfig resolvePaths(String[] args) {
        // Usage modes:
        // 1) no args -> dataset=foodmart
        // 2) <datasetName> -> data/patterns/<datasetName>/HUI.txt -> .../UPI.txt
        // 3) <inputFile.txt> [outputFile.txt] -> explicit paths
        if (args.length == 0) {
            return fromDatasetName(DEFAULT_DATASET);
        }

        String first = args[0];
        if (first.toLowerCase().endsWith(".txt")) {
            String output = args.length > 1 ? args[1] : defaultOutputForInput(first);
            return new PathConfig(first, output);
        }

        PathConfig byDataset = fromDatasetName(first);
        if (args.length > 1) {
            return new PathConfig(byDataset.inputPath, args[1]);
        }
        return byDataset;
    }

    private static PathConfig fromDatasetName(String datasetName) {
        String input = "data/patterns/" + datasetName + "/HUI.txt";
        String output = "data/patterns/" + datasetName + "/UPI.txt";
        return new PathConfig(input, output);
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

    private static IndexResult buildIndex(String inputPath) throws IOException {
        Map<Key128, Long> utilityByKey = new HashMap<>(1 << 20);
        int maxItemId = -1;
        int huiCount = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(inputPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
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
        return new IndexResult(utilityByKey, maxItemId, huiCount);
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
                                                  int maxItemId) throws IOException {
        int upiCount = 0;
        Key128 probe = new Key128(0L, 0L);
        File out = new File(outputPath);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(inputPath));
             BufferedWriter writer = new BufferedWriter(new FileWriter(out))) {
            String line;
            while ((line = reader.readLine()) != null) {
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
        return new FilterResult(upiCount);
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

        IndexResult(Map<Key128, Long> utilityByKey, int maxItemId, int huiCount) {
            this.utilityByKey = utilityByKey;
            this.maxItemId = maxItemId;
            this.huiCount = huiCount;
        }
    }

    private static class FilterResult {
        final int upiCount;

        FilterResult(int upiCount) {
            this.upiCount = upiCount;
        }
    }

    private static class PathConfig {
        final String inputPath;
        final String outputPath;

        PathConfig(String inputPath, String outputPath) {
            this.inputPath = inputPath;
            this.outputPath = outputPath;
        }
    }
}
