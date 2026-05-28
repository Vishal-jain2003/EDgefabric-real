package com.edgefabric.caching.service;

import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.model.Status;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class DrainService {

    private final MembershipList membershipList;
    private final AtomicInteger drainGauge = new AtomicInteger(0);
    private final AtomicReference<Long> drainStartTime = new AtomicReference<>(null);

    @Value("${drain.grace-period-ms:5000}")
    private long drainGracePeriodMs;

    public DrainService(MembershipList membershipList, MeterRegistry meterRegistry) {
        this.membershipList = membershipList;
        NodeInfo self = membershipList.getSelf();
        meterRegistry.gauge("edgefabric.node.drain.active",
                Tags.of("node_id", self.getCacheNodeId(), "node_host", self.getHost()),
                drainGauge);
    }

    public boolean isDraining() {
        return membershipList.getSelf().getStatus() == Status.DRAINING;
    }

    public boolean startDrain() {
        NodeInfo self = membershipList.getSelf();
        if (self.getStatus() != Status.ALIVE) {
            return false;
        }
        drainStartTime.set(System.currentTimeMillis());
        membershipList.markDraining(self.getCacheNodeId());
        if (self.getStatus() == Status.DRAINING) {
            drainGauge.set(1);
            log.info("Node {} entered DRAINING state with {} ms grace period",
                    self.getCacheNodeId(), drainGracePeriodMs);
            return true;
        }
        drainStartTime.set(null); // rollback timer if marking failed
        return false;
    }

    public boolean cancelDrain() {
        NodeInfo self = membershipList.getSelf();
        if (self.getStatus() != Status.DRAINING) {
            return false;
        }
        membershipList.cancelDraining(self.getCacheNodeId());
        drainGauge.set(0);
        drainStartTime.set(null);
        log.info("Node {} exited DRAINING state, back to ALIVE", self.getCacheNodeId());
        return true;
    }

    public boolean isDrainingWithinGracePeriod() {
        Long drainTime = drainStartTime.get();
        if (drainTime == null) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - drainTime;
        return elapsed < drainGracePeriodMs;
    }
}
