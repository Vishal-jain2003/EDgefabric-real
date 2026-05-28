package com.edgefabric.caching.service;

import com.edgefabric.caching.config.ClusterJoinProperties;
import com.edgefabric.caching.membership.MembershipList;
import com.edgefabric.caching.model.NodeInfo;
import com.edgefabric.caching.resolver.DnsNodeDiscoveryResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ClusterJoinService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DnsNodeDiscoveryResolver resolver;
    private final MembershipList membershipList;
    private final RestClient restClient;
    private final ClusterJoinProperties joinProperties;

    public ClusterJoinService(DnsNodeDiscoveryResolver resolver,
                              MembershipList membershipList,
                              @Qualifier("peerRestClient") RestClient restClient,
                              ClusterJoinProperties joinProperties) {
        this.resolver = resolver;
        this.membershipList = membershipList;
        this.restClient = restClient;
        this.joinProperties = joinProperties;
    }

    /**
     * Entry point called by {@link com.edgefabric.caching.config.ClusterJoinListener}
     * once the application context is fully started.
     *
     * <ol>
     *   <li><b>Jitter</b> — sleep a random duration in [0, jitterMaxMs) so that all
     *       nodes that start at the same time (e.g. ASG scale-out) don't hit each
     *       other with join requests simultaneously.</li>
     *   <li><b>Single-shot DNS join</b> — resolve DNS once and try to join. No retry
     *       loop; the reconciliation thread below handles catch-up.</li>
     *   <li><b>DNS reconciliation thread</b> — a daemon background thread that
     *       periodically re-resolves DNS, compares the result against the current
     *       membership list, and tries to join any nodes that are in DNS but not yet
     *       known. Stops itself as soon as the two sets converge.</li>
     * </ol>
     */
    @Async
    public void joinCluster() {
        // ── 1. Jitter ──────────────────────────────────────────────────────────────
        applyStartupJitter();

        // ── 2. Single-shot join (no retry loop) ───────────────────────────────────
        List<String> dnsIps = resolver.resolve();
        if (dnsIps.isEmpty()) {
            log.warn("No nodes found via DNS on initial lookup; starting standalone");
        } else {
            tryJoinVia(dnsIps);
        }

        // ── 3. Start background DNS-reconciliation thread ─────────────────────────
        startDnsReconciliationThread();
    }

    // ── Jitter ────────────────────────────────────────────────────────────────────

    private void applyStartupJitter() {
        long maxJitter = joinProperties.getJitterMaxMs();
        if (maxJitter <= 0) return;
        long jitterMs = (long) (SECURE_RANDOM.nextDouble() * maxJitter);
        log.info("Startup jitter: sleeping {}ms before DNS lookup (max={}ms)", jitterMs, maxJitter);
        try {
            Thread.sleep(jitterMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── DNS reconciliation thread ──────────────────────────────────────────────

    /**
     * Starts a daemon thread that watches DNS vs the local membership list.
     * Does nothing at all when {@code dnsReconcileTimeoutMs ≤ 0} (used by unit tests
     * to prevent the thread from interfering with mock verifications).
     */
    private void startDnsReconciliationThread() {
        if (joinProperties.getDnsReconcileTimeoutMs() <= 0) {
            log.debug("DNS reconciliation thread disabled (timeout <= 0)");
            return;
        }
        Thread t = new Thread(this::runDnsReconciliation, "dns-reconciliation");
        t.setDaemon(true);
        t.start();
        log.info("DNS reconciliation thread started (checkInterval={}ms, maxLifetime={}ms)",
                joinProperties.getDnsReconcileIntervalMs(),
                joinProperties.getDnsReconcileTimeoutMs());
    }

    /**
     * Core reconciliation loop.
     *
     * <p>Every {@code dnsReconcileIntervalMs} it:
     * <ol>
     *   <li>Re-resolves DNS to get the current peer IP set.</li>
     *   <li>Compares it against the ALIVE/SUSPECT nodes already in the membership list.</li>
     *   <li>If every non-self DNS IP is already a known peer → <b>stops the thread</b>
     *       (DNS and membership list have converged).</li>
     *   <li>Otherwise → calls {@link #tryJoinVia} for only the <em>missing</em> IPs
     *       (avoids re-contacting peers we already know about) and loops again.</li>
     * </ol>
     */
    private void runDnsReconciliation() {
        long deadline = System.currentTimeMillis() + joinProperties.getDnsReconcileTimeoutMs();
        NodeInfo self = membershipList.getSelf();

        while (!Thread.currentThread().isInterrupted()
                && System.currentTimeMillis() < deadline) {

            // Wait before each check so we give gossip time to propagate first
            try {
                Thread.sleep(joinProperties.getDnsReconcileIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            // ── Re-resolve DNS ───────────────────────────────────────────────────
            List<String> dnsIps = resolver.resolve();
            if (dnsIps.isEmpty()) {
                log.debug("DNS reconciliation: no IPs resolved yet, will retry");
                continue;
            }

            // ── Build the set of non-self IPs that DNS says should exist ─────────
            Set<String> dnsNonSelfHosts = dnsIps.stream()
                    .filter(ip -> !ip.equals(self.getHost()))
                    .collect(Collectors.toSet());

            if (dnsNonSelfHosts.isEmpty()) {
                // This node is the only one in DNS — cluster of one, nothing to reconcile
                log.info("DNS reconciliation: only self found in DNS. Stopping.");
                return;
            }

            // ── Build the set of peer hosts already in our membership list ───────
            Set<String> knownPeerHosts = membershipList.getAliveNodes().stream()
                    .map(NodeInfo::getHost)
                    .filter(h -> !h.equals(self.getHost()))
                    .collect(Collectors.toSet());

            // ── Compute difference ───────────────────────────────────────────────
            Set<String> missingHosts = new HashSet<>(dnsNonSelfHosts);
            missingHosts.removeAll(knownPeerHosts);

            if (missingHosts.isEmpty()) {
                // ✅ DNS and membership list fully agree — job done, kill the thread
                log.info("DNS reconciliation complete: all {}/{} DNS peer(s) are present in"
                        + " the membership list. Reconciliation thread stopping.",
                        dnsNonSelfHosts.size(), dnsNonSelfHosts.size());
                return;
            }

            // ── Not yet converged — try joining via the missing nodes only ───────
            log.info("DNS reconciliation: {}/{} peer(s) in DNS but not yet in membership list: {}."
                    + " Attempting join.",
                    missingHosts.size(), dnsNonSelfHosts.size(), missingHosts);
            tryJoinVia(new ArrayList<>(missingHosts));
        }

        log.info("DNS reconciliation thread stopping (deadline reached or interrupted)");
    }

    // ── Join logic ─────────────────────────────────────────────────────────────

    /**
     * Tries each IP in {@code ipList} (in shuffled order) until one responds with
     * a non-empty membership list. Merges the response into the local list and
     * returns {@code true}. Returns {@code false} if no node could be reached.
     *
     * <p>Self's own IP is always skipped regardless of what is in the list.
     */
    private boolean tryJoinVia(List<String> ipList) {
        NodeInfo self = membershipList.getSelf();

        List<String> shuffled = new ArrayList<>(ipList);
        Collections.shuffle(shuffled, SECURE_RANDOM);

        for (String ip : shuffled) {
            if (ip.equals(self.getHost())) continue;

            String url = "http://" + ip + ":" + self.getServicePort() + "/cluster/join";

            try {
                log.info("Attempting cluster join via: {}", ip);

                List<NodeInfo> response = restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(self)
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<NodeInfo>>() {});

                if (response != null && !response.isEmpty()) {
                    response.forEach(membershipList::merge);
                    log.info("Successfully joined cluster via: {}", ip);
                    return true;
                }

            } catch (ResourceAccessException e) {
                log.warn("Network error while connecting to {}: {}", ip, e.getMessage());
            } catch (RestClientResponseException e) {
                log.warn("HTTP error from {}: status={}, body={}", ip, e.getStatusCode(), e.getResponseBodyAsString());
            }
        }

        return false;
    }
}