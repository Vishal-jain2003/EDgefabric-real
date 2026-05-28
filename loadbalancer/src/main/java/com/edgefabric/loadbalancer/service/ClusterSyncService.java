package com.edgefabric.loadbalancer.service;

import com.edgefabric.loadbalancer.config.ClusterSyncProperties;
import com.edgefabric.loadbalancer.dto.ClusterMemberResponse;
import com.edgefabric.loadbalancer.exception.ClusterBootstrapException;
import com.edgefabric.loadbalancer.metrics.ClusterSyncMetricsService;
import com.edgefabric.loadbalancer.model.CacheNode;
import com.edgefabric.loadbalancer.resolver.DnsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

@Service
public class ClusterSyncService {

    private static final Logger log = LoggerFactory.getLogger(ClusterSyncService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ParameterizedTypeReference<List<ClusterMemberResponse>> MEMBER_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final CacheRouter cacheRouter;
    private final WebClient webClient;
    private final ClusterSyncProperties props;
    private final DnsResolver dnsResolver;
    private final ClusterSyncMetricsService metricsService;
    private final AtomicReference<Set<CacheNode>> currentActiveNodes = new AtomicReference<>(Set.of());

    public ClusterSyncService(CacheRouter cacheRouter,
                              WebClient webClient,
                              ClusterSyncProperties props,
                              DnsResolver dnsResolver,
                              ClusterSyncMetricsService metricsService) {
        this.cacheRouter = cacheRouter;
        this.webClient = webClient;
        this.props = props;
        this.dnsResolver = dnsResolver;
        this.metricsService = metricsService;
    }

    @PostConstruct
    public void bootstrap() {
        log.info("Bootstrapping cluster sync via DNS: {}", props.getDnsName());
        try {
            Set<CacheNode> seeds = bootstrapFromDns();
            updateHashRing(seeds);
            log.info("DNS bootstrap successful — {} seed nodes loaded", seeds.size());
        } catch (ClusterBootstrapException e) {
            log.warn("Bootstrap found no nodes — LB will start empty and retry via scheduled sync: {}",
                    e.getMessage());
            metricsService.recordSyncError();
        }
    }

    private Set<CacheNode> bootstrapFromDns() {
        UnknownHostException lastDnsFailure = null;

        for (int attempt = 1; attempt <= props.getBootstrapMaxRetries(); attempt++) {
            try {
                Set<CacheNode> seeds = discoverViaDns();
                if (!seeds.isEmpty()) {
                    return seeds;
                }
                log.warn("DNS resolved but returned 0 nodes (attempt {}/{})",
                        attempt, props.getBootstrapMaxRetries());
            } catch (UnknownHostException e) {
                lastDnsFailure = e;
                log.warn("DNS resolution failed (attempt {}/{}): {}",
                        attempt, props.getBootstrapMaxRetries(), e.getMessage());
            }
            sleepBetweenRetries(props.getBootstrapRetryDelayMs());
        }

        throw new ClusterBootstrapException(
                "Failed to discover any cluster nodes from DNS ["
                        + props.getDnsName() + "] after "
                        + props.getBootstrapMaxRetries() + " attempts",
                lastDnsFailure);
    }

    @Scheduled(fixedDelayString = "${cluster.sync-interval-ms:5000}")
    public void syncWithCluster() {
        try {
            Optional<Set<CacheNode>> members = fetchMembershipFromRandomPeer();

            if (members.isPresent() && !members.get().isEmpty()) {
                log.info("Peer sync returned {} members — ring updated from gossip", members.get().size());
                updateHashRing(members.get());
                return;
            }

            if (members.isPresent()) {
                log.warn("Peer returned empty membership list, falling back to DNS");
            } else {
                log.info("Peer sync unavailable, falling back to DNS");
            }

            Optional<Set<CacheNode>> dnsResult = tryDnsFallback();
            if (dnsResult.isPresent()) {
                updateHashRing(dnsResult.get());
            } else {
                log.error("Cluster sync failed — both peer sync and DNS fallback returned no nodes");
                metricsService.recordSyncError();
            }
        } catch (Exception e) {
            log.error("Cluster sync failed: {}", e.getMessage(), e);
            metricsService.recordSyncError();
        }
    }

    Optional<Set<CacheNode>> fetchMembershipFromRandomPeer() {
        List<CacheNode> peers = new ArrayList<>(currentActiveNodes.get());

        if (peers.isEmpty()) {
            log.debug("No known peers — cannot fetch membership, will use DNS");
            return Optional.empty();
        }

        Collections.shuffle(peers, SECURE_RANDOM);

        for (CacheNode peer : peers) {
            Optional<Set<CacheNode>> result = fetchMembershipFromPeer(peer);
            if (result.isPresent()) {
                return result;
            }
        }

        log.warn("All {} known peers failed membership sync", peers.size());
        return Optional.empty();
    }

    private Optional<Set<CacheNode>> fetchMembershipFromPeer(CacheNode peer) {
        String url = buildMembershipUrl(peer);

        try {
            List<ClusterMemberResponse> body = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(MEMBER_LIST_TYPE)
                    .block(Duration.ofMillis(props.getSyncTimeoutMs()));

            if (body == null || body.isEmpty()) {
                log.warn("Empty membership response from peer {}", peer);
                return Optional.empty();
            }

            Set<CacheNode> members = body.stream()
                    .map(this::toCacheNode)
                    .collect(Collectors.toSet());

            log.info("Peer sync from {} returned {} members", peer, members.size());
            return Optional.of(members);

        } catch (Exception e) {
            log.warn("Peer sync to {} failed: {}", peer, e.getMessage());
            return Optional.empty();
        }
    }

    Optional<Set<CacheNode>> tryDnsFallback() {
        try {
            Set<CacheNode> nodes = discoverViaDns();
            if (nodes.isEmpty()) {
                log.warn("DNS fallback returned 0 nodes");
                return Optional.empty();
            }
            log.info("DNS fallback discovered {} nodes", nodes.size());
            return Optional.of(nodes);
        } catch (UnknownHostException e) {
            log.error("DNS fallback failed — hostname unresolvable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    Set<CacheNode> discoverViaDns() throws UnknownHostException {
        try {
            InetAddress[] addresses = dnsResolver.resolve(props.getDnsName());
            metricsService.recordDnsResolution(true);

            return Arrays.stream(addresses)
                    .map(addr -> {
                        String ip = addr.getHostAddress();
                        return new CacheNode(ip, ip, props.getNodePort());
                    })
                    .collect(Collectors.toSet());
        } catch (UnknownHostException e) {
            metricsService.recordDnsResolution(false);
            throw e;
        }
    }

    /**
     * Updates the hash ring with new node membership.
     * Removed synchronized keyword - ConsistentHashRing methods are internally thread-safe.
     * This prevents global lock contention during topology changes.
     */
    void updateHashRing(Set<CacheNode> newNodes) {
        Set<CacheNode> existingNodes = currentActiveNodes.get();

        Set<CacheNode> nodesToRemove = new HashSet<>(existingNodes);
        nodesToRemove.removeAll(newNodes);
        if (!nodesToRemove.isEmpty()) {
            log.info("Nodes to remove: {}", nodesToRemove.size());
            nodesToRemove.forEach(node -> {
                cacheRouter.removeNode(node);
                metricsService.recordNodeRemoved();
                log.info("Removed node from hash ring: {}", node);
            });
        }

        Set<CacheNode> nodesToAdd = new HashSet<>(newNodes);
        nodesToAdd.removeAll(existingNodes);
        if (!nodesToAdd.isEmpty()) {
            nodesToAdd.forEach(node -> {
                cacheRouter.addNode(node);
                metricsService.recordNodeAdded();
                log.info("Added node to hash ring: {}", node);
            });
        }

        currentActiveNodes.set(Set.copyOf(newNodes));

        if (!nodesToRemove.isEmpty() || !nodesToAdd.isEmpty()) {
            log.info("Hash ring updated — active nodes: {}", currentActiveNodes.get().size());
        }
    }

    private CacheNode toCacheNode(ClusterMemberResponse member) {
        return new CacheNode(member.getNodeId(), member.getHost(), member.getPort());
    }

    String buildMembershipUrl(CacheNode peer) {
        return "http://" + peer.getHost() + ":" + peer.getPort() + props.getMembershipPath();
    }

    private void sleepBetweenRetries(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(delayMs));
        if (Thread.currentThread().isInterrupted()) {
            log.warn("Bootstrap retry sleep interrupted");
        }
    }

    public Set<CacheNode> getActiveNodes() {
        return currentActiveNodes.get();
    }

    public int getActiveNodeCount() {
        return currentActiveNodes.get().size();
    }
}
