/*
 * This file is part of cp26:peakutility (https://gitlab.com/chaver/choco-mining)
 *
 * Copyright (c) 2026, IMT Atlantique
 *
 * Licensed under the MIT license.
 *
 * See LICENSE file in the project root for full license information.
 */
package cp26.mining.patterns.io;

import cp26.mining.patterns.io.values.DoubleValuesReader;
import cp26.mining.patterns.io.values.IValuesReader;

import java.io.IOException;

/**
 * Class used to read data :
 * - transactions (i.e. sets of items)
 * - value of items
 */
public abstract class DataReader {

    /** Path of the file which contains the transactions data */
    protected String dataPath;
    /** Extension of the file which contains the transactions data (e.g. txt) */
    protected String extension;
    /** number of measures which require a values file (e.g. mean, max, min) */
    protected int nbValueMeasures;
    /** number of transactions in the dataset */
    protected int nbTransactions;
    /** number of items in the dataset */
    protected int nbItems;
    /** TRUE if classes are ignored (i.e. database.nbClass = 0) */
    protected boolean noClasses;
    /** Used to read the values of each item */
    protected IValuesReader valuesReader;

    public DataReader(String dataPath, int nbValueMeasures) {
        this.dataPath = dataPath;
        this.nbValueMeasures = nbValueMeasures;
        String[] pathSplit = dataPath.split("\\.");
        this.extension = pathSplit[pathSplit.length - 1];
        valuesReader = new DoubleValuesReader(nbValueMeasures, extension, dataPath);
    }

    public DataReader(String dataPath, int nbValueMeasures, boolean noClasses) {
        this(dataPath, nbValueMeasures);
        this.noClasses = noClasses;
    }

    public DataReader(String dataPath) {
        this(dataPath, 0, true);
    }

    /**
     * Read files which contain transactions and values of items
     * @return a database object with the data
     */
    public abstract TransactionalDatabase read() throws IOException;
    public abstract TransactionalDatabase read_hui() throws IOException;


    /**
     * Read files which contain values of items
     * For instance, if we want to read three files of values of zoo.txt, the files
     *   zoo.val0, zoo.val1 and zoo.val2 will be read
     * @throws IOException if a file doesn't exist
     */
    protected int[][] readValueFiles() throws IOException {
        return valuesReader.readValueFiles();
    }

    protected boolean skipLine(String line) {
        return line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '%' || line.charAt(0) == '@';
    }
}
