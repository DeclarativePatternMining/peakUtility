package cp26.mining.patterns.search.strategy.selectors.variables;

import org.chocosolver.solver.search.strategy.selectors.variables.VariableSelector;
import org.chocosolver.solver.variables.IntVar;

/**
 * Selects the free item variable with the highest TWU.
 */
public class TwuDesc implements VariableSelector<IntVar> {

    private final int[] itemTWU;

    public TwuDesc(int[] itemTWU) {
        this.itemTWU = itemTWU;
    }

    @Override
    public IntVar getVariable(IntVar[] variables) {
        IntVar best = null;
        int bestTWU = Integer.MIN_VALUE;
        for (int i = 0; i < variables.length; i++) {
            if (variables[i].isInstantiated()) {
                continue;
            }
            int twu = itemTWU[i];
            if (best == null || twu > bestTWU) {
                best = variables[i];
                bestTWU = twu;
            }
        }
        return best;
    }
}
