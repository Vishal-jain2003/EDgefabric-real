package com.edgefabric.agentops.act;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class ActionRepository {

    private final Map<String, AgentAction> store = new ConcurrentHashMap<>();

    public AgentAction save(AgentAction action) {
        store.put(action.getId(), action);
        return action;
    }

    public Optional<AgentAction> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<AgentAction> findAll() {
        return store.values().stream()
                .sorted(Comparator.comparing(AgentAction::getProposedAt).reversed())
                .toList();
    }

    public List<AgentAction> findByStatus(ActionStatus status) {
        return store.values().stream()
                .filter(a -> a.getStatus() == status)
                .sorted(Comparator.comparing(AgentAction::getProposedAt).reversed())
                .toList();
    }

    public List<AgentAction> findRecent(int limit) {
        return store.values().stream()
                .sorted(Comparator.comparing(AgentAction::getProposedAt).reversed())
                .limit(limit)
                .toList();
    }
}
