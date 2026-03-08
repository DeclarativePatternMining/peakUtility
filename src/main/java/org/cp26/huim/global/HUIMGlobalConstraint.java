package org.cp26.huim.global;

import org.chocosolver.solver.constraints.Constraint;
import org.chocosolver.solver.variables.BoolVar;
import org.cp26.huim.parser.HighUtilityDataset;

public final class HUIMGlobalConstraint extends Constraint {

    public HUIMGlobalConstraint(BoolVar[] x, HighUtilityDataset data, int minUtil) {
        this(x, data, minUtil, -1, ProjectionBackend.ARRAY);
    }

    // maxUtil <= 0 means "no max utility bound"
    public HUIMGlobalConstraint(BoolVar[] x, HighUtilityDataset data, int minUtil, int maxUtil) {
        this(x, data, minUtil, maxUtil, ProjectionBackend.ARRAY);
    }

    public HUIMGlobalConstraint(BoolVar[] x, HighUtilityDataset data, int minUtil, ProjectionBackend projectionBackend) {
        this(x, data, minUtil, -1, projectionBackend);
    }

    public HUIMGlobalConstraint(BoolVar[] x, HighUtilityDataset data, int minUtil, int maxUtil, ProjectionBackend projectionBackend) {
        super("HUIM_GLOBAL", new PropHUIMGlobal(x, data, minUtil, maxUtil, projectionBackend));
    }
}
