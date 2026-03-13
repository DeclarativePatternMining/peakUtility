package cp26.mining.examples.tools;

import cp26.mining.examples.PeakUtility;
import cp26.mining.patterns.constraints.PropPeakUtility;
import cp26.mining.patterns.io.HUIReader;
import cp26.mining.patterns.io.TransactionalDatabase;
import cp26.mining.patterns.util.UtilityList_0;
import cp26.mining.patterns.util.Custom_Element;
import org.chocosolver.solver.Model;
import org.chocosolver.solver.constraints.Constraint;
import org.chocosolver.solver.exception.ContradictionException;
import org.chocosolver.solver.variables.BoolVar;

import java.util.BitSet;

/**
 * Test a single pattern against PropPeakUtility with a given rules mask.
 *
 * Usage:
 *   java PeakPatternRuleTester <dataset> <minUtil> <rulesMask> <itemsCsv>
 *
 * itemsCsv: comma-separated item indices (e.g., "1,5,10")
 */
public class PeakPatternRuleTester {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: java PeakPatternRuleTester <dataset> <minUtil> <rulesMask> <itemsCsv>");
            System.exit(1);
        }

        String dataset = args[0];
        int minUtil = Integer.parseInt(args[1]);
        String rulesMask = args[2];
        String itemsCsv = args[3];

        int[] items = parseItems(itemsCsv);

        TransactionalDatabase db = new HUIReader(dataset).read_hui();
        int nbItems = db.getNbItems();
        int nbTrans = db.getNbTransactions();
        int[][] itemUtils = db.getTransactionItemUtilities();
        int[] transactionUtilities = db.getTransactionUtilities();

        UtilityList_0[] itemULs = buildUtilityLists(db, itemUtils, nbItems, nbTrans);

        Model model = new Model("PeakPatternRuleTester");
        BoolVar[] x = model.boolVarArray("x", nbItems);

        int rulesMaskInt = parseRulesMask(rulesMask);
        PropPeakUtility propagator = new PropPeakUtility(
                x, minUtil, 3, transactionUtilities, itemULs, transactionUtilities.length, PropPeakUtility.Mode.UPI, rulesMaskInt
        );
        new Constraint("PeakUtility", propagator).post();

        boolean[] inPattern = new boolean[nbItems];
        for (int item : items) {
            if (item < 0 || item >= nbItems) {
                System.err.println("Item out of range: " + item);
                System.exit(2);
            }
            inPattern[item] = true;
        }

        for (int i = 0; i < nbItems; i++) {
            if (inPattern[i]) {
                x[i].instantiateTo(1, null);
            } else {
                x[i].instantiateTo(0, null);
            }
        }

        try {
            propagator.propagate(0);
            System.out.println("OK (no contradiction).");
        } catch (ContradictionException e) {
            System.out.println("FAIL rule=" + propagator.getLastFailRule());
        }
    }

    private static int[] parseItems(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return new int[0];
        }
        String[] parts = csv.split(",");
        int[] items = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            items[i] = Integer.parseInt(parts[i].trim());
        }
        return items;
    }

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

    private static UtilityList_0[] buildUtilityLists(TransactionalDatabase database, int[][] itemUtils,
                                                     int nbItems, int nbTrans) {
        UtilityList_0[] itemULs = new UtilityList_0[nbItems];
        for (int i = 0; i < nbItems; i++) {
            itemULs[i] = new UtilityList_0(i);
        }

        BitSet[] vertical = database.getVerticalRepresentation();
        for (int i = 0; i < nbItems; i++) {
            BitSet tids = vertical[i];
            for (int t = tids.nextSetBit(0); t >= 0; t = tids.nextSetBit(t + 1)) {
                int iutil = itemUtils[t][i];
                itemULs[i].elements.add(new Custom_Element(t, iutil, 0));
            }
        }
        return itemULs;
    }
}
