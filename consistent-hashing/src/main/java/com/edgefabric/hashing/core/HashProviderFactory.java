package com.edgefabric.hashing.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class HashProviderFactory {

    private HashProviderFactory() {

    }

    private static final Logger log = LoggerFactory.getLogger(HashProviderFactory.class);


    private static final HashProvider XXHASH_INSTANCE = new XXHashProvider();
    private static final HashProvider MURMUR_INSTANCE  = new MurmurHashProvider();


    private static final HashProvider DEFAULT = XXHASH_INSTANCE;


    public static HashProvider create(String algorithm) {

        if (algorithm == null || algorithm.isBlank()) {
            log.warn("No hash algorithm specified. Using default: XXHash.");
            return DEFAULT;
        }

        return switch (algorithm.toLowerCase().trim()) {

            case "xxhash" -> {
                log.info("HashProvider selected: XXHash (zero-allocation, fastest)");
                yield XXHASH_INSTANCE;
            }

            case "murmur" -> {
                log.info("HashProvider selected: MurmurHash3 (battle-tested, excellent distribution)");
                yield MURMUR_INSTANCE;
            }

            default -> throw new IllegalArgumentException(
                    "Unknown hash algorithm: '" + algorithm + "'. " +
                            "Supported algorithms: 'xxhash', 'murmur'"
            );
        };
    }
}