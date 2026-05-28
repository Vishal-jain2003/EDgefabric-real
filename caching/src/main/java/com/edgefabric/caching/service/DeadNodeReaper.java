package com.edgefabric.caching.service;

import com.edgefabric.caching.config.FailureDetectorProperties;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Periodically scans the membership table for nodes that have been DEAD longer
 * than the configured TTL ({@code failure-detector.dead-node-ttl-ms}) and
 * permanently removes them.
 *
 * <h3>Why this is necessary</h3>
 * Without eviction, dead nodes accumulate forever in the membership map. This
 * wastes memory, inflates gossip digests, and slows down peer selection. After
 * a node has been DEAD long enough for every peer in the cluster to learn about
 * the death (well beyond the gossip convergence time), keeping it serves no
 * purpose.
 *
 * <h3>Safety</h3>
 * <ul>
 *   <li>Only nodes in {@code DEAD} status are eligible for eviction.</li>
 *   <li>The self node is never evicted.</li>
 *   <li>If a removed node later starts up again it will be re-discovered
 *       through DNS resolution or gossip (it will arrive as a fresh ALIVE node
 *       with incarnation 0).</li>
 *   <li>Eviction is disabled when {@code deadNodeTtlMs ≤ 0}.</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <pre>
 * failure-detector.dead-node-ttl-ms           = 300000  # 5 min (default)
 * failure-detector.dead-node-reap-interval-ms = 30000   # 30 sec (default)
 * </pre>
 */
@Slf4j
@Component
public class DeadNodeReaper {

    private final MembershipList membershipList;
    private final FailureDetectorProperties properties;
    private final Clock clock;

    @Autowired
    public DeadNodeReaper(MembershipList membershipList,
                          FailureDetectorProperties properties) {
        this(membershipList, properties, Clock.systemUTC());
    }

    /** Test-friendly constructor that accepts a custom {@link Clock}. */
    DeadNodeReaper(MembershipList membershipList,
                   FailureDetectorProperties properties,
                   Clock clock) {
        this.membershipList = membershipList;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs on a fixed delay defined by {@code failure-detector.dead-node-reap-interval-ms}.
     * Scans all DEAD nodes and evicts those whose {@code lastUpdatedTime} is older
     * than the TTL cutoff.
     */
    @Scheduled(fixedDelayString = "${failure-detector.dead-node-reap-interval-ms:30000}")
    public void reapDeadNodes() {
        long ttl = properties.getDeadNodeTtlMs();
        if (ttl <= 0) {
            return; // eviction disabled
        }

        long cutoff = clock.millis() - ttl;
        List<NodeInfo> deadNodes = membershipList.getDeadNodes();

        if (deadNodes.isEmpty()) {
            return;
        }

        int evicted = 0;
        for (NodeInfo node : deadNodes) {
            if (node.getLastUpdatedTime() < cutoff
                    && membershipList.removeNode(node.getCacheNodeId())) {
                evicted++;
                log.info("Evicted dead node '{}' from membership table " +
                                "(dead since {}ms ago, TTL={}ms)",
                        node.getCacheNodeId(),
                        clock.millis() - node.getLastUpdatedTime(),
                        ttl);
            }
        }

        if (evicted > 0) {
            log.info("Dead-node reaper: evicted {}/{} dead node(s); membership size now {}",
                    evicted, deadNodes.size(), membershipList.size());
        } else {
            log.debug("Dead-node reaper: {} dead node(s) found but none past TTL yet",
                    deadNodes.size());
        }
    }
}
