package org.cp26.huim.parser;

public interface TransactionProjection {

    int size();

    int tidAt(int index);

    void initAllTransactions(int transactionCount);

    void initFromItem(HighUtilityDataset data, int item);

    void intersectWithItem(HighUtilityDataset data, int item);

    TransactionProjection copy();
}
