package top.thinkin.lightd.db;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksIterator;
import top.thinkin.lightd.base.*;
import top.thinkin.lightd.data.KeyEnum;
import top.thinkin.lightd.exception.DAssert;
import top.thinkin.lightd.exception.ErrorType;
import top.thinkin.lightd.exception.KitDBException;
import top.thinkin.lightd.kit.ArrayKits;
import top.thinkin.lightd.kit.BytesUtil;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ZSet extends RCollection {
    public static String HEAD = KeyEnum.ZSET.getKey();
    public static byte[] HEAD_B = HEAD.getBytes();
    public static byte[] HEAD_SCORE_B = KeyEnum.ZSET_S.getBytes();
    public static byte[] HEAD_V_B = KeyEnum.ZSET_V.getBytes();

    @Override
    protected TxLock getTxLock(String key) {
        return new TxLock(String.join(":", HEAD, key));
    }


    protected ZSet(DB db) {
        super(db, false, 128);
    }


    protected byte[] getKey(String key) throws KitDBException {
        DAssert.notNull(key, ErrorType.NULL, "Key is null");
        return ArrayKits.addAll(HEAD_B, key.getBytes(charset));
    }

    public void add(String key, byte[] v, long score) throws KitDBException {
        addMayTTL(key, -1, new Entry(score, v));
    }

    public void add(String key, List<Entry> entryList) throws KitDBException {
        Entry[] entries = new Entry[entryList.size()];
        entryList.toArray(entries);
        addMayTTL(key, -1, entries);
    }

    public void addMayTTL(String key, int ttl, byte[] v, long score) throws KitDBException {
        addMayTTL(key, ttl, new Entry(score, v));
    }


    private void addMayTTL(final String key, int ttl, List<Entry> entryList) throws KitDBException {
        Entry[] entries = new Entry[entryList.size()];
        entryList.toArray(entries);
        addMayTTL(key, ttl, entries);
    }

    public boolean contains(String key, byte[] value) throws KitDBException {
        try (CloseLock ignored = checkClose()) {
            byte[] key_b = getKey(key);
            MetaV metaV = getMeta(key_b);
            if (metaV == null) {
                return false;
            }
            SData sData = new SData(key_b.length, key_b, metaV.getVersion(), value);
            return getDB(sData.convertBytes().toBytes(), SstColumnFamily.DEFAULT) != null;
        }
    }

    private void addMayTTL(final String key, int ttl, Entry... entrys) throws KitDBException {
        checkTxStart();
        try (CloseLock ignored = checkClose()) {
            DAssert.notEmpty(entrys, ErrorType.EMPTY, "entrys is empty");
            LockEntity lockEntity = lock(key);

            byte[] key_b = getKey(key);

            byte[][] bytess = new byte[entrys.length][];
            for (int i = 0; i < entrys.length; i++) {
                bytess[i] = entrys[i].value;
            }
            DAssert.isTrue(ArrayKits.noRepeate(bytess), ErrorType.REPEATED_KEY, "Repeated memebers");
            try {
                start();
                byte[] k_v = getDB(key_b, SstColumnFamily.META);
                MetaV metaV = addCheck(key_b, k_v);
                if (metaV != null) {
                    setEntry(key_b, metaV, entrys);
                    putDB(key_b, metaV.convertMetaBytes().toBytes(), SstColumnFamily.META);
                } else {
                    if (ttl != -1) {
                        ttl = (int) (System.currentTimeMillis() / 1000 + ttl);
                    }
                    metaV = new MetaV(0, ttl, db.versionSequence().incr());
                    setEntry(key_b, metaV, entrys);
                    putDB(key_b, metaV.convertMetaBytes().toBytes(), SstColumnFamily.META);

                    if (metaV.getTimestamp() != -1) {
                        setTimerCollection(KeyEnum.COLLECT_TIMER,
                                metaV.getTimestamp(), key_b, metaV.convertMetaBytes().toBytesHead());
                    }
                }
                commit();
            } finally {
                unlock(lockEntity);
                release();
            }
            checkTxCommit();
        } catch (KitDBException e) {
            checkTxRollBack();
            throw e;
        }
    }


    private void setEntry(byte[] key_b, MetaV metaV, Entry[] entrys) throws KitDBException {
        for (Entry entry : entrys) {
            SData sData = new SData(key_b.length, key_b, metaV.getVersion(), entry.value);
            ZData zData = new ZData(key_b.length, key_b, metaV.getVersion(), entry.score, entry.value);
            byte[] member = sData.convertBytes().toBytes();
            byte[] old_score_bs = getDB(member, SstColumnFamily.DEFAULT);
            if (old_score_bs == null) {
                metaV.size = metaV.size + 1;
            } else {
                ZData zData_old = new ZData(key_b.length, key_b, metaV.getVersion(), ArrayKits.bytesToLong(old_score_bs), entry.value);
                deleteDB(zData_old.convertBytes().toBytes(), SstColumnFamily.DEFAULT);
            }
            putDB(member, ArrayKits.longToBytes(entry.score), SstColumnFamily.DEFAULT);
            putDB(zData.convertBytes().toBytes(), "".getBytes(), SstColumnFamily.DEFAULT);
        }
    }

    /**
     * 返回指定区间分数的成员
     *
     */
    public List<Entry> range(String key, long start, long end, int limit) throws KitDBException {
        try (CloseLock ignored = checkClose()) {
            byte[] key_b = getKey(key);

            List<Entry> entries = new ArrayList<>();
            MetaV metaV = getMeta(key_b);
            ZData zData = new ZData(key_b.length, key_b, metaV.getVersion(), start, "".getBytes());

            byte[] seek = zData.getSeek();
            byte[] head = zData.getHead();
            int count = 0;
            try (final RocksIterator iterator = newIterator(SstColumnFamily.DEFAULT)) {
                iterator.seek(seek);
                long index = 0;
                while (iterator.isValid() && index <= end && count < limit) {
                    byte[] key_bs = iterator.key();
                    if (!BytesUtil.checkHead(head, key_bs)) break;
                    ZData izData = ZDataD.build(key_bs).convertValue();
                    index = izData.getScore();
                    if (index > end) {
                        break;
                    }
                    entries.add(new Entry(index, izData.value));
                    count++;
                    iterator.next();
                }
            }
            return entries;
        }
    }

    /**
     * 返回指定区间分数的成员并删除
     *
     * @param start
     * @param end
     * @return
     * @throws Exception
     */
    public List<Entry> rangeDel(String key, long start, long end, int limit) throws KitDBException {
        checkTxStart();
        List<Entry> entries = new ArrayList<>();
        byte[] key_b = getKey(key);
        LockEntity lockEntity = lock(key);
        try (CloseLock ignored = checkClose()) {

            try (final RocksIterator iterator = newIterator(SstColumnFamily.DEFAULT)) {
                MetaV metaV = getMeta(key_b);
                if (metaV == null) {
                    checkTxCommit();
                    return entries;
                }
                ZData zData = new ZData(key_b.length, key_b, metaV.getVersion(), start, "".getBytes());

                byte[] seek = zData.getSeek();
                byte[] head = zData.getHead();

                List<byte[]> dels = new ArrayList<>();
                iterator.seek(seek);
                long index = 0;
                int count = 0;
                while (iterator.isValid() && index <= end && count < limit) {
                    byte[] key_bs = iterator.key();
                    if (!BytesUtil.checkHead(head, key_bs)) break;
                    ZDataD zDataD = ZDataD.build(key_bs);
                    ZData izData = zDataD.convertValue();
                    index = izData.getScore();
                    if (index > end) {
                        break;
                    }
                    entries.add(new Entry(index, izData.value));
                    count++;
                    //DEL
                    metaV.setSize(metaV.getSize() - 1);
                    dels.add(zDataD.toBytes());
                    SDataD sDataD = new SDataD(zDataD.getMapKeySize(), key_b, zDataD.getVersion(), zDataD.getValue());
                    dels.add(sDataD.toBytes());
                    iterator.next();
                }
                start();
                removeDo(key_b, metaV, dels);
                putDB(key_b, metaV.convertMetaBytes().toBytes(), SstColumnFamily.META);
                commit();
            } finally {
                unlock(lockEntity);
                release();
            }
            checkTxCommit();
        } catch (KitDBException e) {
            checkTxRollBack();
            throw e;
        }
        return entries;
    }


    @Override
    @SuppressWarnings("unchecked")
    public RIterator<ZSet> iterator(String key) throws KitDBException {
        try (CloseLock ignored = checkClose()) {
            byte[] key_b = getKey(key);
            MetaV metaV = getMeta(key_b);
            SData sData = new SData(key_b.length, key_b, metaV.getVersion(), "".getBytes());
            RocksIterator iterator = newIterator(SstColumnFamily.DEFAULT);
            iterator.seek(sData.getHead());
            RIterator<ZSet> rIterator = new RIterator<>(iterator, this, sData.getHead());
            return rIterator;
        }
    }


    private void removeDo(byte[] key_b, MetaV metaV, List<byte[]> dels) {
        for (byte[] del : dels) {
            deleteDB(del, SstColumnFamily.DEFAULT);
        }
    }


    /**
     * 对指定成员的分数加上增量 increment
     *
     * @param increment
     * @param members
     * @throws Exception
     */
    private void incrby(String key, int increment, byte[]... members) throws KitDBException {
        DAssert.notEmpty(members, ErrorType.EMPTY, "vs is empty");
        checkTxStart();
        LockEntity lockEntity = lock(key);
        try (CloseLock ignored = checkClose()) {
            byte[] key_b = getKey(key);
            try {
                start();
                MetaV metaV = getMeta(key_b);
                if (metaV == null) {
                    checkTxCommit();
                    return;
                }
                for (byte[] v : members) {
                    SData sData = new SData(key_b.length, key_b, metaV.getVersion(), v);
                    SDataD sDataD = sData.convertBytes();
                    byte[] scoreD = getDB(sDataD.toBytes(), SstColumnFamily.DEFAULT);
                    if (scoreD != null) {
                        int score = ArrayKits.bytesToInt(scoreD, 0) + increment;
                        scoreD = ArrayKits.intToBytes(score);
                        ZDataD zDataD = new ZDataD(sDataD.getMapKeySize(), sDataD.getMapKey(), sDataD.getVersion(), scoreD, sDataD.getValue());
                        putDB(sData.convertBytes().toBytes(), scoreD, SstColumnFamily.DEFAULT);
                        putDB(zDataD.toBytes(), null, SstColumnFamily.DEFAULT);
                    }
                }
                commit();
            } finally {
                unlock(lockEntity);
                release();
            }
            checkTxCommit();
        } catch (KitDBException e) {
            checkTxRollBack();
            throw e;
        }
    }

    /**
     * 删除指定成员
     *
     * @param vs
     * @throws Exception
     */
    public void remove(String key, byte[]... vs) throws KitDBException {
        DAssert.notEmpty(vs, ErrorType.EMPTY, "vs is empty");
        checkTxStart();
        LockEntity lockEntity = lock(key);
        try (CloseLock ignored = checkClose()) {
            byte[] key_b = getKey(key);
            start();
            try {
                MetaV metaV = getMeta(key_b);
                if (metaV == null) {
                    checkTxCommit();
                    return;
                }
                List<byte[]> dels = new ArrayList<>();
                for (byte[] v : vs) {
                    SData sData = new SData(key_b.length, key_b, metaV.getVersion(), v);
                    SDataD sDataD = sData.convertBytes();
                    byte[] scoreD = getDB(sDataD.toBytes(), SstColumnFamily.DEFAULT);
                    if (scoreD != null) {
                        ZDataD zDataD = new ZDataD(sDataD.getMapKeySize(), sDataD.getMapKey(), sDataD.getVersion(), scoreD, sDataD.getValue());
                        dels.add(zDataD.toBytes());
                        dels.add(sDataD.toBytes());
                        metaV.setSize(metaV.getSize() - 1);
                    }
                }
                removeDo(key_b, metaV, dels);
                putDB(key_b, metaV.convertMetaBytes().toBytes(), SstColumnFamily.META);
                commit();
            } finally {
                unlock(lockEntity);
                release();
            }
            checkTxCommit();
        } catch (KitDBException e) {
            checkTxRollBack();
            throw e;
        }
    }


    /**
     * 返回成员的分数值,如成员不存在，List对应位置则为null
     *
     * @param key
     * @param vs
     * @return
     * @throws Exception
     */
    public List<Long> score(String key, byte[]... vs) throws KitDBException {
        try (CloseLock ignored = checkClose()) {
            DAssert.notEmpty(vs, ErrorType.EMPTY, "vs is empty");
            byte[] key_b = getKey(key);
            MetaV metaV = getMeta(key_b);
            List<Long> scores = new ArrayList<>();
            for (byte[] v : vs) {
                SData sData = new SData(key_b.length, key_b, metaV.getVersion(), v);
                byte[] scoreD = getDB(sData.convertBytes().toBytes(), SstColumnFamily.DEFAULT);
                if (scoreD != null) {
                    scores.add(ArrayKits.bytesToLong(scoreD));
                }
                scores.add(null);
            }
            return scores;
        }
    }

    /**
     * 返回成员的分数值
     *
     * @param v
     * @return
     * @throws Exception
     */
    public Long score(String key, byte[] v) throws KitDBException {
        try (CloseLock ignored = checkClose()) {
            byte[] key_b = getKey(key);
            MetaV metaV = getMeta(key_b);
            if (metaV == null) {
                return null;
            }
            SData sData = new SData(key_b.length, key_b, metaV.getVersion(), v);
            byte[] scoreD = getDB(sData.convertBytes().toBytes(), SstColumnFamily.DEFAULT);
            if (scoreD != null) {
                return ArrayKits.bytesToLong(scoreD);
            }
            return null;
        }
    }


    private MetaV addCheck(byte[] key_b, byte[] k_v) {
        MetaV metaV = null;
        if (k_v != null) {
            MetaD metaD = MetaD.build(k_v);
            metaV = metaD.convertMetaV();
            long nowTime = System.currentTimeMillis() / 1000;
            if (metaV.getTimestamp() != -1 && nowTime > metaV.getTimestamp()) {
                metaV = null;
            }
        }
        return metaV;
    }

    private MetaV getMetaP(byte[] key_b) throws KitDBException {
        byte[] k_v = this.getDB(key_b, SstColumnFamily.META);
        if (k_v == null) return null;
        MetaV metaV = MetaD.build(k_v).convertMetaV();
        return metaV;
    }

    @Override
    protected MetaV getMeta(byte[] key_b) throws KitDBException {
        MetaV metaV = getMetaP(key_b);
        if (metaV == null) {
            return null;
        }
        if (metaV.getTimestamp() != -1 && (System.currentTimeMillis() / 1000) - metaV.getTimestamp() >= 0) {
            metaV = null;
        }
        return metaV;
    }

    protected void deleteByClear(byte[] key_b, MetaD meta) throws KitDBException {
        try (CloseLock ignored = checkClose()) {
            start();
            delete(key_b, meta);
            commitLocal();
        } finally {
            release();
        }
    }

    private void delete(byte[] key_b, MetaD metaD) {
        MetaV metaV = metaD.convertMetaV();
        SData sData = new SData(key_b.length, key_b, metaV.getVersion(), null);
        deleteHead(sData.getHead(), SstColumnFamily.DEFAULT);
        ZData zData = new ZData(sData.getMapKeySize(), sData.getMapKey(), sData.getVersion(), 0, null);
        deleteHead(zData.getHead(), SstColumnFamily.DEFAULT);
        deleteDB(ArrayKits.addAll("D".getBytes(charset), key_b, metaD.getVersion()), SstColumnFamily.DEFAULT);
    }


    @Override
    public void delete(String key) throws KitDBException {
        checkTxRange();
        try (CloseLock ignored = checkClose()) {
            byte[] key_b = getKey(key);
            LockEntity lockEntity = lock(key);
            try {
                start();
                MetaV metaV = getMeta(key_b);
                if (metaV == null) {
                    checkTxCommit();
                    return;
                }
                deleteDB(key_b, SstColumnFamily.META);
                delete(key_b, metaV.convertMetaBytes());
                commit();
            } finally {
                unlock(lockEntity);
                release();
            }
            checkTxCommit();
        } catch (KitDBException e) {
            checkTxRollBack();
            throw e;
        }
    }

    @Override
    public KeyIterator getKeyIterator() throws KitDBException {
        try (CloseLock ignored = checkClose()) {
            return getKeyIterator(HEAD_B);
        }
    }


    public void deleteFast(String key) throws KitDBException {
        checkTxStart();
        try (CloseLock ignored = checkClose()) {
            byte[] key_b = getKey(key);
            LockEntity lockEntity = lock(key);

            try {
                byte[] k_v = getDB(key_b, SstColumnFamily.META);
                if (k_v == null) {
                    checkTxCommit();
                    return;
                }
                MetaV meta = MetaD.build(k_v).convertMetaV();
                deleteFast(key_b, meta);
            } finally {
                unlock(lockEntity);
            }
            checkTxCommit();

        } catch (KitDBException e) {
            checkTxRollBack();
            throw e;
        }
    }

    @Override
    public int getTtl(String key) throws KitDBException {
        try (CloseLock ignored = checkClose()) {
            byte[] key_b = getKey(key);

            MetaV metaV = getMeta(key_b);

            if (metaV == null) {
                return -1;
            }

            if (metaV.getTimestamp() == -1) {
                return -1;
            }
            return (int) (metaV.getTimestamp() - System.currentTimeMillis() / 1000);
        }
    }

    @Override
    public void delTtl(String key) throws KitDBException {
        checkTxStart();
        try (CloseLock ignored = checkClose()) {
            LockEntity lockEntity = lock(key);
            byte[] key_b = getKey(key);
            try {
                MetaV metaV = getMetaP(key_b);
                if (metaV == null) {
                    checkTxCommit();
                    return;
                }
                start();
                delTimerCollection(KeyEnum.COLLECT_TIMER,
                        metaV.getTimestamp(), key_b, metaV.convertMetaBytes().toBytesHead());
                metaV.setTimestamp(-1);
                putDB(key_b, metaV.convertMetaBytes().toBytes(), SstColumnFamily.META);
                commit();
            } finally {
                unlock(lockEntity);
                release();
            }
            checkTxCommit();
        } catch (KitDBException e) {
            checkTxRollBack();
            throw e;
        }
    }


    protected void deleteTTL(int time, byte[] key_b, byte[] meta_b) throws KitDBException {
        String key = new String(ArrayKits.sub(key_b, 1, key_b.length + 1), charset);
        LockEntity lockEntity = lock(key);
        try (CloseLock ignored = checkClose()) {
            MetaV metaV = getMetaP(key_b);
            if (metaV != null && time != metaV.timestamp) {
                return;
            }
            deleteTTL(key_b, MetaD.build(meta_b).convertMetaV(), metaV.version);
        } finally {
            unlock(lockEntity);
        }
    }

    @Override
    public void ttl(String key, int ttl) throws KitDBException {
        checkTxStart();
        try (CloseLock ignored = checkClose()) {
            LockEntity lockEntity = lock(key);

            byte[] key_b = getKey(key);
            try {
                MetaV metaV = getMeta(key_b);
                if (metaV == null) {
                    checkTxCommit();
                    return;
                }
                start();
                delTimerCollection(KeyEnum.COLLECT_TIMER,
                        metaV.getTimestamp(), key_b, metaV.convertMetaBytes().toBytesHead());
                metaV.setTimestamp((int) (System.currentTimeMillis() / 1000 + ttl));
                putDB(key_b, metaV.convertMetaBytes().toBytes(), SstColumnFamily.META);
                setTimerCollection(KeyEnum.COLLECT_TIMER,
                        metaV.getTimestamp(), key_b, metaV.convertMetaBytes().toBytesHead());
            } finally {
                unlock(lockEntity);
                release();
            }
            checkTxCommit();
        } catch (KitDBException e) {
            checkTxRollBack();
            throw e;
        }

    }

    @Override
    public boolean isExist(String key) throws KitDBException {
        try (CloseLock ignored = checkClose()) {
            byte[] key_b = getKey(key);
            byte[] k_v = getDB(key_b, SstColumnFamily.META);
            MetaV meta = addCheck(key_b, k_v);
            return meta != null;
        }
    }

    @Override
    public int size(String key) throws KitDBException {
        try (CloseLock ignored = checkClose()) {
            int size = 0;
            try (RIterator<ZSet> iterator = iterator(key)) {
                while (iterator.hasNext()) {
                    iterator.next();
                    size++;
                }
            }
            return size;
        }
    }

    @Override
    public Entry getEntry(RocksIterator iterator) throws KitDBException {
        try (CloseLock ignored = checkClose()) {
            byte[] key_bs = iterator.key();
            if (key_bs == null) {
                return null;
            }
            SData sData = SDataD.build(key_bs).convertValue();
            Entry entry = new Entry(ArrayKits.bytesToLong(iterator.value()), sData.value);
            return entry;
        }
    }

    @Data
    @AllArgsConstructor
    public static class Entry extends REntry {
        private long score;
        private byte[] value;
    }


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MetaV extends MetaAbs {
        private int size;
        private int timestamp;
        private int version;

        public MetaD convertMetaBytes() {
            MetaD metaVD = new MetaD();
            metaVD.setSize(ArrayKits.intToBytes(this.size));
            metaVD.setTimestamp(ArrayKits.intToBytes(this.timestamp));
            metaVD.setVersion(ArrayKits.intToBytes(this.version));
            return metaVD;
        }

    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MetaD extends MetaDAbs {
        private byte[] size;
        private byte[] timestamp;
        private byte[] version;

        public static MetaD build(byte[] bytes) {
            MetaD metaD = new MetaD();
            metaD.setSize(ArrayKits.sub(bytes, 1, 5));
            metaD.setTimestamp(ArrayKits.sub(bytes, 5, 9));
            metaD.setVersion(ArrayKits.sub(bytes, 9, 13));
            return metaD;
        }


        public byte[] toBytesHead() {
            byte[] value = ArrayKits.addAll(HEAD_B, ArrayKits.intToBytes(0),
                    ArrayKits.intToBytes(0), this.version);
            return value;
        }

        public byte[] toBytes() {
            byte[] value = ArrayKits.addAll(HEAD_B, this.size, this.timestamp, this.version);
            return value;
        }

        public MetaV convertMetaV() {
            MetaV metaV = new MetaV();
            metaV.setSize(ArrayKits.bytesToInt(this.size, 0));
            metaV.setTimestamp(ArrayKits.bytesToInt(this.timestamp, 0));
            metaV.setVersion(ArrayKits.bytesToInt(this.version, 0));
            return metaV;
        }
    }


    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SData {
        private int mapKeySize;
        private byte[] mapKey;
        private int version;
        private byte[] value;


        public SDataD convertBytes() {
            SDataD sDataD = new SDataD();
            sDataD.setMapKeySize(ArrayKits.intToBytes(this.mapKeySize));
            sDataD.setMapKey(this.mapKey);
            sDataD.setVersion(ArrayKits.intToBytes(this.version));
            sDataD.setValue(this.value);
            return sDataD;
        }


        public byte[] getHead() {
            byte[] value = ArrayKits.addAll(HEAD_V_B, ArrayKits.intToBytes(this.mapKeySize),
                    this.mapKey, ArrayKits.intToBytes(this.version));
            return value;
        }

    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SDataD {
        private byte[] mapKeySize;
        private byte[] mapKey;
        private byte[] version;
        private byte[] value;

        public byte[] toBytes() {
            byte[] value = ArrayKits.addAll(HEAD_V_B, this.mapKeySize, this.mapKey, this.version, this.value);
            return value;
        }

        public static SDataD build(byte[] bytes) {
            SDataD sDataD = new SDataD();
            sDataD.setMapKeySize(ArrayKits.sub(bytes, 1, 5));
            int position = ArrayKits.bytesToInt(sDataD.getMapKeySize(), 0);
            sDataD.setMapKey(ArrayKits.sub(bytes, 5, position = 5 + position));
            sDataD.setVersion(ArrayKits.sub(bytes, position, position = position + 4));
            sDataD.setValue(ArrayKits.sub(bytes, position, bytes.length));
            return sDataD;
        }

        public SData convertValue() {
            SData sData = new SData();
            sData.setMapKeySize(ArrayKits.bytesToInt(this.mapKeySize, 0));
            sData.setMapKey(this.mapKey);
            sData.setVersion(ArrayKits.bytesToInt(this.version, 0));
            sData.setValue(this.value);
            return sData;
        }
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ZData {
        private int mapKeySize;
        private byte[] mapKey;
        private int version;
        private long score;
        private byte[] value;

        public ZDataD convertBytes() {
            ZDataD zDataD = new ZDataD();
            zDataD.setMapKeySize(ArrayKits.intToBytes(this.mapKeySize));
            zDataD.setMapKey(this.mapKey);
            zDataD.setVersion(ArrayKits.intToBytes(this.version));
            zDataD.setScore(ArrayKits.longToBytes(this.score));
            zDataD.setValue(this.value);
            return zDataD;
        }


        public byte[] getSeek() {
            byte[] value = ArrayKits.addAll(HEAD_SCORE_B, ArrayKits.intToBytes(this.mapKeySize),
                    this.mapKey, ArrayKits.intToBytes(this.version), ArrayKits.longToBytes(this.score));
            return value;
        }

        public byte[] getHead() {
            byte[] value = ArrayKits.addAll(HEAD_SCORE_B, ArrayKits.intToBytes(this.mapKeySize),
                    this.mapKey, ArrayKits.intToBytes(this.version));
            return value;
        }

    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ZDataD {
        private byte[] mapKeySize;
        private byte[] mapKey;
        private byte[] version;
        private byte[] score;
        private byte[] value;


        public byte[] toBytes() {
            return ArrayKits.addAll(HEAD_SCORE_B, this.mapKeySize, this.mapKey, this.version, this.score, this.value);
        }

        public static ZDataD build(byte[] bytes) {
            ZDataD zData = new ZDataD();
            zData.setMapKeySize(ArrayKits.sub(bytes, 1, 5));
            int position = ArrayKits.bytesToInt(zData.getMapKeySize(), 0);
            zData.setMapKey(ArrayKits.sub(bytes, 5, position = 5 + position));
            zData.setVersion(ArrayKits.sub(bytes, position, position = position + 4));
            zData.setScore(ArrayKits.sub(bytes, position, position = position + 8));
            zData.setValue(ArrayKits.sub(bytes, position, bytes.length));
            return zData;
        }


        public ZData convertValue() {
            ZData zData = new ZData();
            zData.setMapKeySize(ArrayKits.bytesToInt(this.mapKeySize, 0));
            zData.setMapKey(this.mapKey);
            zData.setVersion(ArrayKits.bytesToInt(this.version, 0));
            zData.setScore(ArrayKits.bytesToLong(this.score));
            zData.setValue(this.value);
            return zData;
        }

    }
}
