package com.edgefabric.hashing.core;

import net.openhft.hashing.LongHashFunction;


public class XXHashProvider implements HashProvider {


    private static final LongHashFunction INSTANCE = LongHashFunction.xx();

    @Override
    public long generateHash(String key) {

        return INSTANCE.hashChars(key);
    }
}