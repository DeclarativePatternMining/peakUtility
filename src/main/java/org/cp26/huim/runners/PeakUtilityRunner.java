package org.cp26.huim.runners;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
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
import org.cp26.huim.global.PeakUtilityConstraint;
import org.cp26.huim.global.ProjectionBackend;
import org.cp26.huim.global.PropPeakUtility;
import org.cp26.huim.heuristics.ItemUtilityAsc;
import org.cp26.huim.heuristics.ItemUtilityDesc;
import org.cp26.huim.heuristics.TwuAsc;
import org.cp26.huim.heuristics.TwuDesc;
import org.cp26.huim.parser.HighUtilityDataset;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Locale;

public final class PeakUtilityRunner {

    public static void main(String[] args) throws Exception {
        Options o = new Options();
        o.addOption(Option.builder().longOpt("file").hasArg(true).required(true)
                .desc("Input dataset file").build());
        o.addOption(Option.builder().longOpt("output").hasArg(true).required(true)
                .desc("Output file for patterns").build());
        o.addOption(Option.builder().longOpt("minutil").hasArg(true).required(true)
                .desc("Minimum utility threshold").build());
        o.addOption(Option.builder().longOpt("mode").hasArg(true).required(false)
                .desc("HUI or UPI (default: HUI)").build());
        o.addOption(Option.builder().longOpt("depthk").hasArg(true).required(false)
                .desc("Depth-k for UPI rule 6 (default: 3)").build());
        o.addOption(Option.builder().longOpt("projection").hasArg(true).required(false)
                .desc("Projection backend: ARRAY or BITSET (default: ARRAY)").build());
        o.addOption(Option.builder().longOpt("varsel").hasArg(true).required(false)
                .desc("Variable selector: input|firstfail|domwdeg|twuAsc|twuDesc|utilAsc|utilDesc (default: input)").build());
        o.addOption(Option.builder().longOpt("valsel").hasArg(true).required(false)
                .desc("Value selector: min|max (default: max)").build());
        o.addOption(Option.builder().longOpt("timeoutms").hasArg(true).required(false)
                .desc("Timeout in milliseconds (default: 0 = no timeout)").build());
        o.addOption(Option.builder().longOpt("include").hasArg(true).required(false)
                .desc("Comma-separated item IDs that must be included").build());
        o.addOption(Option.builder().longOpt("exclude").hasArg(true).required(false)
                .desc("Comma-separated item IDs that must be excluded").build());
        o.addOption(Option.builder().longOpt("minsize").hasArg(true).required(false)
                .desc("Minimum itemset size").build());
        o.addOption(Option.builder().longOpt("maxsize").hasArg(true).required(false)
                .desc("Maximum itemset size").build());
        o.addOption("h", "help", false, "Help");

        CommandLine cmd;
        try {
            cmd = new DefaultParser().parse(o, args);
        } catch (ParseException pe) {
            new HelpFormatter().printHelp("PeakUtilityRunner", o, true);
            throw pe;
        }
        if (cmd.hasOption("help")) {
            new HelpFormatter().printHelp("PeakUtilityRunner", o, true);
            return;
        }

        String path = cmd.getOptionValue("file");
        String outputPath = cmd.getOptionValue("output");
        int minUtil = Integer.parseInt(cmd.getOptionValue("minutil"));
        PropPeakUtility.Mode mode = PropPeakUtility.Mode.valueOf(cmd.getOptionValue("mode", "HUI").toUpperCase(Locale.ROOT));
        int depthK = Integer.parseInt(cmd.getOptionValue("depthk", "3"));
        ProjectionBackend projectionBackend = ProjectionBackend.fromCli(cmd.getOptionValue("projection", "ARRAY"));
        String varSel = cmd.getOptionValue("varsel", "input");
        String valSel = cmd.getOptionValue("valsel", "max");
        long timeoutMs = Long.parseLong(cmd.getOptionValue("timeoutms", "0"));
        String includeStr = cmd.getOptionValue("include", "");
        String excludeStr = cmd.getOptionValue("exclude", "");
        int minSize = cmd.hasOption("minsize") ? Integer.parseInt(cmd.getOptionValue("minsize")) : 0;
        int maxSize = cmd.hasOption("maxsize") ? Integer.parseInt(cmd.getOptionValue("maxsize")) : -1;

        HighUtilityDataset data = HighUtilityDataset.loadFromFile(path);
        Model model = new Model("PEAK_UTILITY_RUNNER");
        BoolVar[] x = model.boolVarArray("X", data.itemCount);

        if (mode == PropPeakUtility.Mode.UPI) {
            model.post(new PeakUtilityConstraint(x, data, minUtil, depthK, projectionBackend));
        } else {
            model.post(new PeakUtilityConstraint(x, data, minUtil, projectionBackend));
        }

        for (int id : parseCsvIds(includeStr)) {
            Integer idx = data.originalItemIdToIndex.get(id);
            if (idx != null) {
                model.arithm(x[idx], "=", 1).post();
            }
        }
        for (int id : parseCsvIds(excludeStr)) {
            Integer idx = data.originalItemIdToIndex.get(id);
            if (idx != null) {
                model.arithm(x[idx], "=", 0).post();
            }
        }
        if (minSize > 0 || maxSize > 0) {
            IntVar card = model.intVar("card", 0, data.itemCount);
            model.sum(x, "=", card).post();
            if (minSize > 0) model.arithm(card, ">=", minSize).post();
            if (maxSize > 0) model.arithm(card, "<=", maxSize).post();
        }

        Solver solver = model.getSolver();
        solver.setSearch(buildSearch(model, x, varSel, valSel, computeItemTwu(data), computeItemUtility(data)));
        if (timeoutMs > 0) {
            solver.limitTime(timeoutMs);
        }

        File out = new File(outputPath);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        long count = 0;
        long start = System.nanoTime();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(out))) {
            while (solver.solve()) {
                count++;
                writer.write(patternAsLine(x, data));
                writer.newLine();
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000L;
        System.out.println("Mode: " + mode);
        System.out.println("Patterns: " + count);
        System.out.println("Time(ms): " + ms);
        if (solver.isStopCriterionMet()) {
            System.out.println("Stopped early due to timeout/limit.");
        }
        System.out.println("Output: " + outputPath);
    }

    private static String patternAsLine(BoolVar[] x, HighUtilityDataset data) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < data.itemCount; i++) {
            if (x[i].getValue() == 1) {
                if (!first) {
                    sb.append(' ');
                }
                sb.append(data.itemIndexToOriginalId[i]);
                first = false;
            }
        }

        int totalUtil = 0;
        for (int t = 0; t < data.transactionCount; t++) {
            int[] items = data.transactionItems[t];
            int[] utils = data.transactionUtilities[t];
            for (int k = 0; k < items.length; k++) {
                if (x[items[k]].getValue() == 1) {
                    totalUtil += utils[k];
                }
            }
        }
        sb.append(" #UTIL: ").append(totalUtil);
        return sb.toString();
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
