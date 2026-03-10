package org.cp26.huim.runners;

import org.apache.commons.cli.*;
import org.chocosolver.solver.*;
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMax;
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMin;
import org.chocosolver.solver.search.strategy.selectors.values.IntValueSelector;
import org.chocosolver.solver.search.strategy.selectors.variables.DomOverWDeg;
import org.chocosolver.solver.search.strategy.selectors.variables.FirstFail;
import org.chocosolver.solver.search.strategy.selectors.variables.InputOrder;
import org.chocosolver.solver.search.strategy.selectors.variables.VariableSelector;
import org.chocosolver.solver.variables.*;
import org.chocosolver.solver.search.strategy.Search;
import org.cp26.huim.heuristics.ItemUtilityAsc;
import org.cp26.huim.heuristics.ItemUtilityDesc;
import org.cp26.huim.heuristics.TwuAsc;
import org.cp26.huim.heuristics.TwuDesc;

import java.io.*;
import java.util.*;

public class HuimCpRunner {

    private static String filePath;
    private static String outPath = null;
    private static int minUtil = 10;
    private static boolean useOptBound = false;

    // user constraints (CLI)
    private static String includeStr = null;
    private static String excludeStr = null;
    private static int minSize = 1;
    private static int maxSize = -1;
    private static int maxUtil = -1;
    private static int tuMin = Integer.MIN_VALUE;
    private static int tuMax = Integer.MAX_VALUE;
    private static String varSel = "input";
    private static String valSel = "max";

    // scale to avoid overflow in pruning bounds
    private static final int SCALE = 50;

    // 20 minutes timeout
    private static final long TIMEOUT_MS = 20L * 60L * 1000L;

    private int nTx, nItems;
    private int maxTxLength = 0;

    private List<int[]> txItemIdx = new ArrayList<>();
    private List<int[]> txUtils = new ArrayList<>();

    private int[] TU, scaledTU;
    private int[] supp, uMax, scaledUMax;

    // transaction filter
    private boolean[] allowedTx;

    private Map<Integer,Integer> itemIdToIdx = new HashMap<>();
    private int[] idxToItemId;

    private Model model;
    private BoolVar[] x, occ;
    private IntVar totalUtil;

    public static void main(String[] args) throws Exception {

        Options o = new Options();
        o.addOption(Option.builder("f").longOpt("file").hasArg(true).required(true)
                .desc("Input dataset file").build());
        o.addOption(Option.builder("o").longOpt("output").hasArg(true).required(true)
                .desc("Output file to write HUIs (like SPMF)").build());
        o.addOption(Option.builder("u").longOpt("minutil").hasArg(true).required(true)
                .desc("Minimum utility threshold").build());
        o.addOption(Option.builder().longOpt("optbound").required(false)
                .desc("Enable optimistic bound pruning").build());

        // user-defined constraints
        o.addOption(Option.builder().longOpt("include").hasArg(true).required(false)
                .desc("Comma-separated item IDs that must be included (e.g., 8,11,75)").build());
        o.addOption(Option.builder().longOpt("exclude").hasArg(true).required(false)
                .desc("Comma-separated item IDs that must be excluded (e.g., 20,64)").build());
        o.addOption(Option.builder().longOpt("minsize").hasArg(true).required(false)
                .desc("Minimum itemset size").build());
        o.addOption(Option.builder().longOpt("maxsize").hasArg(true).required(false)
                .desc("Maximum itemset size").build());
        o.addOption(Option.builder().longOpt("maxutil").hasArg(true).required(false)
                .desc("Upper bound on total utility (keep only HUIs with util <= maxutil)").build());

        // transaction utility (TU) filter
        o.addOption(Option.builder().longOpt("tumin").hasArg(true).required(false)
                .desc("Only consider transactions with TU >= tumin").build());
        o.addOption(Option.builder().longOpt("tumax").hasArg(true).required(false)
                .desc("Only consider transactions with TU <= tumax").build());
        o.addOption(Option.builder().longOpt("varsel").hasArg(true).required(false)
                .desc("Variable selector: input|firstfail|domwdeg|twuAsc|twuDesc|utilAsc|utilDesc (default: input)").build());
        o.addOption(Option.builder().longOpt("valsel").hasArg(true).required(false)
                .desc("Value selector: min|max (default: max)").build());

        o.addOption("h", "help", false, "Help");

        CommandLine cmd;
        try {
            cmd = new DefaultParser().parse(o, args);
        } catch (ParseException pe) {
            new HelpFormatter().printHelp("huim-cp", o, true);
            throw pe;
        }

        if (cmd.hasOption("help")) {
            new HelpFormatter().printHelp("huim-cp", o, true);
            return;
        }

        filePath = cmd.getOptionValue("file");
        outPath  = cmd.getOptionValue("output");
        minUtil  = Integer.parseInt(cmd.getOptionValue("minutil"));
        useOptBound = cmd.hasOption("optbound");

        // parse constraint args
        if (cmd.hasOption("include")) includeStr = cmd.getOptionValue("include");
        if (cmd.hasOption("exclude")) excludeStr = cmd.getOptionValue("exclude");
        if (cmd.hasOption("minsize")) minSize = Integer.parseInt(cmd.getOptionValue("minsize"));
        if (cmd.hasOption("maxsize")) maxSize = Integer.parseInt(cmd.getOptionValue("maxsize"));
        if (cmd.hasOption("maxutil")) maxUtil = Integer.parseInt(cmd.getOptionValue("maxutil"));
        if (cmd.hasOption("tumin")) tuMin = Integer.parseInt(cmd.getOptionValue("tumin"));
        if (cmd.hasOption("tumax")) tuMax = Integer.parseInt(cmd.getOptionValue("tumax"));
        if (cmd.hasOption("varsel")) varSel = cmd.getOptionValue("varsel");
        if (cmd.hasOption("valsel")) valSel = cmd.getOptionValue("valsel");

        HuimCpRunner h = new HuimCpRunner();
        h.loadFile(filePath);
        h.buildModel();
        h.solve(outPath);
    }

    /* ================= LOAD ================= */

    private void loadFile(String path) throws IOException {

        List<int[]> rawItems = new ArrayList<>();
        List<int[]> rawUtils = new ArrayList<>();
        List<Integer> rawTU = new ArrayList<>();
        Set<Integer> itemSet = new HashSet<>();

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("%")) continue;

                String[] p = line.split(":");
                int[] items = parse(p[0]);
                int tu = Integer.parseInt(p[1].trim());
                int[] utils = parse(p[2]);

                rawItems.add(items);
                rawUtils.add(utils);
                rawTU.add(tu);

                maxTxLength = Math.max(maxTxLength, items.length);

                for (int it : items) itemSet.add(it);
            }
        }

        List<Integer> sorted = new ArrayList<>(itemSet);
        Collections.sort(sorted);

        nItems = sorted.size();
        idxToItemId = new int[nItems];
        supp = new int[nItems];
        uMax = new int[nItems];

        for (int i = 0; i < nItems; i++) {
            idxToItemId[i] = sorted.get(i);
            itemIdToIdx.put(sorted.get(i), i);
        }

        nTx = rawItems.size();
        TU = new int[nTx];
        scaledTU = new int[nTx];
        allowedTx = new boolean[nTx];

        for (int t = 0; t < nTx; t++) {
            TU[t] = rawTU.get(t);
            scaledTU[t] = Math.max(1, TU[t] / SCALE);

            // transaction filter based on TU
            allowedTx[t] = (TU[t] >= tuMin && TU[t] <= tuMax);

            int[] it = rawItems.get(t);
            int[] ut = rawUtils.get(t);

            int[] idx = new int[it.length];
            for (int k = 0; k < it.length; k++) {
                int id = itemIdToIdx.get(it[k]);
                idx[k] = id;

                supp[id]++;
                uMax[id] = Math.max(uMax[id], ut[k]);
            }

            txItemIdx.add(idx);
            txUtils.add(ut);
        }

        scaledUMax = new int[nItems];
        for (int i = 0; i < nItems; i++) {
            scaledUMax[i] = Math.max(1, uMax[i] / SCALE);
        }
    }

    private int[] parse(String s) {
        String[] t = s.trim().split("\\s+");
        int[] r = new int[t.length];
        for (int i = 0; i < t.length; i++) r[i] = Integer.parseInt(t[i]);
        return r;
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

    /* ================= MODEL ================= */

    private void buildModel() {

        model = new Model("HUIM");

        x = model.boolVarArray(nItems);
        occ = model.boolVarArray(nTx);

        // size bounds
        int lbSize = Math.max(1, minSize);
        int ubSize = (maxSize > 0) ? Math.min(maxTxLength, maxSize) : maxTxLength;
        if (lbSize > ubSize) {
            // infeasible size interval -> no solutions
            // We still build a model that is inconsistent quickly:
            lbSize = 1;
            ubSize = 0;
        }

        // structural pruning: itemset size cannot exceed biggest transaction (and user bounds)
        IntVar len = model.intVar(lbSize, ubSize);
        model.sum(x, "=", len).post();

        // inclusion/exclusion of specific items
        int[] includeIds = parseCsvIds(includeStr);
        for (int id : includeIds) {
            Integer idx = itemIdToIdx.get(id);
            if (idx != null) model.arithm(x[idx], "=", 1).post();
        }
        int[] excludeIds = parseCsvIds(excludeStr);
        for (int id : excludeIds) {
            Integer idx = itemIdToIdx.get(id);
            if (idx != null) model.arithm(x[idx], "=", 0).post();
        }

        IntVar[] contrib = new IntVar[nTx];

        /* ---------- CORE ---------- */

        for (int t = 0; t < nTx; t++) {

            int[] items = txItemIdx.get(t);
            int[] utils = txUtils.get(t);

            IntVar[] chosen = new IntVar[items.length];
            for (int i = 0; i < items.length; i++)
                chosen[i] = x[items[i]];

            IntVar sumX = model.intVar(0, items.length);
            model.sum(chosen, "=", sumX).post();

            model.arithm(sumX, "=", len).reifyWith(occ[t]);

            // transaction filtering (TU constraint)
            // if not allowed, occ[t] must be 0 (transaction never counted)
            if (!allowedTx[t]) {
                model.arithm(occ[t], "=", 0).post();
            }

            IntVar[] uTerms = new IntVar[items.length];
            int maxUTx = 0;

            for (int i = 0; i < items.length; i++) {
                uTerms[i] = model.intScaleView(x[items[i]], utils[i]);
                maxUTx += utils[i];
            }

            IntVar uTx = model.intVar(0, maxUTx);
            model.sum(uTerms, "=", uTx).post();

            contrib[t] = model.intVar(0, maxUTx);
            model.times(occ[t], uTx, contrib[t]).post();
        }

        long ub = 0;
        for (int t = 0; t < nTx; t++)
            for (int u : txUtils.get(t)) ub += u;

        if (ub > Integer.MAX_VALUE) ub = Integer.MAX_VALUE;

        totalUtil = model.intVar(0, (int) ub);
        model.sum(contrib, "=", totalUtil).post();

        // min utility threshold (kept)
        model.arithm(totalUtil, ">=", minUtil).post();

        // utility upper bound (optional)
        if (maxUtil > 0) {
            model.arithm(totalUtil, "<=", maxUtil).post();
        }

        /* ---------- TWU PRUNING (scaled) ---------- */

        IntVar[] twuTerms = new IntVar[nTx];
        int sumTU = 0;

        for (int t = 0; t < nTx; t++) {
            twuTerms[t] = model.intVar(0, scaledTU[t]);
            model.times(occ[t], scaledTU[t], twuTerms[t]).post();
            sumTU += scaledTU[t];
        }

        IntVar TWU = model.intVar(0, sumTU);
        model.sum(twuTerms, "=", TWU).post();
        model.arithm(TWU, ">=", minUtil / SCALE).post();

        /* ---------- OPTIONAL OPTIMISTIC BOUND ---------- */

        if (useOptBound) {

            IntVar[] maxUTerms = new IntVar[nItems];
            int sumMax = 0;

            for (int i = 0; i < nItems; i++) {
                maxUTerms[i] = model.intScaleView(x[i], scaledUMax[i]);
                sumMax += scaledUMax[i];
            }

            IntVar sumMaxU = model.intVar(0, sumMax);
            model.sum(maxUTerms, "=", sumMaxU).post();

            IntVar minSupp = model.intVar(0, nTx);
            for (int i = 0; i < nItems; i++) {
                model.ifThen(
                        model.arithm(x[i], "=", 1),
                        model.arithm(minSupp, "<=", supp[i])
                );
            }

            IntVar optUB = model.intVar(0, nTx * sumMax);
            model.times(minSupp, sumMaxU, optUB).post();
            model.arithm(optUB, ">=", minUtil / SCALE).post();
        }
    }

    /* ================= SOLVE ================= */

    private void solve(String outputFile) throws IOException {

        // Ensure output directory exists
        File outF = new File(outputFile);
        File parent = outF.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                throw new IOException("Cannot create output directory: " + parent);
            }
        }

        Solver solver = model.getSolver();
        solver.setSearch(buildSearch(model, x, varSel, valSel, computeItemTwu(), computeItemUtility()));
        solver.limitTime(TIMEOUT_MS);

        long startNs = System.nanoTime();

        long peakMemBytes = usedMemBytes();
        long count = 0;

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outF))) {

            while (solver.solve()) {
                count++;

                // write pattern to file in an SPMF-like style:
                bw.write(patternAsLine());
                bw.newLine();

                long cur = usedMemBytes();
                if (cur > peakMemBytes) peakMemBytes = cur;
            }
        }

        long timeMs = (System.nanoTime() - startNs) / 1_000_000L;
        double peakMemMB = peakMemBytes / (1024.0 * 1024.0);

        System.out.println("High utility itemsets count: " + count);
        System.out.println("Total time ~: " + timeMs + " ms");
        System.out.println("Max memory:" + peakMemMB);
    }

    private String patternAsLine() {
        StringBuilder sb = new StringBuilder();

        boolean first = true;
        for (int i = 0; i < nItems; i++) {
            if (x[i].getValue() == 1) {
                if (!first) sb.append(' ');
                sb.append(idxToItemId[i]);
                first = false;
            }
        }

        sb.append(" #UTIL: ").append(totalUtil.getValue());
        return sb.toString();
    }

    private static long usedMemBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private int[] computeItemTwu() {
        int[] twu = new int[nItems];
        for (int t = 0; t < nTx; t++) {
            int tu = TU[t];
            int[] items = txItemIdx.get(t);
            for (int item : items) {
                twu[item] += tu;
            }
        }
        return twu;
    }

    private int[] computeItemUtility() {
        int[] utility = new int[nItems];
        for (int t = 0; t < nTx; t++) {
            int[] items = txItemIdx.get(t);
            int[] utils = txUtils.get(t);
            for (int k = 0; k < items.length; k++) {
                utility[items[k]] += utils[k];
            }
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
