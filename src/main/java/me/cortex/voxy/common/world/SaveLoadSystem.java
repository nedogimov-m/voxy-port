package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.util.zstd.Zstd.*;

public class SaveLoadSystem {

    // Format version 2: key(8) + nonEmptyChildren(1) + lutLen(4) + lut(8*n) + data(2*32768) + hash(8)
    private static final byte FORMAT_VERSION = 2;

    //TODO: Cache like long2short and the short and other data to stop allocs
    public static ByteBuffer serialize(WorldSection section) {
        var data = section.copyData();
        var compressed = new short[data.length];
        Long2ShortOpenHashMap LUT = new Long2ShortOpenHashMap(data.length);
        LongArrayList LUTVAL = new LongArrayList();
        for (int i = 0; i < data.length; i++) {
            long block = data[i];
            short mapping = LUT.computeIfAbsent(block, id->{
                LUTVAL.add(id);
                return (short)(LUTVAL.size()-1);
            });
            compressed[i] = mapping;
        }
        long[] lut = LUTVAL.toLongArray();
        ByteBuffer raw = MemoryUtil.memAlloc(compressed.length*2+lut.length*8+512);

        long hash = section.key^(lut.length*1293481298141L);
        raw.putLong(section.key);
        raw.put(section.nonEmptyChildren);
        raw.putInt(lut.length);
        for (long id : lut) {
            raw.putLong(id);
            hash *= 1230987149811L;
            hash += 12831;
            hash ^= id;
        }

        for (int i = 0; i < compressed.length; i++) {
            short block = compressed[i];
            raw.putShort(block);
            hash *= 1230987149811L;
            hash += 12831;
            hash ^= (block*1827631L) ^ data[i];
        }

        raw.putLong(hash);

        raw.limit(raw.position());
        raw.rewind();

        return raw;
    }

    public static boolean deserialize(WorldSection section, ByteBuffer data, boolean ignoreMismatchPosition) {
        long hash = 0;
        long key = data.getLong();

        // Detect format: v2 has nonEmptyChildren byte before lutLen.
        // v1 has lutLen (int) directly after key.
        // Heuristic: read 5 bytes (1 byte + 4 byte int). If the int is reasonable as lutLen, it's v2.
        // If not, rewind and treat as v1.
        byte nonEmptyChildren = 0;
        int lutLen;
        if (data.remaining() >= 5) {
            data.mark();
            byte necByte = data.get();
            int possibleLutLen = data.getInt();
            // In v1, the first 4 bytes after key are lutLen directly.
            // lutLen is typically 1-10000. If necByte+lutLen combination makes sense as v2, use it.
            // In v1, the 5 bytes would be: lutLen as (byte0, byte1, byte2, byte3, byte4) where first 4 = lutLen.
            // The v1 lutLen at position 8 would be: data[8..11] as int.
            // The v2 has nec at position 8 and lutLen at position 9..12.
            // Simple heuristic: if possibleLutLen > 0 && possibleLutLen < 100000, likely v2.
            // If not, reset to position 8 and read as v1.
            if (possibleLutLen >= 0 && possibleLutLen < 100000) {
                nonEmptyChildren = necByte;
                lutLen = possibleLutLen;
            } else {
                // v1 format — rewind
                data.reset();
                lutLen = data.getInt();
            }
        } else {
            lutLen = data.getInt();
        }

        long[] lut = new long[lutLen];
        hash = key^(lut.length*1293481298141L);
        for (int i = 0; i < lutLen; i++) {
            lut[i] = data.getLong();
            hash *= 1230987149811L;
            hash += 12831;
            hash ^= lut[i];
        }

        if ((!ignoreMismatchPosition) && section.key != key) {
            System.err.println("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
            return false;
        }

        for (int i = 0; i < section.data.length; i++) {
            short lutId = data.getShort();
            section.data[i] = lut[lutId];
            hash *= 1230987149811L;
            hash += 12831;
            hash ^= (lutId*1827631L) ^ section.data[i];
        }

        long expectedHash = data.getLong();
        if (expectedHash != hash) {
            System.err.println("Hash mismatch got: " + hash + " expected: " + expectedHash + " removing region");
            return false;
        }

        if (data.hasRemaining()) {
            System.err.println("Decompressed section had excess data removing region");
            return false;
        }

        section.nonEmptyChildren = nonEmptyChildren;
        return true;
    }
}
