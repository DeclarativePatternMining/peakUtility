package cp26.mining.patterns.search.strategy.selectors.variables;

import org.chocosolver.solver.search.strategy.selectors.variables.VariableSelector;
import org.chocosolver.solver.variables.IntVar;

/**
 * Selects the free item variable with the lowest global item utility.
 */
public class ItemUtilityAsc implements VariableSelector<IntVar> {

    private final int[] itemUtility;

    public ItemUtilityAsc(int[] itemUtility) {
        this.itemUtility = itemUtility;
    }

    @Override
    public IntVar getVariable(IntVar[] variables) {
        IntVar best = null;
        int bestUtility = Integer.MAX_VALUE;
        for (int i = 0; i < variables.length; i++) {
            if (variables[i].isInstantiated()) {
                continue;
            }
            int util = itemUtility[i];
            if (best == null || util < bestUtility) {
                best = variables[i];
                bestUtility = util;
            }
        }
        return best;
    }
}
