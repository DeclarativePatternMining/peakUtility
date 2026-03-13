package cp26.mining.examples.tools;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.BitSet;

import cp26.mining.patterns.io.HUIReader;
import cp26.mining.patterns.io.TransactionalDatabase;

/**
 * Compare two pattern files (ON vs OFF) and validate UPI peak property
 * on the patterns that differ.
 *
 * Usage:
 *   java PeakPatternDiffValidator <dataset> <onFile> <offFile> [maxReport]
 */
public class PeakPatternDiffValidator {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java PeakPatternDiffValidator <dataset> <onFile> <offFile> [maxReport]");
            System.exit(1);
        }

        String dataset = args[0];
        String onFile = args[1];
        String offFile = args[2];
        int maxReport = args.length >= 4 ? Integer.parseInt(args[3]) : 20;

        TransactionalDatabase db = new HUIReader(dataset).read_hui();
        BitSet[] itemCovers = db.getVerticalRepresentation();
        int[][] itemUtils = db.getTransactionItemUtilities();
        int nbItems = db.getNbItems();

        Map<String, int[]> onPatterns = readPatterns(onFile);
        Map<String, int[]> offPatterns = readPatterns(offFile);

        List<int[]> onOnly = new ArrayList<>();
        for (Map.Entry<String, int[]> e : onPatterns.entrySet()) {
            if (!offPatterns.containsKey(e.getKey())) {
                onOnly.add(e.getValue());
            }
        }

        List<int[]> offOnly = new ArrayList<>();
        for (Map.Entry<String, int[]> e : offPatterns.entrySet()) {
            if (!onPatterns.containsKey(e.getKey())) {
                offOnly.add(e.getValue());
            }
        }

        System.out.println("Total ON patterns  : " + onPatterns.size());
        System.out.println("Total OFF patterns : " + offPatterns.size());
        System.out.println("ON-only patterns   : " + onOnly.size());
        System.out.println("OFF-only patterns  : " + offOnly.size());
        System.out.println();

        ValidationResult onOnlyRes = validateList(onOnly, itemCovers, itemUtils, nbItems, maxReport);
        ValidationResult offOnlyRes = validateList(offOnly, itemCovers, itemUtils, nbItems, maxReport);

        System.out.println("ON-only invalid (not peak): " + onOnlyRes.invalidCount);
        System.out.println("OFF-only invalid (not peak): " + offOnlyRes.invalidCount);
    }

    private static Map<String, int[]> readPatterns(String file) throws IOException {
        Map<String, int[]> patterns = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String left = line;
                int idx = line.indexOf("#UTIL");
                if (idx >= 0) {
                    left = line.substring(0, idx).trim();
                }
                if (left.isEmpty()) continue;
                String[] parts = left.split("\\s+");
                int[] items = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    items[i] = Integer.parseInt(parts[i]);
                }
                Arrays.sort(items);
                String key = join(items);
                patterns.put(key, items);
            }
        }
        return patterns;
    }

    private static ValidationResult validateList(List<int[]> patterns,
                                                 BitSet[] itemCovers,
                                                 int[][] itemUtils,
                                                 int nbItems,
                                                 int maxReport) {
        int invalid = 0;
        int reported = 0;
        for (int[] items : patterns) {
            if (!isPeakUPI(items, itemCovers, itemUtils, nbItems)) {
                invalid++;
                if (reported < maxReport) {
                    System.out.println("Invalid peak: " + join(items));
                    reported++;
                }
            }
        }
        return new ValidationResult(invalid);
    }

    private static boolean isPeakUPI(int[] items,
                                     BitSet[] itemCovers,
                                     int[][] itemUtils,
                                     int nbItems) {
        if (items.length == 0) return false;

        BitSet coverP = coverOf(items, itemCovers);
        if (coverP.isEmpty()) return false;
        long uP = utility(coverP, items, itemUtils);

        // Subset dominance: u(P \ {i}) < u(P)
        if (items.length > 1) {
            for (int idx = 0; idx < items.length; idx++) {
                int[] sub = new int[items.length - 1];
                int p = 0;
                for (int i = 0; i < items.length; i++) {
                    if (i == idx) continue;
                    sub[p++] = items[i];
                }
                BitSet coverSub = coverOf(sub, itemCovers);
                if (!coverSub.isEmpty()) {
                    long uSub = utility(coverSub, sub, itemUtils);
                    if (uSub >= uP) return false;
                }
            }
        }

        // Superset dominance: u(P ∪ {j}) < u(P) for all j not in P
        boolean[] inP = new boolean[nbItems];
        for (int item : items) inP[item] = true;
        for (int j = 0; j < nbItems; j++) {
            if (inP[j]) continue;
            BitSet coverSup = (BitSet) coverP.clone();
            coverSup.and(itemCovers[j]);
            if (coverSup.isEmpty()) continue;
            int[] sup = Arrays.copyOf(items, items.length + 1);
            sup[sup.length - 1] = j;
            long uSup = utility(coverSup, sup, itemUtils);
            if (uSup >= uP) return false;
        }

        return true;
    }

    private static BitSet coverOf(int[] items, BitSet[] itemCovers) {
        BitSet bs = new BitSet();
        if (items.length == 0) return bs;
        bs.or(itemCovers[items[0]]);
        for (int i = 1; i < items.length; i++) {
            bs.and(itemCovers[items[i]]);
            if (bs.isEmpty()) break;
        }
        return bs;
    }

    private static long utility(BitSet cover, int[] items, int[][] itemUtils) {
        long sum = 0;
        int tid = cover.nextSetBit(0);
        while (tid >= 0) {
            for (int item : items) {
                sum += itemUtils[tid][item];
            }
            tid = cover.nextSetBit(tid + 1);
        }
        return sum;
    }

    private static String join(int[] items) {
        if (items.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(items[0]);
        for (int i = 1; i < items.length; i++) {
            sb.append(' ').append(items[i]);
        }
        return sb.toString();
    }

    private static class ValidationResult {
        final int invalidCount;
        ValidationResult(int invalidCount) {
            this.invalidCount = invalidCount;
        }
    }
}
