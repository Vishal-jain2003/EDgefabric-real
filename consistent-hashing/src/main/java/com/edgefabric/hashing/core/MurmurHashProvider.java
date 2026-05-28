package com.edgefabric.hashing.core;

import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;


public class MurmurHashProvider implements HashProvider {

    @Override
    public long generateHash(String key) {
        return Hashing.murmur3_128()
                .hashString(key, StandardCharsets.UTF_8)
                .asLong();
    }
}