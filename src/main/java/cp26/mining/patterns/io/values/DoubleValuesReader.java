package cp26.mining.patterns.io.values;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Read values of items between [0, 1], each value is multiplied by 100 to be converted to an integer
 *
 */
public class DoubleValuesReader implements IValuesReader {

    private int nbValueMeasures;
    private String extension;
    private String dataPath;

    public DoubleValuesReader(int nbValueMeasures, String extension, String dataPath) {
        this.nbValueMeasures = nbValueMeasures;
        this.extension = extension;
        this.dataPath = dataPath;
    }

    /**
     * Read files which contain values of items
     * For instance, if we want to read three files of values of zoo.txt, the files
     *   zoo.val0, zoo.val1 and zoo.val2 will be read
     * @throws IOException if a file doesn't exist
     */
    @Override
    public int[][] readValueFiles() throws IOException {
        int[][] values = new int[nbValueMeasures][];
        String valuePath = dataPath.substring(0, dataPath.length() - (extension.length() + 1)) + ".val";
        for (int i = 0; i < nbValueMeasures; i++) {
            values[i] = readValueFile(valuePath + i).stream().mapToInt(Integer::intValue).toArray();
        }
        return values;
    }

    private List<Integer> readValueFile(String path) throws IOException{
        List<Integer> valueList = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                valueList.add(new BigDecimal(line).multiply(new BigDecimal("100")).intValue());
            }
        }
        return valueList;
    }
}
