package com.edgefabric.loadbalancer.controller;

import com.edgefabric.loadbalancer.dto.WalAppendRequest;
import com.edgefabric.loadbalancer.service.CacheRouter;
import com.edgefabric.loadbalancer.wal.WalEntry;
import com.edgefabric.loadbalancer.wal.WalWriter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Internal endpoint used by cache nodes to journal bypass writes into the
 * load-balancer WAL.
 *
 * <p>The WAL append is best-effort: any failure is caught and logged; the
 * response is always {@code 202 Accepted} for a syntactically valid request.
 *
 * <p>The {@link WalWriter} is injected as an {@link Optional} so that when
 * {@code wal.enabled=false} the endpoint still exists (returns 202) but is a
 * silent no-op.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/wal")
@RequiredArgsConstructor
public class WalAppendController {

    private final Optional<WalWriter> walWriter;
    private final Optional<CacheRouter> cacheRouter;

    @PostMapping("/append")
    public ResponseEntity<Void> append(@Valid @RequestBody WalAppendRequest req) {
        walWriter.ifPresent(writer -> {
            try {
                byte[] data = decodeData(req.getDataBase64());
                Set<String> failedNodes = resolvePeerNodes(req.getKey(), req.getOriginatorNodeId());
                WalEntry entry = WalEntry.forQuorumPut(
                        req.getKey(),
                        data,
                        req.getExpiresAt(),
                        req.getContentType(),
                        req.getVersion(),
                        Set.of(req.getOriginatorNodeId()),
                        failedNodes
                );
                writer.append(entry);
            } catch (Exception e) {
                log.warn("WalAppendController: failed to append WAL entry key={}: {}", req.getKey(), e.getMessage());
                // Never propagate — always return 202
            }
        });

        return ResponseEntity.accepted().build();
    }

    private byte[] decodeData(String dataBase64) {
        if (dataBase64 == null || dataBase64.isEmpty()) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(dataBase64);
    }

    /**
     * Resolves the peer nodes for the given key from the consistent-hash ring,
     * excluding the originator node. Returns empty set if ring is unavailable.
     */
    private Set<String> resolvePeerNodes(String key, String originatorNodeId) {
        if (cacheRouter.isEmpty()) {
            return Set.of();
        }
        try {
            List<com.edgefabric.loadbalancer.model.CacheNode> nodes =
                    cacheRouter.get().routeToReplicas(key, Integer.MAX_VALUE);
            return nodes.stream()
                    .map(com.edgefabric.loadbalancer.model.CacheNode::getNodeId)
                    .filter(id -> !id.equals(originatorNodeId))
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.debug("WalAppendController: could not resolve peer nodes for key={}: {}", key, e.getMessage());
            return Set.of();
        }
    }
}
