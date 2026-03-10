package org.cp26.huim.parser;

import java.io.*;
import java.util.*;

/**
 * Loads an HUIM dataset in SPMF format:
 *   items : transactionUtility : utils
 *
 * Stores:
 *  - transactionItems[tid] : item indices in transaction
 *  - transactionUtilities[tid] : utility aligned with transactionItems
 *  - itemTransactionIds[i], itemUtilities[i] : vertical representation (allowed transactions only)
 */
public final class HighUtilityDataset {

    public final int itemCount;
    public final int transactionCount;

    public final int[] itemIndexToOriginalId;
    public final Map<Integer, Integer> originalItemIdToIndex;

    public final int[][] transactionItems;
    public final int[][] transactionUtilities;

    public final int[] transactionUtility;

    public final int[] itemSupportCounts;
    public final int[][] itemTransactionIds;
    public final int[][] itemUtilities;

    private HighUtilityDataset(
            int itemCount,
            int transactionCount,
            int[] itemIndexToOriginalId,
            Map<Integer, Integer> originalItemIdToIndex,
            int[][] transactionItems,
            int[][] transactionUtilities,
            int[] transactionUtility,
            int[] itemSupportCounts,
            int[][] itemTransactionIds,
            int[][] itemUtilities
    ) {
        this.itemCount = itemCount;
        this.transactionCount = transactionCount;
        this.itemIndexToOriginalId = itemIndexToOriginalId;
        this.originalItemIdToIndex = originalItemIdToIndex;
        this.transactionItems = transactionItems;
        this.transactionUtilities = transactionUtilities;
        this.transactionUtility = transactionUtility;
        this.itemSupportCounts = itemSupportCounts;
        this.itemTransactionIds = itemTransactionIds;
        this.itemUtilities = itemUtilities;
    }

    public static HighUtilityDataset loadFromFile(String path) throws IOException {
        // default: keep all transactions
        return loadFromFile(path, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public static HighUtilityDataset loadFromFile(String path, int tuMin, int tuMax) throws IOException {

        // raw transactions (external ids)
        List<int[]> rawItems = new ArrayList<>();
        List<int[]> rawUtils = new ArrayList<>();
        List<Integer> rawTU = new ArrayList<>();
        Set<Integer> universe = new HashSet<>();

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("%")) continue;

                String[] p = line.split(":");
                if (p.length < 3) throw new IOException("Bad line (expected items:transactionUtility:utils): " + line);

                int[] items = parseInts(p[0]);
                int tu = Integer.parseInt(p[1].trim());
                int[] utils = parseInts(p[2]);

                if (items.length != utils.length) {
                    throw new IOException("Items/utils length mismatch: " + line);
                }

                rawItems.add(items);
                rawUtils.add(utils);
                rawTU.add(tu);
                for (int it : items) universe.add(it);
            }
        }

        // map external item ids -> 0..itemCount-1
        List<Integer> sorted = new ArrayList<>(universe);
        Collections.sort(sorted);

        int itemCount = sorted.size();
        int[] itemIndexToOriginalId = new int[itemCount];
        Map<Integer, Integer> originalItemIdToIndex = new HashMap<>(itemCount * 2);

        for (int i = 0; i < itemCount; i++) {
            int id = sorted.get(i);
            itemIndexToOriginalId[i] = id;
            originalItemIdToIndex.put(id, i);
        }

        int transactionCount = rawItems.size();
        int[][] transactionItems = new int[transactionCount][];
        int[][] transactionUtilities = new int[transactionCount][];
        int[] transactionUtility = new int[transactionCount];

        int[] itemSupportCounts = new int[itemCount];

        for (int t = 0; t < transactionCount; t++) {
            transactionUtility[t] = rawTU.get(t);

            int[] extItems = rawItems.get(t);
            int[] extUtils = rawUtils.get(t);

            int[] idxItems = new int[extItems.length];
            for (int k = 0; k < extItems.length; k++) {
                int idx = originalItemIdToIndex.get(extItems[k]);
                idxItems[k] = idx;

                itemSupportCounts[idx]++;
            }

            transactionItems[t] = idxItems;
            transactionUtilities[t] = extUtils;
        }

        // build vertical lists (allowed transactions only)
        @SuppressWarnings("unchecked")
        ArrayList<Integer>[] tidsL = new ArrayList[itemCount];
        @SuppressWarnings("unchecked")
        ArrayList<Integer>[] utilsL = new ArrayList[itemCount];

        for (int i = 0; i < itemCount; i++) {
            tidsL[i] = new ArrayList<>(itemSupportCounts[i]);
            utilsL[i] = new ArrayList<>(itemSupportCounts[i]);
        }

        for (int t = 0; t < transactionCount; t++) {

            int[] it = transactionItems[t];
            int[] ut = transactionUtilities[t];
            for (int k = 0; k < it.length; k++) {
                int item = it[k];
                tidsL[item].add(t);
                utilsL[item].add(ut[k]);
            }
        }

        int[][] itemTransactionIds = new int[itemCount][];
        int[][] itemUtilities = new int[itemCount][];

        for (int i = 0; i < itemCount; i++) {
            int sz = tidsL[i].size();
            itemTransactionIds[i] = new int[sz];
            itemUtilities[i] = new int[sz];
            for (int k = 0; k < sz; k++) {
                itemTransactionIds[i][k] = tidsL[i].get(k);
                itemUtilities[i][k] = utilsL[i].get(k);
            }
        }

        return new HighUtilityDataset(
                itemCount, transactionCount,
                itemIndexToOriginalId, originalItemIdToIndex,
                transactionItems, transactionUtilities,
                transactionUtility,
                itemSupportCounts, itemTransactionIds, itemUtilities
        );
    }

    private static int[] parseInts(String s) {
        String[] t = s.trim().split("\\s+");
        int[] r = new int[t.length];
        for (int i = 0; i < t.length; i++) r[i] = Integer.parseInt(t[i]);
        return r;
    }
}