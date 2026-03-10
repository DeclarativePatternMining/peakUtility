package org.cp26.huim.examples;

import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solver;
import org.chocosolver.solver.search.strategy.Search;
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMax;
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMin;
import org.chocosolver.solver.search.strategy.selectors.values.IntValueSelector;
import org.chocosolver.solver.search.strategy.selectors.variables.DomOverWDeg;
import org.chocosolver.solver.search.strategy.selectors.variables.FirstFail;
import org.chocosolver.solver.search.strategy.selectors.variables.InputOrder;
import org.chocosolver.solver.search.strategy.selectors.variables.VariableSelector;
import org.chocosolver.solver.variables.BoolVar;
import org.chocosolver.solver.variables.IntVar;
import org.cp26.huim.global.ProjectionBackend;
import org.cp26.huim.parser.HighUtilityDataset;
import org.cp26.huim.global.PeakUtilityConstraint;
import org.cp26.huim.global.PropPeakUtility;
import org.cp26.huim.heuristics.ItemUtilityAsc;
import org.cp26.huim.heuristics.ItemUtilityDesc;
import org.cp26.huim.heuristics.TwuAsc;
import org.cp26.huim.heuristics.TwuDesc;


/**
 * Test class for PeakUtility constraint (UPI = Utility-Peak Itemsets).
 * Combines HUIBounds propagation with peak utility checks (Rules 4, 5, 6).
 *
 * You can launch directly from IntelliJ with Ctrl+Shift+F10.
 */
public final class PeakUtilityExample {

    public static void main(String[] args) throws Exception {
        // ==================== CONFIGURATION ====================
        // Modify these parameters according to your needs

       // String datasetPath = "data/test_dataset/tiny_manual_spmf.txt";     // Path to the dataset
        String datasetPath = "datasets/Data_SPMF/chess_utility_spmf.txt";     // Path to the dataset
      //  String datasetPath = "data/foodmart.txt";     // Path to the dataset
        int minUtil = 600000;                            // Minimum utility threshold

        // Optional constraints
        String includeItems = "";                      // ex: "1,5,10" or empty
        String excludeItems = "";                      // ex: "2,3" or empty
        int minSize = 0;                               // Minimum itemset size (0 = no limit)
        int maxSize = -1;                              // Maximum itemset size (-1 = no limit)

        // Transaction filters
        int tuMin = Integer.MIN_VALUE;                 // Filter by minimum transactionUtility
        int tuMax = Integer.MAX_VALUE;                 // Filter by maximum transactionUtility

        // Display and save options
        boolean verbose = false;                        // Display details in console
        boolean saveToFile = false;                     // Save itemsets to file
        String outputFilePath = "peak_utility_results.txt";  // Output file path

        // PeakUtility specific options
        PropPeakUtility.Mode mode = PropPeakUtility.Mode.HUI;  // UPI = Peak Utility mode
        int depthK = 3;                                 // Depth threshold for Rule 6 pruning
        ProjectionBackend projectionBackend = ProjectionBackend.ARRAY; // ARRAY or BITSET
        String varSel = "utilAsc";                        // input|firstfail|domwdeg|twuAsc|twuDesc|utilAsc|utilDesc
        String valSel = "max";                          // min|max

        // Timeout configuration
        long timeoutMs = 900000;                         // Timeout in milliseconds (60 sec = 60000)
                                                         // Set to 0 or negative for no timeout

        // ========================================================

        System.out.println("=== PeakUtilityExample ===");
        System.out.println("Mode: " + mode);
        System.out.println("Dataset: " + datasetPath);
        System.out.println("MinUtil: " + minUtil);
        System.out.println("DepthK: " + depthK);
        System.out.println("Projection backend: " + projectionBackend);
        System.out.println("Var selector: " + varSel);
        System.out.println("Val selector: " + valSel);
        System.out.println();

        // Load data
        if (verbose) System.out.println("Loading data...");
        HighUtilityDataset data = HighUtilityDataset.loadFromFile(datasetPath);
        if (verbose) System.out.println("✓ Data loaded");
        if (verbose) System.out.println("  - Number of transactions: " + data.transactionCount);
        if (verbose) System.out.println("  - Number of items: " + data.itemCount);
        if (verbose) System.out.println();

        // Apply transaction filters
        for (int t = 0; t < data.transactionCount; t++) {
            int tu = data.transactionUtility[t];
        }

        // Create CP model
        if (verbose) System.out.println("Creating CP model...");
        Model model = new Model("PEAK_UTILITY_TEST");
        BoolVar[] x = model.boolVarArray("X", data.itemCount);

        // Post PeakUtility constraint
        if (verbose) System.out.println("✓ Posting PeakUtilityConstraint...");
        if (mode == PropPeakUtility.Mode.UPI) {
            model.post(new PeakUtilityConstraint(x, data, minUtil, depthK, projectionBackend));
        } else {
            model.post(new PeakUtilityConstraint(x, data, minUtil, projectionBackend));
        }

        // Include/exclude constraints
        if (!includeItems.isEmpty()) {
            int[] includeIds = parseCsvIds(includeItems);
            for (int id : includeIds) {
                Integer idx = data.originalItemIdToIndex.get(id);
                if (idx != null) {
                    model.arithm(x[idx], "=", 1).post();
                    if (verbose) System.out.println("  - Item " + id + " INCLUDED");
                }
            }
        }

        if (!excludeItems.isEmpty()) {
            int[] excludeIds = parseCsvIds(excludeItems);
            for (int id : excludeIds) {
                Integer idx = data.originalItemIdToIndex.get(id);
                if (idx != null) {
                    model.arithm(x[idx], "=", 0).post();
                    if (verbose) System.out.println("  - Item " + id + " EXCLUDED");
                }
            }
        }

        // Cardinality constraints
        if (minSize > 0 || maxSize > 0) {
            IntVar card = model.intVar("card", 0, data.itemCount);
            model.sum(x, "=", card).post();
            if (minSize > 0) {
                model.arithm(card, ">=", minSize).post();
                if (verbose) System.out.println("  - Minimum size: " + minSize);
            }
            if (maxSize > 0) {
                model.arithm(card, "<=", maxSize).post();
                if (verbose) System.out.println("  - Maximum size: " + maxSize);
            }
        }

        System.out.println();
        if (verbose) System.out.println("Starting resolution...");
        if (verbose) System.out.println("(Press Ctrl+C to stop)");
        System.out.println();

        // Configure solver
        Solver solver = model.getSolver();
        solver.setSearch(buildSearch(model, x, varSel, valSel, computeItemTwu(data), computeItemUtility(data)));

        // Set timeout if configured
        if (timeoutMs > 0) {
            solver.limitTime(timeoutMs);
        }

        // Prepare file output if needed
        java.io.FileWriter fileWriter = null;
        if (saveToFile) {
            fileWriter = new java.io.FileWriter(outputFilePath);
            fileWriter.write("=== Peak Utility Results ===\n");
            fileWriter.write("Mode: " + mode + "\n");
            fileWriter.write("Dataset: " + datasetPath + "\n");
            fileWriter.write("MinUtil: " + minUtil + "\n");
            fileWriter.write("DepthK: " + depthK + "\n");
            fileWriter.write("\n");
            fileWriter.flush();
        }

        // Solve
        long count = 0;
        long start = System.nanoTime();
        long peakMem = usedMemBytes();

        while (solver.solve()) {
            count++;
            long cur = usedMemBytes();
            if (cur > peakMem) peakMem = cur;

            // Display and save solutions
            if (saveToFile || verbose) {
                StringBuilder sb = new StringBuilder();
                sb.append("Solution ").append(count).append(": {");
                int size = 0;
                for (int i = 0; i < data.itemCount; i++) {
                    if (x[i].getValue() == 1) {
                        if (size > 0) sb.append(", ");
                        int itemId = data.itemIndexToOriginalId[i];
                        sb.append(itemId);
                        size++;
                    }
                }
                sb.append("} - Size: ").append(size);

                // Calculate utility
                int totalUtil = 0;
                for (int t = 0; t < data.transactionCount; t++) {
                    int[] items = data.transactionItems[t];
                    int[] utils = data.transactionUtilities[t];
                    int txUtil = 0;
                    for (int k = 0; k < items.length; k++) {
                        if (x[items[k]].getValue() == 1) {
                            txUtil += utils[k];
                        }
                    }
                    totalUtil += txUtil;
                }
                sb.append(" - Utility: ").append(totalUtil);

                String result = sb.toString();

                if (verbose) {
                    System.out.println(result);
                }
                if (saveToFile && fileWriter != null) {
                    fileWriter.write(result + "\n");
                    fileWriter.flush();
                }
            }

            // Progress info every 1000 solutions
            if (verbose && count % 1000 == 0 && count > 0) {
                System.out.println("  ... " + count + " solutions found");
            }
        }

        if (fileWriter != null) {
            fileWriter.close();
        }

        long ms = (System.nanoTime() - start) / 1_000_000L;
        double peakMB = peakMem / (1024.0 * 1024.0);

        // Results
        System.out.println();
        System.out.println("=== RESULTS ===");
        System.out.println("Peak Utility Itemsets found: " + count);
        System.out.println("Execution time: " + ms + " ms");
        System.out.println("Peak memory used: " + String.format("%.2f", peakMB) + " MB");
        if (solver.isStopCriterionMet()) {
            System.out.println("Stopped early due to timeout/limit.");
        }
        if (saveToFile) {
            System.out.println("Results saved to: " + outputFilePath);
        }
        System.out.println();
        System.out.println("Resolution completed ✓");
    }

    /**
     * Parses a comma-separated string of values into an integer array.
     */
    private static int[] parseCsvIds(String s) {
        if (s == null || s.trim().isEmpty()) return new int[0];
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        int k = 0;
        for (String p : parts) {
            String q = p.trim();
            if (!q.isEmpty()) out[k++] = Integer.parseInt(q);
        }
        return (k == out.length) ? out : java.util.Arrays.copyOf(out, k);
    }

    /**
     * Returns used memory in bytes.
     */
    private static long usedMemBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static int[] computeItemTwu(HighUtilityDataset data) {
        int[] twu = new int[data.itemCount];
        for (int t = 0; t < data.transactionCount; t++) {
            int tu = data.transactionUtility[t];
            int[] items = data.transactionItems[t];
            for (int item : items) {
                twu[item] += tu;
            }
        }
        return twu;
    }

    private static int[] computeItemUtility(HighUtilityDataset data) {
        int[] utility = new int[data.itemCount];
        for (int i = 0; i < data.itemCount; i++) {
            int sum = 0;
            int[] values = data.itemUtilities[i];
            for (int v : values) {
                sum += v;
            }
            utility[i] = sum;
        }
        return utility;
    }

    private static org.chocosolver.solver.search.strategy.strategy.AbstractStrategy<IntVar> buildSearch(
            Model model, BoolVar[] x, String varSel, String valSel, int[] itemTwu, int[] itemUtility) {
        VariableSelector<IntVar> variableSelector;
        switch (varSel.toLowerCase(java.util.Locale.ROOT)) {
            case "firstfail":
                variableSelector = new FirstFail(model);
                break;
            case "domwdeg":
                variableSelector = new DomOverWDeg<>(x, 0L);
                break;
            case "twuasc":
                variableSelector = new TwuAsc(itemTwu);
                break;
            case "twudesc":
                variableSelector = new TwuDesc(itemTwu);
                break;
            case "utilasc":
                variableSelector = new ItemUtilityAsc(itemUtility);
                break;
            case "utildesc":
                variableSelector = new ItemUtilityDesc(itemUtility);
                break;
            case "input":
            default:
                variableSelector = new InputOrder<>(model);
                break;
        }

        IntValueSelector valueSelector =
                "min".equalsIgnoreCase(valSel) ? new IntDomainMin() : new IntDomainMax();
        return Search.intVarSearch(variableSelector, valueSelector, x);
    }
}
