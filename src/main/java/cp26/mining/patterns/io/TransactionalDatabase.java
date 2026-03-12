/*
 * Enhanced TransactionalDatabase with HUI/TWU support including closure computation
 */
package cp26.mining.patterns.io;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a transactional database where each transaction is a set of items
 * Now with High Utility Itemset (HUI) support including TWU, closure, and utility data
 */
public class TransactionalDatabase {

    /** Name of items */
    private int[] items;
    /** TWU (Transaction Weighted Utility) for each item - for HUI mining */
    private int[] twu;
    /** Value of items */
    private int[][] values;
    /** Number of classes */
    private int nbClass;
    /** Vertical representation of the dataset */
    private BitSet[] verticalRepresentation;
    /** Number of transactions in the dataset */
    private int nbTransactions;
    /** Map each item to its position in items array */
    private Map<Integer, Integer> itemsMap;
    
    // NEW: HUI-specific fields
    /** Transaction utilities (TU) for each transaction - for HUI mining */
    private int[] transactionUtilities;
    /** External utility (profit) for each item - for HUI mining */
    private int[] itemExternalUtilities;
    /** Internal utility (quantity) for each item in each transaction - for HUI mining */
    private int[][] transactionItemUtilities;
    /** Minimum utility threshold for HUI mining */
    private int minUtil;
    
    // NEW: Closure computation cache
    /** Horizontal representation for closure computation */
    private BitSet[] horizontalRepresentation;
    /** Map from item ID to index in items array */
    private Map<Integer, Integer> itemIdToIndex;
    /** Total order R for items (as per paper Definition 16) */
    private int[] itemOrder;
    /** Precomputed closure for empty itemset */
    private BitSet emptyClosure;

    // Original constructor (for backward compatibility)
    public TransactionalDatabase(int[] items, int[][] values, int nbClass, 
                                BitSet[] verticalRepresentation, int nbTransactions) {
        this(items, values, nbClass, verticalRepresentation, nbTransactions, 
             null, null, null);
    }
    
    // NEW: Constructor with HUI data
    public TransactionalDatabase(int[] items, int[][] values, int nbClass,
                                BitSet[] verticalRepresentation, int nbTransactions,
                                int[] transactionUtilities,
                                int[] itemExternalUtilities,
                                int[][] transactionItemUtilities
                               ) {
        this.items = items;
        this.values = values;
        this.nbClass = nbClass;
        this.verticalRepresentation = verticalRepresentation;
        this.nbTransactions = nbTransactions;
        this.transactionUtilities = transactionUtilities;
        this.itemExternalUtilities = itemExternalUtilities;
        this.transactionItemUtilities = transactionItemUtilities;
        this.minUtil = 0; // Default, can be set later
        
        // Initialize structures
        initializeItemMaps();
        initializeHorizontalRepresentation();
        computeAllItemTWU();
        computeEmptyClosure();
    }
    
    private void initializeItemMaps() {
        itemsMap = new HashMap<>();
        itemIdToIndex = new HashMap<>();
        for (int i = 0; i < items.length; i++) {
            itemsMap.put(items[i], i);
            itemIdToIndex.put(items[i], i);
        }
        
        // Initialize item order (alphabetical by item ID, could be customized)
        itemOrder = Arrays.copyOf(items, items.length);
        Arrays.sort(itemOrder);
    }
    
    private void initializeHorizontalRepresentation() {
        if (verticalRepresentation == null) return;
        
        horizontalRepresentation = new BitSet[nbTransactions];
        for (int t = 0; t < nbTransactions; t++) {
            horizontalRepresentation[t] = new BitSet(items.length);
        }
        
        // Convert vertical to horizontal
        for (int i = 0; i < items.length; i++) {
            BitSet transactions = verticalRepresentation[i];
            if (transactions != null) {
                for (int t = transactions.nextSetBit(0); t >= 0; t = transactions.nextSetBit(t + 1)) {
                    if (t < nbTransactions) {
                        horizontalRepresentation[t].set(i);
                    }
                }
            }
        }
    }
    
    private void computeEmptyClosure() {
        if (horizontalRepresentation == null) {
            emptyClosure = new BitSet(items.length);
            return;
        }
        
        // Empty closure = intersection of all transactions
        if (nbTransactions > 0) {
            emptyClosure = (BitSet) horizontalRepresentation[0].clone();
            for (int t = 1; t < nbTransactions; t++) {
                emptyClosure.and(horizontalRepresentation[t]);
            }
        } else {
            emptyClosure = new BitSet(items.length);
        }
    }
    
    // Getters and setters
    public int[] getTwu() {
        return twu;
    }

    public void setTwu(int[] twu) {
        this.twu = twu;
    }
    
    // NEW: Getters for HUI data
    public int[] getTransactionUtilities() {
        return transactionUtilities;
    }
    
    public void setTransactionUtilities(int[] transactionUtilities) {
        this.transactionUtilities = transactionUtilities;
    }
    
    public int[] getItemExternalUtilities() {
        return itemExternalUtilities;
    }
    
    public void setItemExternalUtilities(int[] itemExternalUtilities) {
        this.itemExternalUtilities = itemExternalUtilities;
    }
    
    public int[][] getTransactionItemUtilities() {
        return transactionItemUtilities;
    }
    
    public void setTransactionItemUtilities(int[][] transactionItemUtilities) {
        this.transactionItemUtilities = transactionItemUtilities;
    }
    
    public int getMinUtil() {
        return minUtil;
    }
    
    public void setMinUtil(int minUtil) {
        this.minUtil = minUtil;
    }
    
    public boolean hasUtilityData() {
        return transactionUtilities != null && itemExternalUtilities != null && transactionItemUtilities != null;
    }
    
    public int[] getItemOrder() {
        return itemOrder;
    }
    
    public BitSet getEmptyClosure() {
        return emptyClosure;
    }
    
    // Original getters (unchanged)
    public BitSet[] getVerticalRepresentation() {
        return verticalRepresentation;
    }
    
    public BitSet[] getData() {
        return verticalRepresentation; // Alias for compatibility with PropTWUClosure
    }

    public int[][] getValues() {
        return values;
    }

    public int[] getItems() {
        return items;
    }

    public int getNbClass() {
        return nbClass;
    }

    public int getNbTransactions() {
        return nbTransactions;
    }

    public int getNbItems() {
        return items.length;
    }

    public long[][] getDatasetAsLongArray() {
        long[][] dataset = new long[items.length][];
        for (int i = 0; i < verticalRepresentation.length; i++) {
            dataset[i] = verticalRepresentation[i].toLongArray();
        }
        return dataset;
    }

    public double getDensity() {
        double nbSetTransactions = Arrays.stream(verticalRepresentation).mapToInt(BitSet::cardinality).sum();
        return nbSetTransactions / (items.length * nbTransactions);
    }
    /**
     * Converts a BitSet of internal indices into an array of actual Item IDs.
     */
    public int[] getItemsFromBitSet(BitSet bitset) {
        int[] result = new int[bitset.cardinality()];
        int count = 0;
        for (int i = bitset.nextSetBit(0); i >= 0; i = bitset.nextSetBit(i + 1)) {
            // 'items' is the array containing actual item IDs from the file
            result[count++] = items[i];
        }
        return result;
    }
    /**
     * Associate each item to its index in the array
     * @return map
     */
    public Map<Integer, Integer> getItemsMap() {
        if (itemsMap == null) {
            initializeItemMaps();
        }
        return itemsMap;
    }

    /**
     * Compute the frequency of each item
     * @return an array with the frequency of each item
     */
    public int[] computeItemFreq() {
        return Arrays.stream(verticalRepresentation).mapToInt(BitSet::cardinality).toArray();
    }

    /**
     * Get class count
     * @return an array of size 2 : the first index represents the number of transactions of the first class and
     * the second one represents the number of transactions that are not in the first class
     */
    public int[] getClassCount() {
        int d1 = verticalRepresentation[0].cardinality();
        int d2 = nbTransactions - d1;
        return new int[]{d1, d2};
    }
    
    // NEW: TWU computation methods
    
    /**
     * Compute TWU (Transaction Weighted Utility) for an itemset
     * Based on Definition 13 from the paper: TWU(X) = Σ_{r∈TidSet(X)} TU(T_r)
     */
    public int computeTWU(BitSet itemset) {
        if (transactionUtilities == null) {
            recalculateTransactionUtilities(); // Ensure data exists
        }
        
        BitSet transactions = getTransactionsContainingPattern(itemset);
        int twuSum = 0;
        for (int t = transactions.nextSetBit(0); t >= 0; t = transactions.nextSetBit(t + 1)) {
            twuSum += transactionUtilities[t];
        }
        return twuSum;
    }
    
    /**
     * Compute TWU for an itemset represented by item indices
     */
    public int computeTWU(int... itemIndices) {
        BitSet itemset = new BitSet(items.length);
        for (int idx : itemIndices) {
            if (idx < items.length) {
                itemset.set(idx);
            }
        }
        return computeTWU(itemset);
    }
    
    /**
     * Compute item TWU (for individual items)
     * Based on TWU definition but for single items
     */
    public int computeItemTWU(int itemIdx) {
        if (transactionUtilities == null || itemIdx >= verticalRepresentation.length) {
            return 0;
        }
        
        int itemTWU = 0;
        BitSet transactions = verticalRepresentation[itemIdx];
        
        for (int t = transactions.nextSetBit(0); t >= 0; t = transactions.nextSetBit(t + 1)) {
            if (t < transactionUtilities.length) {
                itemTWU += transactionUtilities[t];
            }
        }
        
        return itemTWU;
    }
    
    /**
     * Compute and cache TWU for all items
     */
    public void computeAllItemTWU() {
        if (transactionUtilities == null) return;
        
        twu = new int[items.length];
        for (int i = 0; i < items.length; i++) {
            twu[i] = computeItemTWU(i);
        }
    }
    
    // NEW: Exact utility computation methods
    
    /**
     * Compute exact utility of an itemset
     */
    /**
     * Compute exact utility of an itemset
     * Based on Definition 4 (page 6) of the survey: u(X) = Σ_{Tq∈g(X)} u(X, Tq)
     */
    public int computeExactUtility(BitSet itemset) {
        if (!hasUtilityData()) return 0;
        
        // Tidset: Transactions containing the pattern
        BitSet transactions = getTransactionsContainingPattern(itemset);
        int totalUtility = 0;
        
        for (int t = transactions.nextSetBit(0); t >= 0; t = transactions.nextSetBit(t + 1)) {
            int transactionSum = 0;
            for (int i = itemset.nextSetBit(0); i >= 0; i = itemset.nextSetBit(i + 1)) {
                
                // Ensure the item actually exists in this transaction's utility map
                if (i < itemExternalUtilities.length && i < transactionItemUtilities[t].length) {
                    int profit = itemExternalUtilities[i];
                    int value = transactionItemUtilities[t][i]; 
                    
                    // Logic: u(i, Tc) = p(i) * q(i, Tc)
                    // If using SPMF format, profit is 1 and value is the utility
                    transactionSum += (profit * value);
                }
            }
            totalUtility += transactionSum;
        }
        return totalUtility;
    }

    /**
     * Recalculate TUs (Transaction Utilities)
     * Based on Definition 7 (page 11) of the survey: TU(Tq) = Σ_{i∈Tq} u(i, Tq)
     */
    public void recalculateTransactionUtilities() {
        this.transactionUtilities = new int[nbTransactions];
        for (int t = 0; t < nbTransactions; t++) {
            int tu = 0;
            for (int i = 0; i < items.length; i++) {
                if (verticalRepresentation[i].get(t)) {
                    int profit = itemExternalUtilities[i];
                    int value = transactionItemUtilities[t][i];
                    tu += (profit * value);
                }
            }
            this.transactionUtilities[t] = tu;
        }
    }

    /**
     * Compute PU (Pivot Utility) - Corrected for Definition 20
     */
    public int computePivotUtility(BitSet pattern) {
        int pivot = findPivot(pattern);
        if (pivot < 0) return 0;
        
        BitSet transactions = getTransactionsContainingPattern(pattern);
        int totalPU = 0;
        
        for (int t = transactions.nextSetBit(0); t >= 0; t = transactions.nextSetBit(t + 1)) {
            // PU is the utility of all items appearing AFTER the pivot 
            // in the transactions containing the pattern.
            for (int i = pivot + 1; i < items.length; i++) {
                if (verticalRepresentation[i].get(t)) {
                    totalPU += (itemExternalUtilities[i] * transactionItemUtilities[t][i]);
                }
            }
        }
        return totalPU;
    }
    
    // NEW: Closure computation methods
    
    /**
     * Check if an item is in the closure of a pattern
     * Based on closure definition from the paper
     */
    public boolean itemInClosure(BitSet pattern, int itemIdx) {
        if (horizontalRepresentation == null || itemIdx >= items.length) {
            return false;
        }
        
        BitSet patternTransactions = getTransactionsContainingPattern(pattern);
        BitSet itemTransactions = verticalRepresentation[itemIdx];
        
        BitSet temp = (BitSet) patternTransactions.clone();
        temp.andNot(itemTransactions);
        return temp.isEmpty();
    }
    
    /**
     * Compute closure of an itemset
     * Based on Definition 8 from the paper: closure(X) = ∩_{r∈TidSet(X)} T_r
     */
    public BitSet computeClosure(BitSet pattern) {
        if (horizontalRepresentation == null) {
            return (BitSet) pattern.clone(); // Return pattern itself as fallback
        }
        
        BitSet transactions = getTransactionsContainingPattern(pattern);
        
        if (transactions.isEmpty()) {
            return new BitSet(items.length); // Empty closure
        }
        
        // Find first transaction to initialize intersection
        int firstT = transactions.nextSetBit(0);
        BitSet closure = (BitSet) horizontalRepresentation[firstT].clone();
        
        // Intersect with all other transactions containing the pattern
        for (int t = transactions.nextSetBit(firstT + 1); t >= 0; t = transactions.nextSetBit(t + 1)) {
            closure.and(horizontalRepresentation[t]);
        }
        
        return closure;
    }
    
    /**
     * Compute Tidset (transaction IDs containing pattern)
     * Based on Definition 1 from the paper
     */
    public BitSet getTransactionsContainingPattern(BitSet pattern) {
        if (verticalRepresentation == null || pattern.isEmpty()) {
            // Empty pattern is contained in all transactions
            BitSet allTransactions = new BitSet(nbTransactions);
            allTransactions.set(0, nbTransactions);
            return allTransactions;
        }
        
        int firstItem = pattern.nextSetBit(0);
        if (firstItem < 0 || firstItem >= verticalRepresentation.length) {
            return new BitSet(nbTransactions);
        }
        
        BitSet transactions = (BitSet) verticalRepresentation[firstItem].clone();
        
        for (int i = pattern.nextSetBit(firstItem + 1); i >= 0; i = pattern.nextSetBit(i + 1)) {
            if (i < verticalRepresentation.length) {
                transactions.and(verticalRepresentation[i]);
            }
        }
        
        return transactions;
    }
    
    /**
     * Compute support count of an itemset
     * Based on Definition 1 from the paper
     */
    public int computeSupport(BitSet pattern) {
        return getTransactionsContainingPattern(pattern).cardinality();
    }
    
    // NEW: HUI checking methods
    
    /**
     * Check if a pattern is a High Utility Itemset (HUI)
     */
    public boolean isHUI(BitSet pattern) {
        if (!hasUtilityData()) return false;
        
        int utility = computeExactUtility(pattern);
        return utility >= minUtil;
    }
    
    /**
     * Check if pattern is closed (has no proper superset with same support)
     * Based on Definition 7 from the paper
     */
    public boolean isClosed(BitSet pattern) {
        BitSet closure = computeClosure(pattern);
        return pattern.equals(closure);
    }
    
    /**
     * Check if pattern is closed HUI
     * Based on Definition 9 from the paper
     */
    public boolean isClosedHUI(BitSet pattern) {
        return isHUI(pattern) && isClosed(pattern);
    }
    
    /**
     * Check if pattern is maximal HUI
     * Based on Definition 12 from the paper
     */
    public boolean isMaximalHUI(BitSet pattern) {
        if (!isHUI(pattern)) {
            return false;
        }
        
        // Check if any proper superset is also HUI
        BitSet complement = new BitSet(items.length);
        complement.set(0, items.length, true);
        complement.andNot(pattern);
        
        for (int i = complement.nextSetBit(0); i >= 0; i = complement.nextSetBit(i + 1)) {
            BitSet superset = (BitSet) pattern.clone();
            superset.set(i);
            if (isHUI(superset)) {
                return false; // Found HUI superset, so not maximal
            }
        }
        
        return true;
    }
    
    // NEW: Utility unit array computation (for closed+ HUI)
    
    /**
     * Compute utility unit array for an itemset
     * Based on Definition 10 from the paper
     */
    public int[] computeUtilityUnitArray(BitSet pattern) {
        if (!hasUtilityData()) return new int[0];
        
        int[] utilityArray = new int[pattern.cardinality()];
        int idx = 0;
        
        BitSet transactions = getTransactionsContainingPattern(pattern);
        
        for (int i = pattern.nextSetBit(0); i >= 0; i = pattern.nextSetBit(i + 1)) {
            int itemUtility = 0;
            for (int t = transactions.nextSetBit(0); t >= 0; t = transactions.nextSetBit(t + 1)) {
                if (t < transactionItemUtilities.length && i < transactionItemUtilities[t].length) {
                    int profit = itemExternalUtilities[i];
                    int quantity = transactionItemUtilities[t][i];
                    itemUtility += profit ;
                }
            }
            utilityArray[idx++] = itemUtility;
        }
        
        return utilityArray;
    }
    
    // NEW: Pivot-related methods (for PUDC property)
    
    /**
     * Find pivot of an itemset (smallest item according to total order)
     * Based on Definition 17 from the paper
     */
    public int findPivot(BitSet pattern) {
        if (pattern.isEmpty()) return -1;
        
        // Items in pattern are stored by index, not by item ID in order
        // We need to find which item index corresponds to the smallest item in order
        int pivotIndex = -1;
        int smallestOrder = Integer.MAX_VALUE;
        
        for (int i = pattern.nextSetBit(0); i >= 0; i = pattern.nextSetBit(i + 1)) {
            // Find position of item i in itemOrder
            int itemId = items[i];
            for (int j = 0; j < itemOrder.length; j++) {
                if (itemOrder[j] == itemId) {
                    if (j < smallestOrder) {
                        smallestOrder = j;
                        pivotIndex = i;
                    }
                    break;
                }
            }
        }
        
        return pivotIndex;
    }
    
   
    
}