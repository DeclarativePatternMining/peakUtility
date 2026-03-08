package org.cp26.huim.parser;

import java.util.Arrays;

/**
 * Sorted set of tids representing T(S).
 * All operations keep only allowed transactions.
 */
public final class ProjectedDB implements TransactionProjection {

    private int[] tids;
    private int size;

    public ProjectedDB(int capacity) {
        this.tids = new int[Math.max(1, capacity)];
        this.size = 0;
    }

    @Override
    public int size() { return size; }

    @Override
    public int tidAt(int i) { return tids[i]; }

    @Override
    public void initAllTransactions(int transactionCount) {
        ensureCapacity(transactionCount);
        size = transactionCount;
        for (int t = 0; t < transactionCount; t++) {
            tids[t] = t;
        }
    }

    public void initAllAllowed(boolean[] allowedTransactions) {
        ensureCapacity(allowedTransactions.length);
        size = 0;
        for (int t = 0; t < allowedTransactions.length; t++) {
            if (allowedTransactions[t]) tids[size++] = t;
        }
    }

    @Override
    public void initFromItem(HighUtilityDataset data, int item) {
        int[] itTids = data.itemTransactionIds[item];
        ensureCapacity(itTids.length);
        size = 0;
        for (int k = 0; k < itTids.length; k++) {
            int tid = itTids[k];
             tids[size++] = tid;
        }
    }

    @Override
    public void intersectWithItem(HighUtilityDataset data, int item) {
        int[] b = data.itemTransactionIds[item];
        if (size == 0 || b.length == 0) { size = 0; return; }

        int[] a = this.tids;
        int asz = this.size;

        int i = 0, j = 0, out = 0;
        while (i < asz && j < b.length) {
            int ta = a[i];
            int tb = b[j];
            if (ta == tb) {
                 a[out++] = ta;
                i++; j++;
            } else if (ta < tb) {
                i++;
            } else {
                j++;
            }
        }
        this.size = out;
    }

    @Override
    public ProjectedDB copy() {
        ProjectedDB c = new ProjectedDB(size);
        c.tids = Arrays.copyOf(this.tids, this.size);
        c.size = this.size;
        return c;
    }

    private void ensureCapacity(int cap) {
        if (tids.length < cap) tids = Arrays.copyOf(tids, cap);
    }
}
