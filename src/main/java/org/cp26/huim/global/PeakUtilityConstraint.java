package org.cp26.huim.global;

import org.chocosolver.solver.constraints.Constraint;
import org.chocosolver.solver.variables.BoolVar;
import org.cp26.huim.parser.HighUtilityDataset;

/**
 * Constraint wrapper for PeakUtility mining.
 * Supports two modes:
 * - HUI: High-Utility Itemsets (basic utility bounds)
 * - UPI: Utility-Peak Itemsets (with peak utility checking)
 */
public final class PeakUtilityConstraint extends Constraint {

    /**
     * Creates a PeakUtility constraint in HUI mode (basic utility bounds).
     *
     * @param x Boolean variables representing the itemset
     * @param data HighUtilityDataset containing dataset information
     * @param minUtil Minimum utility threshold
     */
    public PeakUtilityConstraint(BoolVar[] x, HighUtilityDataset data, int minUtil) {
        this(x, data, minUtil, ProjectionBackend.ARRAY);
    }

    public PeakUtilityConstraint(BoolVar[] x, HighUtilityDataset data, int minUtil, ProjectionBackend projectionBackend) {
        super("PEAK_UTILITY_HUI", new PropPeakUtility(x, data, minUtil, -1, PropPeakUtility.Mode.HUI, projectionBackend));
    }

    /**
     * Creates a PeakUtility constraint in UPI mode (with peak utility checking).
     *
     * @param x Boolean variables representing the itemset
     * @param data HighUtilityDataset containing dataset information
     * @param minUtil Minimum utility threshold
     * @param depthK Depth threshold for Rule 6 pruning
     */
    public PeakUtilityConstraint(BoolVar[] x, HighUtilityDataset data, int minUtil, int depthK) {
        this(x, data, minUtil, depthK, ProjectionBackend.ARRAY);
    }

    public PeakUtilityConstraint(BoolVar[] x, HighUtilityDataset data, int minUtil, int depthK, ProjectionBackend projectionBackend) {
        super("PEAK_UTILITY_UPI", new PropPeakUtility(x, data, minUtil, depthK, PropPeakUtility.Mode.UPI, projectionBackend));
    }
}
