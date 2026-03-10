/*
 * This file is part of io.gitlab.chaver:choco-mining (https://gitlab.com/chaver/choco-mining)
 *
 * Copyright (c) 2026, IMT Atlantique
 *
 * Licensed under the MIT license.
 *
 * See LICENSE file in the project root for full license information.
 */
package org.cp26.huim.heuristics;

import org.chocosolver.solver.search.strategy.selectors.variables.VariableSelector;
import org.chocosolver.solver.variables.IntVar;

/**
 * Selects the free item variable with the highest global item utility.
 */
public class ItemUtilityDesc implements VariableSelector<IntVar> {

    private final int[] itemUtility;

    public ItemUtilityDesc(int[] itemUtility) {
        this.itemUtility = itemUtility;
    }

    @Override
    public IntVar getVariable(IntVar[] variables) {
        IntVar best = null;
        int bestUtility = Integer.MIN_VALUE;
        for (int i = 0; i < variables.length; i++) {
            if (variables[i].isInstantiated()) {
                continue;
            }
            int util = itemUtility[i];
            if (best == null || util > bestUtility) {
                best = variables[i];
                bestUtility = util;
            }
        }
        return best;
    }
}
