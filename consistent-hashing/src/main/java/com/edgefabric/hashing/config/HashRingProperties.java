package com.edgefabric.hashing.config;

public class HashRingProperties {


    private int virtualNodes = 150;
    private String hashAlgorithm = "xxhash";




    public HashRingProperties() { }


    public HashRingProperties(int virtualNodes, String hashAlgorithm) {
        setVirtualNodes(virtualNodes);
        setHashAlgorithm(hashAlgorithm);
    }



    public int getVirtualNodes() {
        return virtualNodes;
    }


    public void setVirtualNodes(int virtualNodes) {
        if (virtualNodes <= 0) {
            throw new IllegalArgumentException(
                    "virtualNodes must be > 0, got: " + virtualNodes);
        }
        this.virtualNodes = virtualNodes;
    }

    public String getHashAlgorithm() {
        return hashAlgorithm;
    }

    public void setHashAlgorithm(String hashAlgorithm) {

        this.hashAlgorithm = hashAlgorithm;
    }

    @Override
    public String toString() {
        return "HashRingProperties{virtualNodes=" + virtualNodes +
                ", hashAlgorithm='" + hashAlgorithm + "'}";
    }
}