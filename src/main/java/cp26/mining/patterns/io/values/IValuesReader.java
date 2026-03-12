package cp26.mining.patterns.io.values;

import java.io.IOException;

/**
 * Read the values of items
 */
public interface IValuesReader {

    int[][] readValueFiles() throws IOException;
}
