package org.cp26.huim.global;

import org.chocosolver.solver.constraints.Propagator;
import org.chocosolver.solver.constraints.PropagatorPriority;
import org.chocosolver.solver.exception.ContradictionException;
import org.chocosolver.solver.variables.BoolVar;
import org.chocosolver.util.ESat;
import org.cp26.huim.parser.HighUtilityDataset;
import org.cp26.huim.parser.ProjectedDB;
import org.cp26.huim.parser.StateBitsetProjectedDB;
import org.cp26.huim.parser.TransactionProjection;

/**
 * Unified PeakUtility constraint V1 that combines HUIBounds and PeakUtility_v0 propagation.
 * Supports two modes:
 * - HUI: Only HUIBounds propagation (basic utility bounds checking)
 * - UPI: Both HUIBounds and PeakUtility_v0 propagation (includes peak utility checking)
 *
 * Adapted to use HighUtilityDataset and ProjectedDB from this project.
 */
public final class PropPeakUtility extends Propagator<BoolVar> {

    public enum Mode {
        HUI,  // High-Utility Itemsets: only HUIBounds propagation
        UPI   // Utility-Peak Itemsets: HUIBounds + PeakUtility_v0 propagation
    }

    private final HighUtilityDataset data;
    private final int minUtil;
    private final int depthK;  // Depth threshold for Rule 6 pruning
    private final Mode mode;
    private final ProjectionBackend projectionBackend;

    // Single reusable partition buffer:
    // [0, oneCount) => ones
    // [oneCount, oneCount + freeCount) => frees
    // [oneCount + freeCount, vars.length) => zeros
    private final int[] stateOrder;
    private int oneCount;
    private int freeCount;
    private int zeroStart;

    /**
     * Creates a unified PeakUtility_V1 constraint combining HUIBounds and PeakUtility_v0.
     *
     * @param x Boolean variables representing the itemset
     * @param data HighUtilityDataset containing dataset information
     * @param minUtil Minimum utility threshold
     * @param depthK Depth threshold for Rule 6 pruning (only used in UPI mode)
     * @param mode Mining mode (HUI or UPI)
     */
    public PropPeakUtility(BoolVar[] x, HighUtilityDataset data, int minUtil, int depthK, Mode mode, ProjectionBackend projectionBackend) {
        super(x, PropagatorPriority.QUADRATIC, false);
        this.data = data;
        this.minUtil = minUtil;
        this.depthK = depthK;
        this.mode = mode;
        this.projectionBackend = projectionBackend;
        this.stateOrder = new int[x.length];
    }

    @Override
    public void propagate(int evtmask) throws ContradictionException {

        partitionStates();

        TransactionProjection proj = buildProjection(stateOrder, oneCount);

        // Rule 1: TWU(P) < minUtil => fail immediately
        long twu = projectedTransactionUtility(proj);
        if (twu < minUtil) {
            fails();
            return;
        }

        // Exact utility
        long utility = exactUtilityOnProjection(proj, stateOrder, oneCount);

        // new) If projection is empty and we have selected items, itemset cannot be formed
        if (proj.size() == 0 && oneCount > 0) {
            fails();
            return;
        }

        // If already fully assigned, apply peak utility checks
        if (freeCount == 0) {
            // Always enforce minUtil
            if (utility < minUtil) fails();

            // Apply peak utility checks in UPI mode
            if (mode == Mode.UPI) {
                applyPeakUtilityChecks(proj, stateOrder, oneCount, utility);
            }
            return;
        }

        // 4) Remaining utility bound RU(P) < minUtil => fail
        long ru = remainingUtilityBoundOverFrees(proj, oneCount, zeroStart);

        // 5) Node feasibility (minUtil)
        if (utility + ru < minUtil) fails();

        // new) Item elimination (HUIBounds pruning)
        for (int ui = oneCount; ui < zeroStart; ui++) {
            int item = stateOrder[ui];

            TransactionProjection proj2 = proj.copy();
            proj2.intersectWithItem(data, item);

            if (proj2.size() == 0) {
                vars[item].instantiateTo(0, this);
                continue;
            }

            long uSproj2 = exactUtilityOnProjection(proj2, stateOrder, oneCount);
            long uItem = utilityOfItemOnProjection(proj2, item);

            long ruSi = remainingUtilityBoundOverFrees(proj2, oneCount, zeroStart);

            // Min utility pruning
            if (uSproj2 + uItem + ruSi < minUtil) {
                vars[item].instantiateTo(0, this);
            }
        }

        // If pruning instantiated everything in this call, we must re-check
        // complete-assignment semantics (including UPI rules 4/5).
        if (allInstantiatedNow()) {
            partitionStates();
            TransactionProjection finalProj = buildProjection(stateOrder, oneCount);
            long finalU = exactUtilityOnProjection(finalProj, stateOrder, oneCount);
            if (finalU < minUtil) {
                fails();
                return;
            }
            if (mode == Mode.UPI) {
                applyPeakUtilityChecks(finalProj, stateOrder, oneCount, finalU);
            }
            return;
        }

        // Apply peak utility pruning in UPI mode (Rule 6)
        if (mode == Mode.UPI && depthK > 0) {
            // Domains may have changed during Rule 3-style pruning
            partitionStates();
            applyDepthKSubsetPruning(utility);
        }

        // Final safety: if complete assignment is reached at end of propagate,
        // enforce full semantics so propagate and isEntailed stay consistent.
        if (allInstantiatedNow()) {
            partitionStates();
            TransactionProjection finalProj = buildProjection(stateOrder, oneCount);
            long finalU = exactUtilityOnProjection(finalProj, stateOrder, oneCount);
            if (finalU < minUtil) {
                fails();
                return;
            }
            if (mode == Mode.UPI) {
                applyPeakUtilityChecks(finalProj, stateOrder, oneCount, finalU);
            }
        }
    }

    /**
     * Peak Utility checks for fully assigned solutions (Rule 4 & 5).
     */
    private void applyPeakUtilityChecks(TransactionProjection proj, int[] ones, int sz, long uP) throws ContradictionException {
        // Rule 4 — subset dominance: if any proper subset has utility >= uP, reject
        for (int j = 0; j < sz; j++) {
            int excludeItem = ones[j];
            TransactionProjection subsetProj = rebuildProjectionExcluding(ones, sz, excludeItem);

            if (subsetProj.size() > 0) {
                long uSub = exactUtilityOnProjection(subsetProj, ones, sz, excludeItem);
                if (uSub >= uP) {
                    fails();
                    return;
                }
            }
        }

        // Rule 5 — superset dominance: check against excluded items
        for (int i = zeroStart; i < vars.length; i++) {
            int excludedItem = stateOrder[i];
            TransactionProjection supProj = proj.copy();
            supProj.intersectWithItem(data, excludedItem);

            if (supProj.size() > 0) {
                long uSup = exactUtilityOnProjection(supProj, ones, sz);
                uSup += utilityOfItemOnProjection(supProj, excludedItem);

                if (uSup >= uP) {
                    fails();
                    return;
                }
            }
        }
    }

    /**
     * Rule 6 (as in pseudo-code):
     * If |frees| == k and all non-empty subsets S of frees satisfy u(S) < u(ones),
     * then prune all frees to 0.
     */
    private void applyDepthKSubsetPruning(long uOnes) throws ContradictionException {
        if (freeCount != depthK || freeCount <= 0) {
            return;
        }
        // Avoid undefined bit shifts if k is unexpectedly large.
        if (freeCount >= Integer.SIZE - 1) {
            return;
        }

        int start = oneCount;
        int end = zeroStart;
        int subsetCount = 1 << freeCount;
        int[] subsetItems = new int[freeCount];
        boolean allLower = true;

        for (int mask = 1; mask < subsetCount; mask++) {
            int subsetSz = 0;
            for (int bit = 0; bit < freeCount; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    subsetItems[subsetSz++] = stateOrder[start + bit];
                }
            }

            TransactionProjection subProj = buildProjection(subsetItems, subsetSz);
            long uSubset = exactUtilityOnProjection(subProj, subsetItems, subsetSz);
            if (uSubset >= uOnes) {
                allLower = false;
                break;
            }
        }

        if (allLower) {
            for (int i = start; i < end; i++) {
                int item = stateOrder[i];
                vars[item].instantiateTo(0, this);
            }
        }
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    private void partitionStates() {
        oneCount = 0;
        freeCount = 0;

        for (BoolVar var : vars) {
            if (var.isInstantiatedTo(1)) {
                oneCount++;
            } else if (!var.isInstantiated()) {
                freeCount++;
            }
        }
        zeroStart = oneCount + freeCount;

        int oneWrite = 0;
        int freeWrite = oneCount;
        int zeroWrite = zeroStart;
        for (int i = 0; i < vars.length; i++) {
            BoolVar var = vars[i];
            if (var.isInstantiatedTo(1)) {
                stateOrder[oneWrite++] = i;
            } else if (var.isInstantiated()) {
                stateOrder[zeroWrite++] = i;
            } else {
                stateOrder[freeWrite++] = i;
            }
        }
    }

    private TransactionProjection buildProjection(int[] items, int itemsCount) {
        TransactionProjection proj = newProjection();
        if (itemsCount == 0) {
            proj.initAllTransactions(data.transactionCount);
            return proj;
        }

        proj.initFromItem(data, items[0]);
        for (int k = 1; k < itemsCount; k++) {
            proj.intersectWithItem(data, items[k]);
            if (proj.size() == 0) break;
        }
        return proj;
    }

    private TransactionProjection rebuildProjectionFromItems(int[] items, int itemsSz) {
        return buildProjection(items, itemsSz);
    }

    private TransactionProjection rebuildProjectionExcluding(int[] items, int itemsSz, int excludeItem) {
        TransactionProjection proj = newProjection();
        int firstItem = -1;

        // Find first item != excludeItem
        for (int k = 0; k < itemsSz; k++) {
            if (items[k] != excludeItem) {
                firstItem = items[k];
                break;
            }
        }

        if (firstItem != -1) {
            proj.initFromItem(data, firstItem);
            for (int k = 0; k < itemsSz; k++) {
                if (items[k] != excludeItem && items[k] != firstItem) {
                    proj.intersectWithItem(data, items[k]);
                    if (proj.size() == 0) break;
                }
            }
        }
        return proj;
    }

    private long exactUtilityOnProjection(TransactionProjection proj, int[] sel, int selSz) {
        if (selSz == 0 || proj.size() == 0) return 0L;
        long sum = 0L;
        for (int k = 0; k < selSz; k++) {
            sum += utilityOfItemOnProjection(proj, sel[k]);
        }
        return sum;
    }

    private long exactUtilityOnProjection(TransactionProjection proj, int[] sel, int selSz, int excludeItem) {
        if (selSz == 0 || proj.size() == 0) return 0L;
        long sum = 0L;
        for (int k = 0; k < selSz; k++) {
            if (sel[k] != excludeItem) {
                sum += utilityOfItemOnProjection(proj, sel[k]);
            }
        }
        return sum;
    }

    private long utilityOfItemOnProjection(TransactionProjection proj, int item) {
        int[] itTids = data.itemTransactionIds[item];
        int[] itUtils = data.itemUtilities[item];

        long sum = 0L;
        int i = 0, j = 0;
        int psz = proj.size();

        while (i < psz && j < itTids.length) {
            int tp = proj.tidAt(i);
            int ti = itTids[j];
            if (tp == ti) {
                sum += itUtils[j];
                i++; j++;
            } else if (tp < ti) {
                i++;
            } else {
                j++;
            }
        }
        return sum;
    }

    private long remainingUtilityBoundOverFrees(TransactionProjection proj, int freeStart, int freeEnd) {
        long ru = 0L;
        for (int i = freeStart; i < freeEnd; i++) {
            int freeItem = stateOrder[i];
            ru += utilityOfItemOnProjection(proj, freeItem);
        }
        return ru;
    }

    private long projectedTransactionUtility(TransactionProjection proj) {
        long sum = 0L;
        for (int idx = 0; idx < proj.size(); idx++) {
            sum += data.transactionUtility[proj.tidAt(idx)];
        }
        return sum;
    }

    private boolean allInstantiatedNow() {
        for (BoolVar v : vars) {
            if (!v.isInstantiated()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ESat isEntailed() {
        partitionStates();

        TransactionProjection proj = buildProjection(stateOrder, oneCount);
        long u = exactUtilityOnProjection(proj, stateOrder, oneCount);

        if (freeCount == 0) {
            if (u < minUtil) return ESat.FALSE;
            if (mode == Mode.UPI) {
                try {
                    applyPeakUtilityChecks(proj, stateOrder, oneCount, u);
                } catch (ContradictionException e) {
                    return ESat.FALSE;
                }
            }
            return ESat.TRUE;
        }

        long ru = remainingUtilityBoundOverFrees(proj, oneCount, zeroStart);
        if (u + ru < minUtil) return ESat.FALSE;
        return ESat.UNDEFINED;
    }

    private TransactionProjection newProjection() {
        if (projectionBackend == ProjectionBackend.BITSET) {
            return new StateBitsetProjectedDB(model.getEnvironment(), data.transactionCount);
        }
        return new ProjectedDB(data.transactionCount);
    }
}
