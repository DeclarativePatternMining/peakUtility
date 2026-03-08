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
 * Semantic HUIM constraint:
 *   minUtil <= U(X) <= maxUtil (if maxUtil > 0), over allowed transactions only.
 *
 * Propagation uses:
 *   - projection T(S)
 *   - exact utility U(S)
 *   - remaining utility upper bound RU(S) (order-free)
 */
public final class PropHUIMGlobal extends Propagator<BoolVar> {

    private final HighUtilityDataset data;
    private final int minUtil;
    private final int maxUtil; // <=0 means "no max"
    private final ProjectionBackend projectionBackend;

    private final int[] selected;
    private final int[] undecided;
    private int selSz, undSz;

    public PropHUIMGlobal(BoolVar[] x, HighUtilityDataset data, int minUtil, int maxUtil, ProjectionBackend projectionBackend) {
        super(x, PropagatorPriority.QUADRATIC, false);
        this.data = data;
        this.minUtil = minUtil;
        this.maxUtil = maxUtil;
        this.projectionBackend = projectionBackend;
        this.selected = new int[x.length];
        this.undecided = new int[x.length];
    }

    @Override
    public void propagate(int evtmask) throws ContradictionException {

        // 1) Collect selected and undecided
        selSz = 0;
        undSz = 0;
        for (int i = 0; i < vars.length; i++) {
            if (vars[i].isInstantiated()) {
                if (vars[i].getValue() == 1) selected[selSz++] = i;
            } else {
                undecided[undSz++] = i;
            }
        }

        // 2) Build projection T(S)
        TransactionProjection proj = newProjection();
        if (selSz == 0) {
            proj.initAllTransactions(data.transactionCount);
        } else {
            proj.initFromItem(data, selected[0]);
            for (int k = 1; k < selSz; k++) {
                proj.intersectWithItem(data, selected[k]);
                if (proj.size() == 0) break;
            }
        }

        // 3) Exact utility U(S)
        long uS = exactUtilityOnProjection(proj, selected, selSz);

        // Max utility: if already too large, impossible (utilities are non-negative)
        if (maxUtil > 0 && uS > maxUtil) fails();

        // If already fully assigned, enforce semantics right away
        if (undSz == 0) {
            if (uS < minUtil) fails();
            // maxUtil already checked above
            return;
        }

        // 4) Remaining utility bound RU(S)
        long ruS = remainingUtilityBound(proj);

        // 5) Node feasibility (minUtil)
        if (uS + ruS < minUtil) fails();

        // 6) Item elimination
        for (int ui = 0; ui < undSz; ui++) {
            int item = undecided[ui];

            TransactionProjection proj2 = proj.copy();
            proj2.intersectWithItem(data, item);

            if (proj2.size() == 0) {
                vars[item].instantiateTo(0, this);
                continue;
            }

            long uSproj2 = exactUtilityOnProjection(proj2, selected, selSz);
            long uItem = utilityOfItemOnProjection(proj2, item);

            // Max utility pruning: if selecting item already exceeds maxUtil, forbid it
            if (maxUtil > 0 && (uSproj2 + uItem) > maxUtil) {
                vars[item].instantiateTo(0, this);
                continue;
            }

            long ruSi = remainingUtilityBound(proj2);

            // Min utility pruning
            if (uSproj2 + uItem + ruSi < minUtil) {
                vars[item].instantiateTo(0, this);
            }
        }

        // If pruning instantiated everything, must satisfy minUtil (and maxUtil already checked)
        if (allInstantiatedNow() && uS < minUtil) {
            fails();
        }
    }

    @Override
    public ESat isEntailed() {
        selSz = 0;
        boolean allInst = true;
        for (int i = 0; i < vars.length; i++) {
            if (!vars[i].isInstantiated()) {
                allInst = false;
            } else if (vars[i].getValue() == 1) {
                selected[selSz++] = i;
            }
        }

        TransactionProjection proj = newProjection();
        if (selSz == 0) {
            proj.initAllTransactions(data.transactionCount);
        } else {
            proj.initFromItem(data, selected[0]);
            for (int k = 1; k < selSz; k++) {
                proj.intersectWithItem(data, selected[k]);
                if (proj.size() == 0) break;
            }
        }

        long u = exactUtilityOnProjection(proj, selected, selSz);

        if (maxUtil > 0 && u > maxUtil) return ESat.FALSE;

        if (allInst) {
            return (u >= minUtil) ? ESat.TRUE : ESat.FALSE;
        }

        long ru = remainingUtilityBound(proj);
        if (u + ru < minUtil) return ESat.FALSE;
        return ESat.UNDEFINED;
    }

    private boolean allInstantiatedNow() {
        for (BoolVar v : vars) {
            if (!v.isInstantiated()) return false;
        }
        return true;
    }

    private long exactUtilityOnProjection(TransactionProjection proj, int[] sel, int selSz) {
        if (selSz == 0 || proj.size() == 0) return 0L;
        long sum = 0L;
        for (int k = 0; k < selSz; k++) {
            sum += utilityOfItemOnProjection(proj, sel[k]);
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

    private long remainingUtilityBound(TransactionProjection proj) {
        long ru = 0L;
        for (int idx = 0; idx < proj.size(); idx++) {
            int t = proj.tidAt(idx);
            int[] items = data.transactionItems[t];
            int[] utils = data.transactionUtilities[t];
            for (int k = 0; k < items.length; k++) {
                int it = items[k];
                if (!vars[it].isInstantiated()) {
                    ru += utils[k];
                }
            }
        }
        return ru;
    }

    private TransactionProjection newProjection() {
        if (projectionBackend == ProjectionBackend.BITSET) {
            return new StateBitsetProjectedDB(model.getEnvironment(), data.transactionCount);
        }
        return new ProjectedDB(data.transactionCount);
    }
}
