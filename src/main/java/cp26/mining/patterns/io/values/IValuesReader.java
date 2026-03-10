/*
 * This file is part of cp26:peakutility (https://gitlab.com/chaver/choco-mining)
 *
 * Copyright (c) 2026, IMT Atlantique
 *
 * Licensed under the MIT license.
 *
 * See LICENSE file in the project root for full license information.
 */
package cp26.mining.patterns.io.values;

import java.io.IOException;

/**
 * Read the values of items
 */
public interface IValuesReader {

    int[][] readValueFiles() throws IOException;
}
