package cp26.mining.patterns.io;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

/**
 * Reader for transactional database files in DAT format.
 *
 * <p>This class reads both traditional basket mining and high-utility itemset (HUI)
 * mining formats. It supports two input file formats:
 *
 * <h3>Format 1: Traditional Items Format</h3>
 * <pre>
 *   {1 3 4}          (transaction contains items 1, 3, 4)
 *   {2 4 5}          (transaction contains items 2, 4, 5)
 *   {1 3}            (transaction contains items 1, 3)
 * </pre>
 *
 * <h3>Format 2: High-Utility Item Format (with utilities)</h3>
 * <p>Each transaction specifies items, transaction utility (TU), and item utilities:
 * <pre>
 *   items -1 transaction_utility -1 item_utilities 0
 *   1 2 3 -1 50 -1 10 20 30 0
 *   |_____|    |_|    |___________|
 *   items      TU     item utilities
 * </pre>
 *
 * <p>Alternative format with ":" separator (automatically converted internally):
 * <pre>
 *   1 2 3 : 50 : 10 20 30
 * </pre>
 *
 * <h3>Data Structures Built</h3>
 * <ul>
 *   <li><strong>Vertical representation:</strong> BitSet per item for fast transaction lookup</li>
 *   <li><strong>Item mapping:</strong> Maps file item IDs to normalized indices (0 to nbItems-1)</li>
 *   <li><strong>Utility arrays:</strong> Transaction utilities and item utilities</li>
 *   <li><strong>Transaction-item matrix:</strong> Internal utilities per transaction-item pair</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>
 * // For traditional basket mining
 * HUIReader reader = new HUIReader("datasets/items.txt");
 * TransactionalDatabase db = reader.read();
 *
 * // For high-utility itemset mining
 * HUIReader huiReader = new HUIReader("datasets/foodmart.txt");
 * TransactionalDatabase huiDb = huiReader.read_hui();
 * </pre>
 *
 * @version 1.0
 * @since 2026
 * @see TransactionalDatabase
 * @see DataReader
 */
public class HUIReader extends DataReader {

    // =========================================================================
    // INTERNAL DATA STRUCTURES
    // =========================================================================

    /**
     * Sorted array of all item IDs found in the database.
     *
     * <p>Items are sorted in increasing order for consistent processing.
     * Used to build the item-to-index mapping via {@link #itemIdToIndexMap}.
     */
    private int[] sortedItemIds;

    /**
     * Maps original item IDs (from file) to normalized indices (0 to nbItems-1).
     *
     * <p>Example: If items in file are {1, 5, 10, 100}, the mapping would be:
     * <ul>
     *   <li>1 → 0</li>
     *   <li>5 → 1</li>
     *   <li>10 → 2</li>
     *   <li>100 → 3</li>
     * </ul>
     *
     * <p>This normalization enables efficient BitSet and array-based representations.
     */
    private Map<Integer, Integer> itemIdToIndexMap;

    /**
     * Transaction utilities (TU) for each transaction.
     *
     * <p>TU[t] = sum of all item utilities in transaction t.
     * Used as an upper bound for high-utility pruning.
     */
    private int[] transactionUtilities;

    /**
     * External utilities (profit per unit) for each item.
     *
     * <p>For traditional basket mining, typically all 1's.
     * For HUI mining, these are the unit profit values for each item.
     */
    private int[] itemExternalUtilities;

    /**
     * Internal utilities (quantities) per transaction-item pair.
     *
     * <p>transactionItemUtilities[t][i] = utility of item i in transaction t.
     * This is usually quantity × unit profit, but can represent any utility metric.
     */
    private int[][] transactionItemUtilities;

    /**
     * Vertical representation: one BitSet per item.
     *
     * <p>data[i] contains the transactions where item i appears.
     * Enables efficient transaction lookup for a given item.
     */
    private BitSet[] verticalDataRepresentation;

    // =========================================================================
    // CONSTRUCTORS
    // =========================================================================

    /**
     * Creates a reader for a DAT format file with specified value measures.
     *
     * @param dataPath path to the database file
     * @param numberOfValueMeasures number of associated value measure files
     */
    public HUIReader(String dataPath, int numberOfValueMeasures) {
        super(dataPath, numberOfValueMeasures);
    }

    /**
     * Creates a reader for a DAT format file with optional class labels.
     *
     * @param dataPath path to the database file
     * @param numberOfValueMeasures number of associated value measure files
     * @param noClasses whether to ignore class labels
     */
    public HUIReader(String dataPath, int numberOfValueMeasures, boolean noClasses) {
        super(dataPath, numberOfValueMeasures, noClasses);
    }

    /**
     * Creates a reader for a DAT format file (no value measures).
     *
     * @param dataPath path to the database file
     */
    public HUIReader(String dataPath) {
        super(dataPath);
    }

    // =========================================================================
    // PRIVATE HELPER METHODS
    // =========================================================================

    /**
     * First pass: Discovers all unique items and counts transactions in traditional format.
     *
     * <p>Populates:
     * <ul>
     *   <li>{@link #sortedItemIds}: Sorted array of unique items</li>
     *   <li>{@link #itemIdToIndexMap}: Mapping from item ID to index</li>
     *   <li>{@link #nbItems}: Count of unique items</li>
     *   <li>{@link #nbTransactions}: Count of transactions</li>
     * </ul>
     *
     * @throws IOException if an error occurs reading the file
     */
    private void loadItems() throws IOException {
        String line;
        Set<Integer> itemSet = new HashSet<>();
        nbTransactions = 0;
        
        try (BufferedReader reader = new BufferedReader(new FileReader(dataPath))) {
            while ((line = reader.readLine()) != null) {
                if (skipLine(line))
                    continue;
                
                String[] itemTokens = line.split(" ");
                for (String itemToken : itemTokens) {
                    itemSet.add(Integer.valueOf(itemToken));
                }
                nbTransactions++;
            }
            
            // Build sorted item array and mapping
            sortedItemIds = itemSet.stream().mapToInt(Integer::intValue).toArray();
            Arrays.sort(sortedItemIds);
            nbItems = sortedItemIds.length;
            
            itemIdToIndexMap = new HashMap<>();
            for (int index = 0; index < sortedItemIds.length; index++) {
                itemIdToIndexMap.put(sortedItemIds[index], index);
            }
        }
    }

    /**
     * Computes Transaction-Weighted Utility (TWU) for a single item.
     *
     * <p>TWU(item i) = Σ(TU(t)) for all transactions t containing i.
     * This upper bound is used for pruning in HUI mining.
     *
     * @param itemIndex the normalized item index
     * @param verticalData the vertical database representation
     * @param transactionUtilityValues array of transaction utilities
     *
     * @return the TWU value for this item
     */
    private int computeItemTWU(int itemIndex, BitSet[] verticalData,
                               int[] transactionUtilityValues) {
        int twuValue = 0;
        BitSet transactionsWithItem = verticalData[itemIndex];
        
        for (int transactionId = transactionsWithItem.nextSetBit(0);
             transactionId >= 0;
             transactionId = transactionsWithItem.nextSetBit(transactionId + 1)) {
            twuValue += transactionUtilityValues[transactionId];
        }
        return twuValue;
    }

    // =========================================================================
    // PUBLIC API: DATABASE READING
    // =========================================================================

    /**
     * Reads a traditional basket mining database (no utilities).
     *
     * <p>File format: One transaction per line with space-separated items.
     *
     * @return a populated TransactionalDatabase object
     *
     * @throws IOException if an error occurs reading the file
     */
    @Override
    public TransactionalDatabase read() throws IOException {
        loadItems();
        
        BitSet[] verticalData = new BitSet[nbItems];
        for (int itemIndex = 0; itemIndex < nbItems; itemIndex++) {
            verticalData[itemIndex] = new BitSet(nbTransactions);
        }
        
        int maxClass = 1;
        
        try (BufferedReader reader = new BufferedReader(new FileReader(dataPath))) {
            String line;
            int currentTransactionId = 0;
            
            while ((line = reader.readLine()) != null) {
                if (skipLine(line))
                    continue;
                
                String[] itemTokens = line.split(" ");
                int firstItemClass = itemIdToIndexMap.get(Integer.parseInt(itemTokens[0])) + 1;
                maxClass = Math.max(firstItemClass, maxClass);
                
                for (String itemToken : itemTokens) {
                    int normalizedItemIndex = itemIdToIndexMap.get(Integer.parseInt(itemToken));
                    verticalData[normalizedItemIndex].set(currentTransactionId);
                }
                currentTransactionId++;
            }
        }
        
        return new TransactionalDatabase(
            sortedItemIds,
            readValueFiles(),
            noClasses ? 0 : maxClass,
            verticalData,
            nbTransactions
        );
    }

    /**
     * Reads a high-utility itemset mining database.
     *
     * <p>File format supports two variants:
     * <ol>
     *   <li>"items -1 TU -1 utilities 0" (space-separated)</li>
     *   <li>"items : TU : utilities" (colon-separated, auto-converted)</li>
     * </ol>
     *
     * <p><strong>What is computed:</strong>
     * <ul>
     *   <li>Vertical representation (BitSet per item)</li>
     *   <li>Item-to-index normalization mapping</li>
     *   <li>Transaction utilities and item utilities</li>
     *   <li>Transaction-item utility matrix</li>
     *   <li>TWU (Transaction-Weighted Utility) for each item</li>
     * </ul>
     *
     * @return a populated TransactionalDatabase with utility information
     *
     * @throws IOException if an error occurs reading or parsing the file
     */
    @Override
    public TransactionalDatabase read_hui() throws IOException {
        // =====================================================================
        // PHASE 1: FIRST PASS - Discover Items and Parse Transactions
        // =====================================================================
        
        Set<Integer> itemSet = new HashSet<>();
        List<List<Integer>> transactionItemsLists = new ArrayList<>();
        List<Integer> transactionTUList = new ArrayList<>();
        List<List<Integer>> transactionUtilitiesLists = new ArrayList<>();
        
        nbTransactions = 0;
        
        try (BufferedReader reader = new BufferedReader(new FileReader(dataPath))) {
            String line;
            
            while ((line = reader.readLine()) != null) {
                if (skipLine(line))
                    continue;
                
                line = line.trim();
                if (line.isEmpty())
                    continue;

                // Convert colon-separated format to space-separated format
                if (line.contains(":")) {
                    String[] colonParts = line.split(":");
                    if (colonParts.length < 3) {
                        throw new IOException("Invalid colon-separated transaction: " + line);
                    }
                    
                    line = colonParts[0].trim() + " -1 "
                         + colonParts[1].trim() + " -1 "
                         + colonParts[2].trim() + " 0";
                }

                // Parse space-separated format: items -1 TU -1 utilities 0
                String[] tokens = line.split("\\s+");
                List<Integer> items = new ArrayList<>();
                Integer transactionUtility = null;
                List<Integer> utilities = new ArrayList<>();
                int parseState = 0; // 0: items, 1: TU, 2: utilities

                for (String token : tokens) {
                    int value = Integer.parseInt(token);

                    if (value == -1) {
                        parseState++;
                        continue;
                    } else if (value == 0) {
                        break;
                    }

                    switch (parseState) {
                        case 0:
                            items.add(value);
                            itemSet.add(value);
                            break;
                        case 1:
                            transactionUtility = value;
                            break;
                        case 2:
                            utilities.add(value);
                            break;
                    }
                }

                if (items.isEmpty()) {
                    throw new IOException("Empty transaction found in file: " + dataPath);
                }

                // Align utilities count with items count
                if (utilities.size() != items.size()) {
                    System.err.println("Warning: Items/Utilities mismatch in transaction " + nbTransactions +
                            ". Items: " + items.size() + ", Utilities: " + utilities.size());
                    
                    while (utilities.size() < items.size()) {
                        utilities.add(0);
                    }
                    while (utilities.size() > items.size()) {
                        utilities.remove(utilities.size() - 1);
                    }
                }

                transactionItemsLists.add(items);
                transactionTUList.add(transactionUtility != null ? transactionUtility : 0);
                transactionUtilitiesLists.add(utilities);
                nbTransactions++;
            }
        }
        
        // =====================================================================
        // PHASE 2: Setup Item Mappings
        // =====================================================================
        
        sortedItemIds = itemSet.stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(sortedItemIds);
        nbItems = sortedItemIds.length;
        
        itemIdToIndexMap = new HashMap<>();
        for (int index = 0; index < sortedItemIds.length; index++) {
            itemIdToIndexMap.put(sortedItemIds[index], index);
        }
        
        // =====================================================================
        // PHASE 3: Initialize Data Structures
        // =====================================================================
        
        verticalDataRepresentation = new BitSet[nbItems];
        for (int itemIndex = 0; itemIndex < nbItems; itemIndex++) {
            verticalDataRepresentation[itemIndex] = new BitSet(nbTransactions);
        }
        
        transactionItemUtilities = new int[nbTransactions][nbItems];
        transactionUtilities = new int[nbTransactions];
        
        // External utilities (assumes utilities in file are already quality × profit)
        itemExternalUtilities = new int[nbItems];
        Arrays.fill(itemExternalUtilities, 1);
        
        // =====================================================================
        // PHASE 4: Fill Data Structures from Parsed Transactions
        // =====================================================================
        
        for (int transactionId = 0; transactionId < nbTransactions; transactionId++) {
            List<Integer> items = transactionItemsLists.get(transactionId);
            List<Integer> utilities = transactionUtilitiesLists.get(transactionId);
            
            int fileTU = transactionTUList.get(transactionId);
            int computedTU = utilities.stream().mapToInt(Integer::intValue).sum();
            
            if (computedTU != fileTU && fileTU != 0) {
                System.err.println("Warning: TU mismatch at transaction " + transactionId +
                        ". File TU: " + fileTU + ", Computed TU: " + computedTU);
            }
            
            int finalTU = (fileTU != 0) ? fileTU : computedTU;
            transactionUtilities[transactionId] = finalTU;
            
            for (int itemPosition = 0; itemPosition < items.size(); itemPosition++) {
                Integer originalItemId = items.get(itemPosition);
                Integer normalizedItemIndex = itemIdToIndexMap.get(originalItemId);
                
                if (normalizedItemIndex != null) {
                    verticalDataRepresentation[normalizedItemIndex].set(transactionId);
                    
                    int itemUtilityValue = (itemPosition < utilities.size())
                        ? utilities.get(itemPosition)
                        : 0;
                    
                    transactionItemUtilities[transactionId][normalizedItemIndex] = itemUtilityValue;
                }
            }
        }
        
        // =====================================================================
        // PHASE 5: Create Database and Precompute TWUs
        // =====================================================================
        
        int[][] valueMeasures = new int[nbItems][1];
        for (int itemIndex = 0; itemIndex < nbItems; itemIndex++) {
            valueMeasures[itemIndex][0] = 1;
        }
        
        TransactionalDatabase database = new TransactionalDatabase(
            sortedItemIds,
            valueMeasures,
            noClasses ? 0 : 1,
            verticalDataRepresentation,
            nbTransactions,
            transactionUtilities,
            itemExternalUtilities,
            transactionItemUtilities
        );
        
        // Precompute TWUs for efficient pruning
        int[] itemTWUValues = new int[nbItems];
        for (int itemIndex = 0; itemIndex < nbItems; itemIndex++) {
            itemTWUValues[itemIndex] = computeItemTWU(itemIndex,
                verticalDataRepresentation,
                transactionUtilities);
        }
        database.setTwu(itemTWUValues);
        
        return database;
    }

    // =========================================================================
    // PUBLIC API: DATA ACCESSORS
    // =========================================================================

    /**
     * Returns the transaction utility (TU) values.
     *
     * <p>TU[t] = sum of all item utilities in transaction t.
     *
     * @return array of transaction utility values
     */
    public int[] getTransactionUtilities() {
        return transactionUtilities;
    }

    /**
     * Returns the item external utilities (profits per unit).
     *
     * <p>Typically all 1's for traditional mining, or actual profit values for HUI.
     *
     * @return array of item external utility values
     */
    public int[] getItemUtilities() {
        return itemExternalUtilities;
    }

    /**
     * Returns the transaction-item utility matrix.
     *
     * <p>transactionItemUtilities[t][i] = utility of item i in transaction t.
     *
     * @return 2D array of transaction-item utilities
     */
    public int[][] getTransactionItemUtilities() {
        return transactionItemUtilities;
    }

    /**
     * Computes the exact utility of an itemset in a specific transaction.
     *
     * <p>Sums the utilities of all items in the itemset within the transaction.
     *
     * @param itemIndices array of normalized item indices
     * @param transactionId the transaction identifier
     *
     * @return total utility of the itemset in this transaction
     */
    public int computeExactUtility(int[] itemIndices, int transactionId) {
        int utility = 0;
        
        for (int itemIndex : itemIndices) {
            if (itemIndex >= 0 && itemIndex < nbItems) {
                int internalUtility = transactionItemUtilities[transactionId][itemIndex];
                int externalUtility = itemExternalUtilities[itemIndex];
                utility += internalUtility * externalUtility;
            }
        }
        return utility;
    }

    /**
     * Computes the Transaction-Weighted Utility (TWU) of an itemset.
     *
     * <p>TWU = Σ(TU(t)) for all transactions t containing ALL items in the itemset.
     * This is an upper bound used for pruning in high-utility mining.
     *
     * @param itemSetBitSet BitSet representation of the itemset
     *
     * @return the TWU value for the itemset
     */
    public int computeTWU(BitSet itemSetBitSet) {
        int twu = 0;
        
        // Find all transactions containing ALL items
        BitSet transactionsWithItemset = new BitSet(nbTransactions);
        transactionsWithItemset.set(0, nbTransactions, true);
        
        for (int itemIndex = itemSetBitSet.nextSetBit(0); itemIndex >= 0;
             itemIndex = itemSetBitSet.nextSetBit(itemIndex + 1)) {
            if (itemIndex < verticalDataRepresentation.length) {
                transactionsWithItemset.and(verticalDataRepresentation[itemIndex]);
            }
        }
        
        // Sum transaction utilities
        for (int transactionId = transactionsWithItemset.nextSetBit(0);
             transactionId >= 0;
             transactionId = transactionsWithItemset.nextSetBit(transactionId + 1)) {
            twu += transactionUtilities[transactionId];
        }
        
        return twu;
    }
}

