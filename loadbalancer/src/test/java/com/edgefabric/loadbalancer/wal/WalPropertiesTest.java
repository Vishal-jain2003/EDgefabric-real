package com.edgefabric.loadbalancer.wal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WalProperties} — covers default values and all getters/setters
 * added when bounded-drain and local-storage support was introduced.
 */
class WalPropertiesTest {

    @Test
    void defaultValues_areCorrect() {
        WalProperties p = new WalProperties();

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getStorage()).isEqualTo("s3");
        assertThat(p.getS3Bucket()).isEqualTo("ef-hermes-wal");
        assertThat(p.getRegion()).isEqualTo("ap-south-1");
        assertThat(p.getLbId()).isEqualTo("lb1");
        assertThat(p.getSegmentSizeBytes()).isEqualTo(4 * 1024 * 1024);
        assertThat(p.getFlushBatchSize()).isEqualTo(100);
        assertThat(p.getBufferHeadroom()).isEqualTo(50);
        assertThat(p.getMaxFlushRetries()).isEqualTo(3);
        assertThat(p.getRetryBackoffMs()).isEqualTo(500L);
        assertThat(p.getFlushIntervalMs()).isEqualTo(500L);
        assertThat(p.getLocalDir()).isEqualTo("wal-local");
    }

    @Test
    void setters_updateAllFields() {
        WalProperties p = new WalProperties();

        p.setEnabled(true);
        p.setStorage("local");
        p.setS3Bucket("my-bucket");
        p.setRegion("us-east-1");
        p.setLbId("lb99");
        p.setSegmentSizeBytes(1024);
        p.setFlushBatchSize(200);
        p.setBufferHeadroom(100);
        p.setMaxFlushRetries(5);
        p.setRetryBackoffMs(250L);
        p.setFlushIntervalMs(1000L);
        p.setLocalDir("/data/wal");

        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getStorage()).isEqualTo("local");
        assertThat(p.getS3Bucket()).isEqualTo("my-bucket");
        assertThat(p.getRegion()).isEqualTo("us-east-1");
        assertThat(p.getLbId()).isEqualTo("lb99");
        assertThat(p.getSegmentSizeBytes()).isEqualTo(1024);
        assertThat(p.getFlushBatchSize()).isEqualTo(200);
        assertThat(p.getBufferHeadroom()).isEqualTo(100);
        assertThat(p.getMaxFlushRetries()).isEqualTo(5);
        assertThat(p.getRetryBackoffMs()).isEqualTo(250L);
        assertThat(p.getFlushIntervalMs()).isEqualTo(1000L);
        assertThat(p.getLocalDir()).isEqualTo("/data/wal");
    }

    @Test
    void bufferCapacity_isFlushBatchSizePlusHeadroom() {
        WalProperties p = new WalProperties();
        p.setFlushBatchSize(100);
        p.setBufferHeadroom(50);

        assertThat(p.getFlushBatchSize() + p.getBufferHeadroom()).isEqualTo(150);
    }
}
