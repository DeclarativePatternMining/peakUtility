package cp26.mining.examples;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solver;
import org.chocosolver.solver.search.measure.IMeasures;
import org.chocosolver.solver.search.strategy.Search;
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMax;
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMin;
import org.chocosolver.solver.constraints.Constraint;
import org.chocosolver.solver.search.strategy.selectors.values.IntValueSelector;
import org.chocosolver.solver.search.strategy.selectors.variables.DomOverWDeg;
import org.chocosolver.solver.search.strategy.selectors.variables.FirstFail;
import org.chocosolver.solver.search.strategy.selectors.variables.InputOrder;
import org.chocosolver.solver.search.strategy.selectors.variables.VariableSelector;
import org.chocosolver.solver.variables.BoolVar;
import org.chocosolver.solver.variables.IntVar;

import cp26.mining.patterns.constraints.PropPeakUtility;
import org.chocosolver.solver.constraints.Constraint;
import cp26.mining.patterns.io.HUIReader;
import cp26.mining.patterns.io.TransactionalDatabase;
import cp26.mining.patterns.search.strategy.selectors.variables.ItemUtilityAsc;
import cp26.mining.patterns.search.strategy.selectors.variables.ItemUtilityDesc;
import cp26.mining.patterns.search.strategy.selectors.variables.TwuAsc;
import cp26.mining.patterns.search.strategy.selectors.variables.TwuDesc;
import cp26.mining.patterns.util.Custom_Element;
import cp26.mining.patterns.util.UtilityList_0;

/**
 * Unified mining engine for HUI and UPI using the combined PropPeakUtility constraint.
 * 
 * <h2>Overview</h2>
 * This class demonstrates the use of the unified PropPeakUtility constraint that combines
 * HUIBounds and PeakUtility_v0 propagation. It supports two modes:
 * - HUI (High-Utility Itemsets): Only HUIBounds constraint
 * - UPI (Utility-Peak Itemsets): Both HUIBounds and PeakUtility_v0 constraints
 *
 * <h2>Usage Example</h2>
 * <pre>
 * // HUI mode
 * Config config = new Config("datasets/dataset.txt", "output/results_hui.txt")
 *     .withMinUtilAbsolute(10000)
 *     .withMode(PropPeakUtility.Mode.HUI)
 *     .withVerbose(true);
 *
 * // or UPI mode
 * Config config = new Config("datasets/dataset.txt", "output/results_upi.txt")
 *     .withMinUtilAbsolute(10000)
 *     .withMode(PropPeakUtility.Mode.UPI)
 *     .withDepthK(2)
 *     .withVerbose(true);
 *
 * PeakUtility miner = new PeakUtility(config);
 * MiningResults results = miner.run();
 * results.printStats();
 * </pre>
 *
 * @version 1.0
 * @since 2026
 * @see PropPeakUtility
 * @see PropPeakUtility.Mode
 */
public class PeakUtility {

    public enum VarHeuristic {
        ITEM_UTIL_ASC,
        ITEM_UTIL_DESC,
        TWU_ASC,
        TWU_DESC,
        CHOCO_INPUT_ORDER,
        CHOCO_FIRST_FAIL,
        CHOCO_DOM_OVER_WDEG,
        CHOCO_DEFAULT
    }

    public enum ValHeuristic {
        MIN,
        MAX
    }

    // =========================================================================
    // CONFIGURATION CLASS
    // =========================================================================

    /**
     * Configuration class with builder pattern for unified PeakUtility mining.
     */
    public static class Config {
        public String inputFile;
        public String outputFile;
        public int minUtilAbsolute = 10000;
        public int depthK = 2;
        public PropPeakUtility.Mode mode = PropPeakUtility.Mode.HUI;
        public boolean writeToFile = true;
        public boolean verbose = true;
        public boolean printDetailedStats = true;
        public boolean storePatternsInMemory = true;
        public VarHeuristic varHeuristic = VarHeuristic.ITEM_UTIL_ASC;
        public ValHeuristic valHeuristic = ValHeuristic.MIN;
        public long timeoutMs = 0L;
        public String rulesMask = "11111111";

        public Config(String inputFile, String outputFile) {
            this.inputFile = inputFile;
            this.outputFile = outputFile;
        }

        public Config withMinUtilAbsolute(int minutil) {
            this.minUtilAbsolute = minutil;
            return this;
        }

        public Config withDepthK(int k) {
            this.depthK = k;
            return this;
        }

        public Config withMode(PropPeakUtility.Mode m) {
            this.mode = m;
            return this;
        }

        public Config withFileOutput(boolean write) {
            this.writeToFile = write;
            return this;
        }

        public Config withVerbose(boolean v) {
            this.verbose = v;
            return this;
        }

        public Config withDetailedStats(boolean stats) {
            this.printDetailedStats = stats;
            return this;
        }

        public Config withStorePatternsInMemory(boolean store) {
            this.storePatternsInMemory = store;
            return this;
        }

        public Config withVarHeuristic(VarHeuristic heuristic) {
            this.varHeuristic = heuristic;
            return this;
        }

        public Config withValHeuristic(ValHeuristic heuristic) {
            this.valHeuristic = heuristic;
            return this;
        }

        public Config withTimeoutMs(long timeoutMs) {
            if (timeoutMs < 0) {
                throw new IllegalArgumentException("timeoutMs must be >= 0");
            }
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Config withRulesMask(String rulesMask) {
            if (rulesMask == null || !rulesMask.matches("[01]{8}")) {
                throw new IllegalArgumentException("rulesMask must be 8 chars of 0/1");
            }
            this.rulesMask = rulesMask;
            return this;
        }
    }

    // =========================================================================
    // PATTERN & RESULTS CLASSES
    // =========================================================================

    private static class Pattern {
        long utility;
        int[] itemArray;

        Pattern(long utility, int[] itemArray) {
            this.utility = utility;
            this.itemArray = itemArray;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < itemArray.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(itemArray[i]);
            }
            sb.append(" #UTIL: ").append(utility);
            return sb.toString();
        }
    }

    public static class MiningResults {
        public List<Pattern> patterns;
        public int patternCount;
        public long startTime;
        public long endTime;
        public int minUtilityThreshold;
        public boolean timeoutReached;
        public long timeoutMs;
        public long fixpointCount;
        public long nodeCount;
        public long failCount;
        public long backtrackCount;
        public long restartCount;
        public long decisionCount;
        public float solverTimeSec;
        public int nbItems;
        public int nbTransactions;
        public long rule8ExecCount;
        public long rule8PruneCount;

        public MiningResults() {
            this.patterns = new ArrayList<>();
        }

        public long getExecutionTime() {
            return endTime - startTime;
        }

        public void printStats() {
            System.out.println("\n" + fillString("=", 70));
            String modeStr = "unknown";
            System.out.println("         *** MINING RESULTS (PeakUtility) ***");
            System.out.println(fillString("=", 70));
            System.out.println(String.format("Dataset          : %d items × %d transactions", nbItems, nbTransactions));
            System.out.println(String.format("Minimum Utility  : %d", minUtilityThreshold));
            System.out.println(String.format("Patterns Found   : %d", patternCount));
            System.out.println(String.format("Execution Time   : %d ms", getExecutionTime()));
            System.out.println(String.format("Fixpoints        : %d", fixpointCount));
            System.out.println(String.format("Nodes / Fails    : %d / %d", nodeCount, failCount));
            if (timeoutMs > 0) {
                System.out.println(String.format("Timeout          : %d ms (%s)",
                        timeoutMs, timeoutReached ? "reached" : "not reached"));
            }
            System.out.println(fillString("=", 70) + "\n");
        }

        public void printPatterns() {
            if (patterns.isEmpty()) {
                System.out.println("No patterns found.");
                return;
            }

            System.out.println("\nPatterns discovered:");
            System.out.println(fillString("-", 70));
            for (int i = 0; i < patterns.size(); i++) {
                System.out.printf("%4d. %s%n", i + 1, patterns.get(i));
            }
            System.out.println(fillString("-", 70));
        }
    }

    // =========================================================================
    // MINING ENGINE
    // =========================================================================

    private Config config;

    public PeakUtility(Config config) {
        this.config = config;
    }

    public MiningResults run() throws Exception {
        long startTime = System.currentTimeMillis();

        if (config.verbose) {
            System.out.println("Loading dataset from: " + config.inputFile);
        }

        // Load database
        TransactionalDatabase database = new HUIReader(config.inputFile).read_hui();
        int nbItems = database.getNbItems();
        int nbTrans = database.getNbTransactions();

        if (config.verbose) {
            System.out.println(String.format("✓ Dataset loaded: %d items, %d transactions", nbItems, nbTrans));
            System.out.println(String.format("✓ Mode: %s", config.mode.toString()));
        }

        int minutil = config.minUtilAbsolute;
        if (minutil <= 0) minutil = 1;

        if (config.verbose) {
            System.out.println(String.format("✓ Min utility threshold (absolute): %d", minutil));
        }

        // Build UtilityList_0 structures
        if (config.verbose) {
            System.out.println("Building UtilityList_0 structures...");
        }

        int[][] itemUtils = database.getTransactionItemUtilities();
        int[] transactionUtilities = database.getTransactionUtilities();
        UtilityList_0[] itemULs = buildUtilityLists_v0(database, itemUtils, nbItems, nbTrans);

        int[] itemTWU = computeItemTWU(database, transactionUtilities);
        int[] itemUtility = computeItemUtility(database, itemUtils);

        if (config.verbose) {
            System.out.println("✓ UtilityList_v0 structures built");
        }

        // Setup CSP model
        if (config.verbose) {
            System.out.println("Setting up CSP model...");
        }

        Model model = new Model("PeakUtility Mining (" + config.mode + ")");
        BoolVar[] x = model.boolVarArray("x", nbItems);
        IntVar patternLength = model.intVar("length", 1, nbItems);

        model.sum(x, "=", patternLength).post();
        // Post unified PeakUtility constraint
        int rulesMask = parseRulesMask(config.rulesMask);
        PropPeakUtility propagator = new PropPeakUtility(
                x, minutil, config.depthK, transactionUtilities, itemULs, transactionUtilities.length, config.mode, rulesMask
        );
        new Constraint(
                "PeakUtility",
                propagator
        ).post();

        if (config.verbose) {
            System.out.println("✓ CSP model configured with PeakUtility (" + config.mode + " mode)");
            System.out.println("\n" + fillString("=", 70));
            System.out.println("Starting mining process...");
            System.out.println(fillString("=", 70) + "\n");
        }

        // Mine patterns
        MiningResults results = new MiningResults();
        results.minUtilityThreshold = minutil;
        results.nbItems = nbItems;
        results.nbTransactions = nbTrans;
        results.startTime = startTime;
        results.timeoutMs = config.timeoutMs;

        Solver solver = model.getSolver();
        if (config.timeoutMs > 0) {
            solver.limitTime(config.timeoutMs);
        }

        if (config.varHeuristic == VarHeuristic.CHOCO_DEFAULT) {
            solver.setSearch(Search.defaultSearch(model));
        } else {
            solver.setSearch(Search.intVarSearch(
                    createVariableSelector(config.varHeuristic, model, database, itemTWU, itemUtility, x),
                    createValueSelector(config.valHeuristic),
                    x
            ));
        }

        PrintWriter writer = null;
        if (config.writeToFile) {
            writer = new PrintWriter(new FileWriter(config.outputFile));
        }

        try {
            int solutionCount = 0;

            while (solver.solve()) {
                int[] items = getSelectedItems(x);
                long utility = computePatternUtility(items, itemUtils, transactionUtilities);

                if (config.storePatternsInMemory) {
                    Pattern pattern = new Pattern(utility, items);
                    results.patterns.add(pattern);
                    if (config.writeToFile && writer != null) {
                        writer.println(pattern.toString());
                    }
                }
                solutionCount++;

                if (config.verbose && solutionCount % 100 == 0) {
                    System.out.println(String.format("  [Progress] Found %d patterns...", solutionCount));
                }
            } 
            results.patternCount = solutionCount;
            results.timeoutReached = config.timeoutMs > 0 && solver.isStopCriterionMet();
            results.endTime = System.currentTimeMillis();
            IMeasures m = solver.getMeasures();
            results.fixpointCount = m.getFixpointCount();
            results.nodeCount = m.getNodeCount();
            results.failCount = m.getFailCount();
            results.backtrackCount = m.getBackTrackCount();
            results.restartCount = m.getRestartCount();
            results.decisionCount = m.getDecisionCount();
            results.solverTimeSec = m.getTimeCount();
            results.rule8ExecCount = propagator.getRule8ExecCount();
            results.rule8PruneCount = propagator.getRule8PruneCount();

            if (config.verbose) {
                if (results.timeoutReached) {
                    System.out.println(String.format(
                            "Timeout reached at %d ms, returning partial result (%d patterns).",
                            config.timeoutMs, solutionCount));
                }
                System.out.println();
                if (config.printDetailedStats) {
                    solver.printShortStatistics();
                }
            }

        } finally {
            if (writer != null) {
                writer.close();
                if (config.verbose) {
                    System.out.println("\n✓ Results written to: " + config.outputFile);
                }
            }
        }

        return results;
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private static int parseRulesMask(String mask) {
        if (mask == null || !mask.matches("[01]{8}")) {
            throw new IllegalArgumentException("rulesMask must be 8 chars of 0/1");
        }
        int value = 0;
        for (int i = 0; i < 8; i++) {
            if (mask.charAt(i) == '1') {
                value |= (1 << i);
            }
        }
        return value;
    }

    private UtilityList_0[] buildUtilityLists_v0(TransactionalDatabase database, int[][] itemUtils, 
                                                   int nbItems, int nbTrans) {
        UtilityList_0[] itemULs = new UtilityList_0[nbItems];
        for (int i = 0; i < nbItems; i++) {
            itemULs[i] = new UtilityList_0(i);
        }

        for (int t = 0; t < nbTrans; t++) {
            int remainingUtility = 0;
            for (int i = nbItems - 1; i >= 0; i--) {
                if (database.getVerticalRepresentation()[i].get(t)) {
                    int fullUtil = itemUtils[t][i];
                    itemULs[i].add(new Custom_Element(t, fullUtil, remainingUtility));
                    remainingUtility += fullUtil;
                }
            }
        }

        return itemULs;
    }

    private long computePatternUtility(int[] items, int[][] itemUtils, int[] transactionUtilities) {
        BitSet coverage = new BitSet();
        for (int item : items) {
            if (coverage.isEmpty()) {
                for (int t = 0; t < itemUtils.length; t++) {
                    if (itemUtils[t][item] > 0) {
                        coverage.set(t);
                    }
                }
            } else {
                BitSet temp = new BitSet();
                for (int t = coverage.nextSetBit(0); t >= 0; t = coverage.nextSetBit(t + 1)) {
                    if (itemUtils[t][item] > 0) {
                        temp.set(t);
                    }
                }
                coverage = temp;
            }
        }

        long utility = 0;
        for (int t = coverage.nextSetBit(0); t >= 0; t = coverage.nextSetBit(t + 1)) {
            for (int item : items) {
                utility += itemUtils[t][item];
            }
        }
        return utility;
    }

    private int[] computeItemTWU(TransactionalDatabase database, int[] transactionUtilities) {
        int nbItems = database.getNbItems();
        int[] itemTWU = new int[nbItems];
        for (int i = 0; i < nbItems; i++) {
            BitSet tids = database.getVerticalRepresentation()[i];
            int twu = 0;
            for (int t = tids.nextSetBit(0); t >= 0; t = tids.nextSetBit(t + 1)) {
                twu += transactionUtilities[t];
            }
            itemTWU[i] = twu;
        }
        return itemTWU;
    }

    private int[] computeItemUtility(TransactionalDatabase database, int[][] itemUtils) {
        int nbItems = database.getNbItems();
        int[] itemUtility = new int[nbItems];
        for (int i = 0; i < nbItems; i++) {
            BitSet tids = database.getVerticalRepresentation()[i];
            int util = 0;
            for (int t = tids.nextSetBit(0); t >= 0; t = tids.nextSetBit(t + 1)) {
                util += itemUtils[t][i];
            }
            itemUtility[i] = util;
        }
        return itemUtility;
    }

    private int[] getSelectedItems(BoolVar[] x) {
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < x.length; i++) {
            if (x[i].isInstantiatedTo(1)) {
                selected.add(i);
            }
        }
        int[] result = new int[selected.size()];
        for (int i = 0; i < selected.size(); i++) {
            result[i] = selected.get(i);
        }
        return result;
    }

    private VariableSelector<IntVar> createVariableSelector(VarHeuristic heuristic, Model model,
                                                            TransactionalDatabase database, int[] itemTWU, int[] itemUtility,
                                                            IntVar[] vars) {
        switch (heuristic) {
            case ITEM_UTIL_ASC:
                return new ItemUtilityAsc(itemUtility);
            case ITEM_UTIL_DESC:
                return new ItemUtilityDesc(itemUtility);
            case TWU_ASC:
                return new TwuAsc(itemTWU);
            case TWU_DESC:
                return new TwuDesc(itemTWU);
            case CHOCO_INPUT_ORDER:
                return new InputOrder<>(model);
            case CHOCO_FIRST_FAIL:
                return new FirstFail(model);
            case CHOCO_DOM_OVER_WDEG:
                return new DomOverWDeg<>(vars, 0L);
            case CHOCO_DEFAULT:
            default:
                return new InputOrder<>(model);
        }
    }

    private IntValueSelector createValueSelector(ValHeuristic heuristic) {
        switch (heuristic) {
            case MAX:
                return new IntDomainMax();
            case MIN:
            default:
                return new IntDomainMin();
        }
    }

    private static String fillString(String s, int length) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < length) {
            sb.append(s);
        }
        return sb.substring(0, length);
    }

    private static void printUsage() {
        System.err.println("\nUsage: java PeakUtility [inputFile] [outputFile] [minUtil] [mode] [timeoutMs] [depthK] [varHeuristic] [valHeuristic] [rulesMask]");
        System.err.println("\nPositional Arguments:");
        System.err.println("  1. inputFile       : Input dataset file (default: datasets/Data_SPMF/accidents_utility_spmf.txt)");
        System.err.println("  2. outputFile      : Output results file (default: datasets/output_peakutil.txt)");
        System.err.println("  3. minUtil         : Minimum utility threshold, absolute value (default: 10000)");
        System.err.println("  4. mode            : Mining mode: HUI | UPI (default: HUI)");
        System.err.println("  5. timeoutMs       : Timeout in milliseconds, 0 = no timeout (default: 0)");
        System.err.println("  6. depthK          : Depth K parameter for UPI mode (default: 2)");
        System.err.println("  7. varHeuristic    : Variable selection heuristic (default: ITEM_UTIL_ASC)");
        System.err.println("                       Available: ITEM_UTIL_ASC, ITEM_UTIL_DESC,");
        System.err.println("                                  TWU_ASC, TWU_DESC, CHOCO_INPUT_ORDER,");
        System.err.println("                                  CHOCO_FIRST_FAIL, CHOCO_DOM_OVER_WDEG, CHOCO_DEFAULT");
        System.err.println("  8. valHeuristic    : Value selection heuristic (default: MIN)");
        System.err.println("                       Available: MIN, MAX");
        System.err.println("  9. rulesMask       : 8-bit mask for rules R1..R8 (default: 11111111)");
        System.err.println("\nExamples:");
        System.err.println("  # HUI mode with default settings");
        System.err.println("  java PeakUtility datasets/Data_SPMF/chess_utility_spmf.txt results.txt 150000 HUI");
        System.err.println("\n  # UPI mode with timeout and custom heuristics");
        System.err.println("  java PeakUtility datasets/Data_SPMF/mushroom_utility_spmf.txt results.txt 20000 UPI 30000 2 TWU_DESC MAX");
        System.err.println();
    }

    /**
     * Example main method demonstrating both HUI and UPI modes with configurable heuristics.
     */
    public static void main(String[] args) throws Exception {
        // Parse command-line arguments
        String inputFile = args.length > 0 ? args[0] : "datasets/Data_SPMF/chess_utility_spmf.txt";
        String outputFile = args.length > 1 ? args[1] : "datasets/output_peakutil.txt";
        int minUtil = args.length > 2 ? Integer.parseInt(args[2]) : 600000;
        String modeStr = args.length > 3 ? args[3].trim().toUpperCase() : "HUI";
        long timeoutMs = args.length > 4 ? Long.parseLong(args[4]) : 300000L;
        int depthK = args.length > 5 ? Integer.parseInt(args[5]) : 3;
        String varHeurStr = args.length > 6 ? args[6].toUpperCase() : "ITEM_UTIL_ASC";
        String valHeurStr = args.length > 7 ? args[7].toUpperCase() : "MIN";
        String rulesMask = args.length > 8 ? args[8].trim() : "11111111";

        // Validate mode
        PropPeakUtility.Mode mode;
        try {
            mode = Enum.valueOf(PropPeakUtility.Mode.class, modeStr);
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown mode: " + modeStr);
            printUsage();
            System.exit(1);
            return;
        }

        // Validate variable heuristic
        VarHeuristic varHeuristic;
        try {
            varHeuristic = VarHeuristic.valueOf(varHeurStr);
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown variable heuristic: " + varHeurStr);
            printUsage();
            System.exit(1);
            return;
        }

        // Validate value heuristic
        ValHeuristic valHeuristic;
        try {
            valHeuristic = ValHeuristic.valueOf(valHeurStr);
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown value heuristic: " + valHeurStr);
            printUsage();
            System.exit(1);
            return;
        }

        if (!rulesMask.matches("[01]{8}")) {
            System.err.println("Invalid rulesMask: " + rulesMask + " (expected 8 chars of 0/1)");
            printUsage();
            System.exit(1);
            return;
        }

        // Print configuration
        System.out.println("\n" + fillString("=", 70));
        System.out.println("PEAKUTILITY MINING ENGINE");
        System.out.println(fillString("=", 70));
        System.out.println("Input file       : " + inputFile);
        System.out.println("Output file      : " + outputFile);
        System.out.println("Minimum util     : " + minUtil);
        System.out.println("Mode             : " + mode);
        System.out.println("Timeout (ms)     : " + (timeoutMs > 0 ? timeoutMs : "No timeout"));
        System.out.println("Var heuristic    : " + varHeuristic);
        System.out.println("Val heuristic    : " + valHeuristic);
        System.out.println("Rules mask       : " + rulesMask);
        if (mode == PropPeakUtility.Mode.UPI) {
            System.out.println("Depth K          : " + depthK);
        }
        System.out.println(fillString("=", 70));

        // Create and run configuration
        Config config = new Config(inputFile, outputFile)
                .withMinUtilAbsolute(minUtil)
                .withMode(mode)
                .withVarHeuristic(varHeuristic)
                .withValHeuristic(valHeuristic)
                .withVerbose(false)
                .withDetailedStats(true)
                .withRulesMask(rulesMask);

        if (timeoutMs > 0) {
            config.withTimeoutMs(timeoutMs);
        }

        if (mode == PropPeakUtility.Mode.UPI) {
            config.withDepthK(depthK);
        }

        // Run mining
        PeakUtility miner = new PeakUtility(config);
        MiningResults results = miner.run();
        results.printStats();
        System.out.println("RESULT\talgorithm=PeakUtility"
                + "\tpatterns=" + results.patternCount
                + "\ttimeoutReached=" + results.timeoutReached
                + "\tnodes=" + results.nodeCount
                + "\tsolverTimeSec=" + results.solverTimeSec
                + "\trule8ExecCount=" + results.rule8ExecCount
                + "\trule8PruneCount=" + results.rule8PruneCount);

        System.out.println(fillString("=", 70) + "\n");
    }
}
