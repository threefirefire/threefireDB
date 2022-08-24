package top.thinkin.lightd.db;


import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksIterator;
import top.thinkin.lightd.base.*;
import top.thinkin.lightd.data.KeyEnum;
import top.thinkin.lightd.exception.DAssert;
import top.thinkin.lightd.exception.ErrorType;
import top.thinkin.lightd.exception.KitDBException;
import top.thinkin.lightd.kit.ArrayKits;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

@Slf4j

public abstract class RBase {

    protected DB db;

    protected final boolean isLog;

    protected static Charset charset = Charset.forName("UTF-8");

    protected int DEF_TX_TIME_OUT = 5000;


    protected KeyLock lock;

    public RBase(boolean isLog) {
        this.isLog = isLog;
    }

    public RBase() {
        this.isLog = false;
    }


    public void start() {
        db.start();
    }


    public CloseLock checkClose() throws KitDBException {
        return db.closeCheck();
    }

    protected void setTimer(KeyEnum keyEnum, int time, byte[] value) {

        TimerStore.put(this, keyEnum.getKey(), time, value);

    }


    protected void setTimerCollection(KeyEnum keyEnum, int time, byte[] key_b, byte[] meta_b) {
        byte[] key_b_size_b = ArrayKits.intToBytes(key_b.length);
        TimerStore.put(this, keyEnum.getKey(), time, ArrayKits.addAll(key_b_size_b, key_b, meta_b));
    }


    protected void delTimerCollection(KeyEnum keyEnum, int time, byte[] key_b, byte[] meta_b) {
        byte[] key_b_size_b = ArrayKits.intToBytes(key_b.length);
        TimerStore.del(this, keyEnum.getKey(), time, ArrayKits.addAll(key_b_size_b, key_b, meta_b));
    }

    public static class TimerCollection {
        public byte[] key_b;
        public byte[] meta_b;

    }


    protected void checkTxRange() throws KitDBException {
        if (!db.openTransaction) {
            return;
        }
        DAssert.isTrue(!this.db.IS_STATR_TX.get(), ErrorType.TX_ERROR,
                "This operation can't execute  in a transaction KitDB");
        db.checkKey();
    }


    protected LockEntity lock(String key) {
        LockEntity lockEntity = lock.lock(key);
        db.addLockEntity(lockEntity);
        return lockEntity;
    }

    protected void unlock(LockEntity lockEntity) {
        if (db.IS_STATR_TX.get()) {
            return;
        }
        lock.unlock(lockEntity);
    }


    protected void checkTxStart() throws KitDBException {

        if (db.openTransaction) {
            db.startTran();
        }
    }

    protected void checkTxCommit() throws KitDBException {
        if (db.openTransaction) {
            db.commitTX();
        }
    }

    protected void checkTxRollBack() throws KitDBException {
        if (db.openTransaction) {
            db.rollbackTX();
        }
    }

    protected abstract TxLock getTxLock(String key);

    protected static TimerCollection getTimerCollection(byte[] value) {
        byte[] key_b_size_b = ArrayKits.sub(value, 0, 4);
        int size = ArrayKits.bytesToInt(key_b_size_b, 0);
        TimerCollection timerCollection = new TimerCollection();
        timerCollection.key_b = ArrayKits.sub(value, 4, 4 + size);
        timerCollection.meta_b = ArrayKits.sub(value, 4 + size, value.length);
        return timerCollection;
    }

    protected void delTimer(KeyEnum keyEnum, int time, byte[] value) {
        TimerStore.del(this, keyEnum.getKey(), time, value);
    }


    protected void commit() throws KitDBException {
        db.commit();
    }

    protected void commitLocal() throws KitDBException {
        db.commitLocal();
    }

    protected void release() {
        db.release();
    }


    protected void putDB(byte[] key, byte[] value, SstColumnFamily columnFamily) {
        db.putDB(key, value, columnFamily);
    }

    public void deleteDB(byte[] key, SstColumnFamily columnFamily) {
        db.deleteDB(key, columnFamily);
    }


    protected void deleteRangeDB(byte[] start, byte[] end, SstColumnFamily columnFamily) {
        db.deleteRangeDB(start, end, columnFamily);
    }


    protected byte[] getDB(byte[] key, SstColumnFamily columnFamily) throws KitDBException {
        return db.getDB(key, columnFamily);
    }


    protected RocksIterator newIterator(SstColumnFamily columnFamily) {
        return db.newIterator(columnFamily);
    }


    protected Map<byte[], byte[]> multiGet(List<byte[]> keys, SstColumnFamily columnFamily) throws KitDBException {
        return db.multiGet(keys, columnFamily);
    }


    protected void deleteHead(byte[] head, SstColumnFamily columnFamily) {
        db.deleteHead(head, columnFamily);
    }


}
