package org.cp26.huim.parser;

import org.chocosolver.memory.IEnvironment;
import org.chocosolver.memory.IStateBitSet;

public final class StateBitsetProjectedDB implements TransactionProjection {

    private final IEnvironment environment;
    private final int capacity;
    private final IStateBitSet tids;
    private final int[] cache;
    private int cacheSize;
    private boolean cacheValid;

    public StateBitsetProjectedDB(IEnvironment environment, int capacity) {
        this.environment = environment;
        this.capacity = capacity;
        this.tids = environment.makeBitSet(capacity);
        this.cache = new int[Math.max(1, capacity)];
        this.cacheSize = 0;
        this.cacheValid = true;
    }

    @Override
    public int size() {
        ensureCache();
        return cacheSize;
    }

    @Override
    public int tidAt(int index) {
        ensureCache();
        return cache[index];
    }

    @Override
    public void initAllTransactions(int transactionCount) {
        tids.clear();
        tids.set(0, transactionCount);
        cacheValid = false;
    }

    @Override
    public void initFromItem(HighUtilityDataset data, int item) {
        tids.clear();
        int[] itemTids = data.itemTransactionIds[item];
        for (int tid : itemTids) {
            tids.set(tid);
        }
        cacheValid = false;
    }

    @Override
    public void intersectWithItem(HighUtilityDataset data, int item) {
        int[] itemTids = data.itemTransactionIds[item];
        if (itemTids.length == 0) {
            tids.clear();
            cacheValid = false;
            return;
        }

        int j = 0;
        for (int tid = tids.nextSetBit(0); tid >= 0; tid = tids.nextSetBit(tid + 1)) {
            while (j < itemTids.length && itemTids[j] < tid) {
                j++;
            }
            if (j >= itemTids.length || itemTids[j] != tid) {
                tids.clear(tid);
            }
        }
        cacheValid = false;
    }

    @Override
    public StateBitsetProjectedDB copy() {
        StateBitsetProjectedDB copy = new StateBitsetProjectedDB(environment, capacity);
        for (int tid = tids.nextSetBit(0); tid >= 0; tid = tids.nextSetBit(tid + 1)) {
            copy.tids.set(tid);
        }
        copy.cacheValid = false;
        return copy;
    }

    private void ensureCache() {
        if (cacheValid) {
            return;
        }
        int out = 0;
        for (int tid = tids.nextSetBit(0); tid >= 0; tid = tids.nextSetBit(tid + 1)) {
            cache[out++] = tid;
        }
        cacheSize = out;
        cacheValid = true;
    }
}
