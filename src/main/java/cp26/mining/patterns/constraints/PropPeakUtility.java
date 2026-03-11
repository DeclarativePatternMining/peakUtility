
package cp26.mining.patterns.constraints;

import java.util.BitSet;

import org.chocosolver.solver.constraints.Propagator;
import org.chocosolver.solver.constraints.PropagatorPriority;
import org.chocosolver.solver.exception.ContradictionException;
import org.chocosolver.solver.variables.BoolVar;
import org.chocosolver.util.ESat;

import cp26.mining.patterns.util.Custom_Element;
import cp26.mining.patterns.util.UtilityList_0;

public class PropPeakUtility extends Propagator<BoolVar> {

    public enum Mode {
        UPI,
        HUI
    }

    private final Mode mode;

    private final int theta;
    private final int k;
    private final int nTrans;

    private final BitSet[] itemCovers;
    private final int[][] iutils;
    private final int[] transactionUtilities;
    private final Custom_Element[][] elements;

    private final int[] ones;
    private final int[] zeros;
    private final int[] free;

    private int onesSize;
    private int zerosSize;
    private int freeSize;

    private final BitSet currentBS;
    private final BitSet tmpBS;

    public PropPeakUtility(
            BoolVar[] vars,
            int theta,
            int k,
            int[] transactionUtilities,
            UtilityList_0[] itemULs,
            int nTrans,
            Mode mode) {

        super(vars, PropagatorPriority.LINEAR, false);

        this.mode = mode;
        this.theta = theta;
        this.k = k;
        this.transactionUtilities = transactionUtilities;
        this.nTrans = nTrans;

        int nItems = vars.length;

        itemCovers = new BitSet[nItems];
        iutils = new int[nItems][nTrans];
        elements = new Custom_Element[nItems][];

        ones = new int[nItems];
        zeros = new int[nItems];
        free = new int[nItems];

        currentBS = new BitSet(nTrans);
        tmpBS = new BitSet(nTrans);

        for (int i = 0; i < nItems; i++) {

            itemCovers[i] = new BitSet(nTrans);
            elements[i] = itemULs[i].elements.toArray(new Custom_Element[0]);

            for (Custom_Element e : elements[i]) {
                itemCovers[i].set(e.tid);
                iutils[i][e.tid] = e.iutil;
            }
        }
    }

    @Override
    public void propagate(int evtmask) throws ContradictionException {

        onesSize = 0;
        zerosSize = 0;
        freeSize = 0;

        for (int i = 0; i < vars.length; i++) {

            if (vars[i].isInstantiatedTo(1))
                ones[onesSize++] = i;
            else if (vars[i].isInstantiatedTo(0))
                zeros[zerosSize++] = i;
            else
                free[freeSize++] = i;
        }

        // ==================================================
        // Phase 1: High-utility pruning (Rules 1--5)
        // ==================================================

        if (onesSize == 0)
            return;

        // --------------------------------------------------
        // Cover-based feasibility (Rules 1--2)
        // --------------------------------------------------

        // Rule 1: cover(x^{-1}(1)) must be non-empty
        currentBS.clear();
        currentBS.or(itemCovers[ones[0]]);

        for (int i = 1; i < onesSize; i++) {

            currentBS.and(itemCovers[ones[i]]);

            if (currentBS.isEmpty()) {
                fails();
                return;
            }
        }

        // Rule 2: prune free items whose extension makes cover empty
        for (int i = 0; i < freeSize; i++) {

            int item = free[i];

            tmpBS.clear();
            tmpBS.or(currentBS);
            tmpBS.and(itemCovers[item]);

            if (tmpBS.isEmpty()) {
                vars[item].instantiateTo(0, this);
            }
        }

        // --------------------------------------------------
        // Utility-based pruning (Rules 3--5)
        // --------------------------------------------------

        // Utility U(S)

        long uP = 0;
        int tid = currentBS.nextSetBit(0);

        while (tid >= 0) {

            for (int i = 0; i < onesSize; i++)
                uP += iutils[ones[i]][tid];

            tid = currentBS.nextSetBit(tid + 1);
        }

        if (mode == Mode.HUI || mode == Mode.UPI ) {

            // Rule 3: TWU(x^{-1}(1)) >= theta
            long twu = 0;

            tid = currentBS.nextSetBit(0);
            while (tid >= 0) {

                twu += transactionUtilities[tid];
                tid = currentBS.nextSetBit(tid + 1);
            }
            
            if (twu < theta) {
                fails();
                return;
            }

            // Rule 4: UB = u(P) + ru(P, free)
            long ru = 0;

            for (int i = 0; i < freeSize; i++) {

                int item = free[i];

                for (Custom_Element e : elements[item]) {

                    if (currentBS.get(e.tid))
                        ru += e.iutil;
                }
            }

            if (uP + ru < theta) {
                fails();
                return;
            }

            // Rule 5: UB_i for each free item
            for (int k2 = 0; k2 < freeSize; k2++) {

                int item = free[k2];
                
                tmpBS.clear();
                tmpBS.or(currentBS);
                tmpBS.and(itemCovers[item]);

                if (tmpBS.isEmpty()) {
                    continue;
                }

                long utilItem = 0;

                for (Custom_Element e : elements[item]) {
                    if (tmpBS.get(e.tid))
                        utilItem += e.iutil;
                }

                long ruExt = ru - utilItem;

                if (uP + utilItem + ruExt < theta) {
                    vars[item].instantiateTo(0, this);
                    continue;
                }

            }

            // IMPORTANT: enforce constraint when fully assigned
            if (freeSize == 0 && uP < theta) {
                fails();
            } 
        }

        // ==================================================
        // Phase 2: Peak condition enforcement (Rules 6--8)
        // ==================================================

        if (mode == Mode.UPI ) {

            onesSize = 0;
            zerosSize = 0;
            freeSize = 0;

            for (int i = 0; i < vars.length; i++) {

                if (vars[i].isInstantiatedTo(1))
                    ones[onesSize++] = i;
                else if (vars[i].isInstantiatedTo(0))
                    zeros[zerosSize++] = i;
                else
                    free[freeSize++] = i;
            }

            if (onesSize == 0)
                return;

            // -------------------------------------------------
            // Compute cover(P)
            // -------------------------------------------------

            currentBS.clear();
            currentBS.or(itemCovers[ones[0]]);

            for (int i = 1; i < onesSize; i++)
            	currentBS.and(itemCovers[ones[i]]);



            // ===============================================
            // Rules 6--7: Verification at complete assignment
            // ===============================================

            if (freeSize == 0) {

                // subset dominance
                for (int idx = 0; idx < onesSize; idx++) {

                    int removed = ones[idx];

                    tmpBS.clear();
                    boolean first = true;

                    for (int i = 0; i < onesSize; i++) {

                        int it = ones[i];
                        if (it == removed) continue;

                        if (first) {
                            tmpBS.or(itemCovers[it]);
                            first = false;
                        } else {
                            tmpBS.and(itemCovers[it]);
                        }
                    }

                    long uSub = 0;

                    tid = tmpBS.nextSetBit(0);
                    while (tid >= 0) {

                        for (int i = 0; i < onesSize; i++)
                            if (ones[i] != removed)
                                uSub += iutils[ones[i]][tid];

                        tid = tmpBS.nextSetBit(tid + 1);
                    }

                    if (uSub >= uP) {
                        fails();
                        return;
                    }
                }

                // superset dominance
                for (int i = 0; i < zerosSize; i++) {

                    int item = zeros[i];

                    tmpBS.clear();
                    tmpBS.or(currentBS);
                    tmpBS.and(itemCovers[item]);

                    if (!tmpBS.isEmpty()) {

                        long uSup = 0;

                        tid = tmpBS.nextSetBit(0);
                        while (tid >= 0) {

                            for (int j = 0; j < onesSize; j++)
                                uSup += iutils[ones[j]][tid];

                            uSup += iutils[item][tid];

                            tid = tmpBS.nextSetBit(tid + 1);
                        }

                        if (uSup >= uP) {
                            fails();
                            return;
                        }
                    }
                }

                return;
            }

            // ==================================================
            // Rule 8: Depth-k exhaustive subset pruning
            // ==================================================

            if (k > 0 && freeSize == k) {

                boolean allSubsetsLower = true;

                for (int idx = 0; idx < onesSize; idx++) {

                    int removed = ones[idx];

                    tmpBS.clear();
                    boolean first = true;

                    for (int i = 0; i < onesSize; i++) {

                        int it = ones[i];
                        if (it == removed) continue;

                        if (first) {
                            tmpBS.or(itemCovers[it]);
                            first = false;
                        } else {
                            tmpBS.and(itemCovers[it]);
                        }
                    }

                    long uSub = 0;

                    tid = tmpBS.nextSetBit(0);
                    while (tid >= 0) {

                        for (int i = 0; i < onesSize; i++)
                            if (ones[i] != removed)
                                uSub += iutils[ones[i]][tid];

                        tid = tmpBS.nextSetBit(tid + 1);
                    }

                    if (uSub >= uP) {
                        allSubsetsLower = false;
                        break;
                    }
                }

                if (allSubsetsLower) {

                    boolean allSupersetsLower = true;

                    for (int i = 0; i < freeSize; i++) {

                        int item = free[i];

                        long remainingUtility = 0;

                        tid = currentBS.nextSetBit(0);

                        while (tid >= 0) {

                            if (itemCovers[item].get(tid))
                                remainingUtility += iutils[item][tid];

                            tid = currentBS.nextSetBit(tid + 1);
                        }

                        long upperBound = uP + remainingUtility;

                        if (upperBound >= uP) {
                            allSupersetsLower = false;
                            break;
                        }
                    }

                    if (allSupersetsLower) {

                        for (int i = 0; i < freeSize; i++) {

                            int item = free[i];

                            if (vars[item].contains(1))
                                vars[item].removeValue(1, this);
                        }
                    }
                }
            }
        }
    }

    @Override
    public ESat isEntailed() {
    	return ESat.UNDEFINED;
    }
}
