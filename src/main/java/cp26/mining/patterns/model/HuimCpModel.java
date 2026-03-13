package cp26.mining.patterns.model;

import cp26.mining.patterns.search.strategy.selectors.variables.ItemUtilityAsc;
import cp26.mining.patterns.search.strategy.selectors.variables.ItemUtilityDesc;
import cp26.mining.patterns.search.strategy.selectors.variables.TwuAsc;
import cp26.mining.patterns.search.strategy.selectors.variables.TwuDesc;
import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solver;
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMax;
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMin;
import org.chocosolver.solver.search.strategy.selectors.values.IntValueSelector;
import org.chocosolver.solver.search.strategy.selectors.variables.DomOverWDeg;
import org.chocosolver.solver.search.strategy.selectors.variables.FirstFail;
import org.chocosolver.solver.search.strategy.selectors.variables.InputOrder;
import org.chocosolver.solver.search.strategy.selectors.variables.VariableSelector;
import org.chocosolver.solver.variables.BoolVar;
import org.chocosolver.solver.variables.IntVar;
import org.chocosolver.solver.search.strategy.Search;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class HuimCpModel {

    private static String filePath;
    private static String outPath = null;
    private static int minUtil = 10;
    // user constraints (CLI)
    private static String includeStr = null;
    private static String excludeStr = null;
    private static int minSize = 1;
    private static int maxSize = -1;
    private static String varSel = "input";
    private static String valSel = "max";
    private static String modeStr = "HUI";
    private static int depthK = 3;
    private static int maxUtil = -1;
    private static int tuMin = Integer.MIN_VALUE;
    private static int tuMax = Integer.MAX_VALUE;
    private static boolean useOptBound = false;

    // 20 minutes timeout
    private static final long TIMEOUT_MS = 20L * 60L * 1000L;
    private static final int SCALE = 50;

    private int nTx, nItems;
    private int maxTxLength = 0;

    private List<int[]> txItemIdx = new ArrayList<>();
    private List<int[]> txUtils = new ArrayList<>();

    private int[] TU, scaledTU;
    private int[] supp, uMax, scaledUMax;

    private boolean[] allowedTx;

    private Map<Integer,Integer> itemIdToIdx = new HashMap<>();
    private int[] idxToItemId;

    private Model model;
    private BoolVar[] x, occ;
    private IntVar totalUtil;

    public static void main(String[] args) throws Exception {
        // Defaults for IDE run
        filePath = "datasets/test_dataset/cp26.txt";
        outPath = "datasets/output_huim_cp.txt";
        minUtil = 0;
        modeStr = "UPI";
        depthK = 3;

        // Positional args:
        // 0 file, 1 output, 2 minUtil, 3 mode, 4 depthK,
        // 5 minSize, 6 maxSize, 7 varSel, 8 valSel,
        // 9 maxUtil, 10 tuMin, 11 tuMax, 12 useOptBound,
        // 13 includeCsv, 14 excludeCsv
        if (args.length > 0) filePath = args[0];
        if (args.length > 1) outPath = args[1];
        if (args.length > 2) minUtil = Integer.parseInt(args[2]);
        if (args.length > 3) modeStr = args[3];
        if (args.length > 4) depthK = Integer.parseInt(args[4]);
        if (args.length > 5) minSize = Integer.parseInt(args[5]);
        if (args.length > 6) maxSize = Integer.parseInt(args[6]);
        if (args.length > 7) varSel = args[7];
        if (args.length > 8) valSel = args[8];
        if (args.length > 9) maxUtil = Integer.parseInt(args[9]);
        if (args.length > 10) tuMin = Integer.parseInt(args[10]);
        if (args.length > 11) tuMax = Integer.parseInt(args[11]);
        if (args.length > 12) useOptBound = Boolean.parseBoolean(args[12]);
        if (args.length > 13) includeStr = args[13];
        if (args.length > 14) excludeStr = args[14];

        HuimCpModel h = new HuimCpModel();
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

        // Constraint 1: itemset size is within [minSize, maxSize] and <= max transaction length
        int lbSize = Math.max(1, minSize);
        int ubSize = (maxSize > 0) ? Math.min(maxTxLength, maxSize) : maxTxLength;
        if (lbSize > ubSize) {
            lbSize = 1;
            ubSize = 0;
        }
        IntVar len = model.intVar(lbSize, ubSize);
        model.sum(x, "=", len).post();

        // Constraint 2: inclusion constraints (force specific items to be selected)
        int[] includeIds = parseCsvIds(includeStr);
        for (int id : includeIds) {
            Integer idx = itemIdToIdx.get(id);
            if (idx != null) model.arithm(x[idx], "=", 1).post();
        }

        // Constraint 3: exclusion constraints (force specific items to be absent)
        int[] excludeIds = parseCsvIds(excludeStr);
        for (int id : excludeIds) {
            Integer idx = itemIdToIdx.get(id);
            if (idx != null) model.arithm(x[idx], "=", 0).post();
        }

        IntVar[] sumXByT = new IntVar[nTx];
        IntVar[] uTxByT = new IntVar[nTx];
        int[] maxUTx = new int[nTx];
        IntVar[] contrib = new IntVar[nTx];

        for (int t = 0; t < nTx; t++) {
            int[] items = txItemIdx.get(t);
            int[] utils = txUtils.get(t);

            // Constraint 4: chosen items of transaction t (link x to transaction items)
            IntVar[] chosen = new IntVar[items.length];
            for (int i = 0; i < items.length; i++) {
                chosen[i] = x[items[i]];
            }

            // Constraint 5: sumX = number of selected items present in transaction t
            IntVar sumX = model.intVar(0, items.length);
            model.sum(chosen, "=", sumX).post();
            sumXByT[t] = sumX;

            // Constraint 6: occ[t] = 1 iff transaction t contains all selected items (sumX == len)
            model.arithm(sumX, "=", len).reifyWith(occ[t]);

            // Constraint 7: TU filter (force occ[t]=0 if TU is outside [tuMin, tuMax])
            if (!allowedTx[t]) {
                model.arithm(occ[t], "=", 0).post();
            }

            // Constraint 8: uTx = utility of selected items inside transaction t
            IntVar[] uTerms = new IntVar[items.length];
            int maxU = 0;
            for (int i = 0; i < items.length; i++) {
                uTerms[i] = model.intScaleView(x[items[i]], utils[i]);
                maxU += utils[i];
            }
            maxUTx[t] = maxU;
            IntVar uTx = model.intVar(0, maxU);
            model.sum(uTerms, "=", uTx).post();
            uTxByT[t] = uTx;

            // Constraint 9: contrib[t] = occ[t] * uTx (utility contributed by transaction t)
            contrib[t] = model.intVar(0, maxU);
            model.times(occ[t], uTx, contrib[t]).post();
        }

        // Constraint 10: totalUtil = sum of transaction contributions
        long ub = 0;
        for (int t = 0; t < nTx; t++) {
            for (int u : txUtils.get(t)) ub += u;
        }
        if (ub > Integer.MAX_VALUE) ub = Integer.MAX_VALUE;

        totalUtil = model.intVar(0, (int) ub);
        model.sum(contrib, "=", totalUtil).post();

        // Constraint 11: HUI threshold totalUtil >= minUtil
        model.arithm(totalUtil, ">=", minUtil).post();

        // Constraint 12: optional utility upper bound totalUtil <= maxUtil
        if (maxUtil > 0) {
            model.arithm(totalUtil, "<=", maxUtil).post();
        }

        // Constraint 13: TWU bound sum(occ[t]*scaledTU[t]) >= minUtil/SCALE
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

        // Constraint 14: optimistic bound minSupp * sumMaxU >= minUtil/SCALE
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

        // Constraint 15: UPI constraints (all subsets and supersets have lower utility)
        if ("UPI".equalsIgnoreCase(modeStr)) {
            addUPIConstraints(len, sumXByT, uTxByT, maxUTx);
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

        sb.append(" #UTIL: ").append(computePatternUtility());
        return sb.toString();
    }

    private int computePatternUtility() {
        boolean[] selected = new boolean[nItems];
        int selectedCount = 0;
        for (int i = 0; i < nItems; i++) {
            if (x[i].getValue() == 1) {
                selected[i] = true;
                selectedCount++;
            }
        }
        if (selectedCount == 0) return 0;

        int total = 0;
        for (int t = 0; t < nTx; t++) {
            int[] items = txItemIdx.get(t);
            int[] utils = txUtils.get(t);
            int match = 0;
            int sum = 0;
            for (int k = 0; k < items.length; k++) {
                int item = items[k];
                if (selected[item]) {
                    match++;
                    sum += utils[k];
                }
            }
            if (match == selectedCount) {
                total += sum;
            }
        }
        return total;
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

    private void addUPIConstraints(IntVar len, IntVar[] sumXByT, IntVar[] uTxByT, int[] maxUTx) {
        int[][] utilInTx = new int[nTx][nItems];
        for (int t = 0; t < nTx; t++) {
            int[] items = txItemIdx.get(t);
            int[] utils = txUtils.get(t);
            for (int k = 0; k < items.length; k++) {
                utilInTx[t][items[k]] = utils[k];
            }
        }

        // UPI-Subset constraints: for each item i in P, enforce u(P \\ {i}) < u(P)
        for (int i = 0; i < nItems; i++) {
            // lenMinus = len - x[i] = |P \\ {i}|
            IntVar lenMinus = model.intVar(0, nItems);
            model.sum(new IntVar[]{lenMinus, x[i]}, "=", len).post();

            IntVar[] contribMinus = new IntVar[nTx];
            for (int t = 0; t < nTx; t++) {
                IntVar sumXMinus;
                if (utilInTx[t][i] > 0) {
                    sumXMinus = model.intVar(0, maxTxLength);
                    model.sum(new IntVar[]{sumXMinus, x[i]}, "=", sumXByT[t]).post();
                } else {
                    sumXMinus = sumXByT[t];
                }

                // occMinus = 1 iff transaction t contains P \\ {i}
                BoolVar occMinus = model.boolVar();
                model.arithm(sumXMinus, "=", lenMinus).reifyWith(occMinus);

                IntVar uTxMinus;
                if (utilInTx[t][i] > 0) {
                    IntVar term = model.intScaleView(x[i], utilInTx[t][i]);
                    uTxMinus = model.intVar(0, maxUTx[t]);
                    model.sum(new IntVar[]{uTxMinus, term}, "=", uTxByT[t]).post();
                } else {
                    uTxMinus = uTxByT[t];
                }

                // contribMinus[t] = occMinus * uTxMinus
                contribMinus[t] = model.intVar(0, maxUTx[t]);
                model.times(occMinus, uTxMinus, contribMinus[t]).post();
            }
            // totalMinus = sum of contributions for P \\ {i}
            IntVar totalMinus = model.intVar(0, totalUtil.getUB());
            model.sum(contribMinus, "=", totalMinus).post();
            // enforce u(P \\ {i}) < u(P)
            model.arithm(totalMinus, "<", totalUtil).post();
        }

        // UPI-Superset constraints: for each item j not in P, enforce u(P ∪ {j}) < u(P)
        for (int j = 0; j < nItems; j++) {
            // oneMinus = 1 - x[j] (activates when j is NOT selected)
            IntVar oneMinus = model.intVar(0, 1);
            model.arithm(oneMinus, "+", x[j], "=", 1).post();
            // lenPlus = len + (1 - x[j]) = |P ∪ {j}|
            IntVar lenPlus = model.intVar(0, nItems);
            model.sum(new IntVar[]{len, oneMinus}, "=", lenPlus).post();

            IntVar[] contribPlus = new IntVar[nTx];
            for (int t = 0; t < nTx; t++) {
                IntVar sumXPlus;
                IntVar addJ;
                if (utilInTx[t][j] > 0) {
                    addJ = oneMinus;
                    sumXPlus = model.intVar(0, maxTxLength + 1);
                    model.sum(new IntVar[]{sumXByT[t], addJ}, "=", sumXPlus).post();
                } else {
                    addJ = model.intVar(0, 0);
                    sumXPlus = sumXByT[t];
                }

                // occPlus = 1 iff transaction t contains P ∪ {j}
                BoolVar occPlus = model.boolVar();
                model.arithm(sumXPlus, "=", lenPlus).reifyWith(occPlus);

                // uTxPlus = utility of P ∪ {j} inside transaction t
                IntVar uTxPlus;
                if (utilInTx[t][j] > 0) {
                    IntVar term = model.intScaleView(oneMinus, utilInTx[t][j]);
                    uTxPlus = model.intVar(0, maxUTx[t] + utilInTx[t][j]);
                    model.sum(new IntVar[]{uTxByT[t], term}, "=", uTxPlus).post();
                } else {
                    uTxPlus = uTxByT[t];
                }

                // contribPlus[t] = occPlus * uTxPlus
                contribPlus[t] = model.intVar(0, maxUTx[t] + utilInTx[t][j]);
                model.times(occPlus, uTxPlus, contribPlus[t]).post();
            }
            // totalPlus = sum of contributions for P ∪ {j}
            IntVar totalPlus = model.intVar(0, totalUtil.getUB() + 1);
            model.sum(contribPlus, "=", totalPlus).post();
            // enforce u(P ∪ {j}) < u(P)
            model.arithm(totalPlus, "<", totalUtil).post();
        }
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
