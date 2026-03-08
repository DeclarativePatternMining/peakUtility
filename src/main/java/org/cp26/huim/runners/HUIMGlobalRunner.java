package org.cp26.huim.runners;

import org.apache.commons.cli.*;
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
import org.cp26.huim.global.HUIMGlobalConstraint;
import org.cp26.huim.global.ProjectionBackend;
import org.cp26.huim.heuristics.ItemUtilityAsc;
import org.cp26.huim.heuristics.ItemUtilityDesc;
import org.cp26.huim.heuristics.TwuAsc;
import org.cp26.huim.heuristics.TwuDesc;
import org.cp26.huim.parser.HighUtilityDataset;

import java.util.*;

public final class HUIMGlobalRunner {

    public static void main(String[] args) throws Exception {

        // CLI (mirrors HUIM_SAT style)
        Options o = new Options();
        o.addOption(Option.builder().longOpt("file").hasArg(true).required(true)
                .desc("Input dataset file").build());
        o.addOption(Option.builder().longOpt("minutil").hasArg(true).required(true)
                .desc("Minimum utility threshold").build());

        o.addOption(Option.builder().longOpt("include").hasArg(true).required(false)
                .desc("Comma-separated item IDs that must be included").build());
        o.addOption(Option.builder().longOpt("exclude").hasArg(true).required(false)
                .desc("Comma-separated item IDs that must be excluded").build());
        o.addOption(Option.builder().longOpt("minsize").hasArg(true).required(false)
                .desc("Minimum itemset size").build());
        o.addOption(Option.builder().longOpt("maxsize").hasArg(true).required(false)
                .desc("Maximum itemset size").build());
        o.addOption(Option.builder().longOpt("maxutil").hasArg(true).required(false)
                .desc("Upper bound on total utility").build());
        o.addOption(Option.builder().longOpt("projection").hasArg(true).required(false)
                .desc("Projection backend: ARRAY or BITSET (default: ARRAY)").build());
        o.addOption(Option.builder().longOpt("varsel").hasArg(true).required(false)
                .desc("Variable selector: input|firstfail|domwdeg|twuAsc|twuDesc|utilAsc|utilDesc (default: input)").build());
        o.addOption(Option.builder().longOpt("valsel").hasArg(true).required(false)
                .desc("Value selector: min|max (default: max)").build());

        o.addOption(Option.builder().longOpt("tumin").hasArg(true).required(false)
                .desc("Only consider transactions with transactionUtility >= tumin").build());
        o.addOption(Option.builder().longOpt("tumax").hasArg(true).required(false)
                .desc("Only consider transactions with transactionUtility <= tumax").build());

        o.addOption("h", "help", false, "Help");

        CommandLine cmd;
        try {
            cmd = new DefaultParser().parse(o, args);
        } catch (ParseException pe) {
            new HelpFormatter().printHelp("HUIMGlobalRunner", o, true);
            throw pe;
        }

        if (cmd.hasOption("help")) {
            new HelpFormatter().printHelp("HUIMGlobalRunner", o, true);
            return;
        }

        String path = cmd.getOptionValue("file");
        int minUtil = Integer.parseInt(cmd.getOptionValue("minutil"));

        String includeStr = cmd.getOptionValue("include", "");
        String excludeStr = cmd.getOptionValue("exclude", "");
        int minSize = cmd.hasOption("minsize") ? Integer.parseInt(cmd.getOptionValue("minsize")) : 0;
        int maxSize = cmd.hasOption("maxsize") ? Integer.parseInt(cmd.getOptionValue("maxsize")) : -1;
        int maxUtil = cmd.hasOption("maxutil") ? Integer.parseInt(cmd.getOptionValue("maxutil")) : -1;
        ProjectionBackend projectionBackend = ProjectionBackend.fromCli(cmd.getOptionValue("projection", "ARRAY"));
        String varSel = cmd.getOptionValue("varsel", "input");
        String valSel = cmd.getOptionValue("valsel", "max");

        int tuMin = cmd.hasOption("tumin") ? Integer.parseInt(cmd.getOptionValue("tumin")) : Integer.MIN_VALUE;
        int tuMax = cmd.hasOption("tumax") ? Integer.parseInt(cmd.getOptionValue("tumax")) : Integer.MAX_VALUE;

        // load
        HighUtilityDataset data = HighUtilityDataset.loadFromFile(path);

        // apply transactionUtility filter directly on allowedTransactions
        for (int t = 0; t < data.transactionCount; t++) {
            int tu = data.transactionUtility[t];
        }

        Model model = new Model("HUIM-GLOBAL");
        BoolVar[] x = model.boolVarArray("X", data.itemCount);

        // post global HUIM constraint
        model.post(new HUIMGlobalConstraint(x, data, minUtil, maxUtil, projectionBackend));

        // include/exclude constraints on x (by external item IDs)
        for (int id : parseCsvIds(includeStr)) {
            Integer idx = data.originalItemIdToIndex.get(id);
            if (idx != null) model.arithm(x[idx], "=", 1).post();
        }
        for (int id : parseCsvIds(excludeStr)) {
            Integer idx = data.originalItemIdToIndex.get(id);
            if (idx != null) model.arithm(x[idx], "=", 0).post();
        }

        // size bounds
        if (minSize > 0 || maxSize > 0) {
            IntVar card = model.intVar("card", 0, data.itemCount);
            model.sum(x, "=", card).post();
            if (minSize > 0) model.arithm(card, ">=", minSize).post();
            if (maxSize > 0) model.arithm(card, "<=", maxSize).post();
        }

        // max util upper bound (very simple decomposition: sum u(i,t)*y_{i,t} <= maxUtil)
        // Implemented via occurrence vars y_{t,k} like your SAT model but with CP BoolVars.
        // This is optional; if maxUtil not set, skip completely.
        if (maxUtil > 0) {
            // build y vars per occurrence
            List<BoolVar> yLits = new ArrayList<>();
            List<Integer> coeffs = new ArrayList<>();

            for (int t = 0; t < data.transactionCount; t++) {
                int[] items = data.transactionItems[t];
                int[] utils = data.transactionUtilities[t];

                for (int k = 0; k < items.length; k++) {
                    int item = items[k];
                    int u = utils[k];

                    // y_{t,k} <-> (x_item AND txAllowed)
                    // since txAllowed is constant here (we already filtered), we just link y -> x and x -> y
                    // NOTE: x -> y is too strong if the item appears in multiple tx. We must do y <= x only.
                    // For an upper bound, y <= x is sufficient and safe.
                    BoolVar y = model.boolVar("y_" + t + "_" + k);
                    model.arithm(y, "<=", x[item]).post();

                    yLits.add(y);
                    coeffs.add(u);
                }
            }

            if (!yLits.isEmpty()) {
                BoolVar[] yArr = yLits.toArray(new BoolVar[0]);
                int[] cArr = coeffs.stream().mapToInt(Integer::intValue).toArray();
                model.scalar(yArr, cArr, "<=", maxUtil).post();
            }
        }

        Solver solver = model.getSolver();
        int[] itemTwu = computeItemTwu(data);
        int[] itemUtility = computeItemUtility(data);
        solver.setSearch(buildSearch(model, x, varSel, valSel, itemTwu, itemUtility));

        long count = 0;
        long start = System.nanoTime();
        long peakMem = usedMemBytes();

        while (solver.solve()) {
            count++;
            long cur = usedMemBytes();
            if (cur > peakMem) peakMem = cur;
        }

        long ms = (System.nanoTime() - start) / 1_000_000L;
        double peakMB = peakMem / (1024.0 * 1024.0);

        System.out.println("HUIs: " + count);
        System.out.println("Time(ms): " + ms);
        System.out.println("Max memory (MB): " + peakMB);
    }

    private static int[] parseCsvIds(String s) {
        if (s == null || s.trim().isEmpty()) return new int[0];
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        int k = 0;
        for (String p : parts) {
            String q = p.trim();
            if (!q.isEmpty()) out[k++] = Integer.parseInt(q);
        }
        return (k == out.length) ? out : Arrays.copyOf(out, k);
    }

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
        switch (varSel.toLowerCase(Locale.ROOT)) {
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
