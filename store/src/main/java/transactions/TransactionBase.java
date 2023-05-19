public abstract class TransactionBase{
 
    public abstract TrState startTr();
    public abstract TrState commitTr();
    public abstract TrState rollBackTr();
    public abstract TrState setTrPoint();
    public abstract TrState truncateTr();

} 
