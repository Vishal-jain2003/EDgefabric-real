package com.edgefabric.agentops.act;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActionRepositoryTest {

    private ActionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ActionRepository();
    }

    @Test
    void save_and_findById() {
        AgentAction action = buildAction(ActionStatus.PENDING_APPROVAL);
        repository.save(action);

        Optional<AgentAction> found = repository.findById(action.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(action.getId());
        assertThat(found.get().getStatus()).isEqualTo(ActionStatus.PENDING_APPROVAL);
    }

    @Test
    void findByStatus_returnsOnlyMatchingStatus() {
        AgentAction pending = buildAction(ActionStatus.PENDING_APPROVAL);
        AgentAction approved = buildAction(ActionStatus.APPROVED);
        AgentAction rejected = buildAction(ActionStatus.REJECTED);
        repository.save(pending);
        repository.save(approved);
        repository.save(rejected);

        List<AgentAction> pendingResults = repository.findByStatus(ActionStatus.PENDING_APPROVAL);

        assertThat(pendingResults).hasSize(1);
        assertThat(pendingResults.get(0).getId()).isEqualTo(pending.getId());
    }

    @Test
    void findAll_returnsSortedListByProposedAtDescending() {
        Instant base = Instant.now();
        AgentAction first = buildActionAt(base.minusSeconds(10));
        AgentAction second = buildActionAt(base.minusSeconds(5));
        AgentAction third = buildActionAt(base);

        repository.save(first);
        repository.save(second);
        repository.save(third);

        List<AgentAction> all = repository.findAll();

        assertThat(all).hasSize(3);
        assertThat(all.get(0).getId()).isEqualTo(third.getId());
        assertThat(all.get(1).getId()).isEqualTo(second.getId());
        assertThat(all.get(2).getId()).isEqualTo(first.getId());
    }

    @Test
    void findRecent_returnsLimitedSortedList() {
        Instant base = Instant.now();
        AgentAction first = buildActionAt(base.minusSeconds(10));
        AgentAction second = buildActionAt(base.minusSeconds(5));
        AgentAction third = buildActionAt(base);

        repository.save(first);
        repository.save(second);
        repository.save(third);

        List<AgentAction> recent = repository.findRecent(2);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).getId()).isEqualTo(third.getId());
        assertThat(recent.get(1).getId()).isEqualTo(second.getId());
    }

    @Test
    void findByStatus_multipleActions_returnsOnlyMatching() {
        AgentAction pending1 = buildAction(ActionStatus.PENDING_APPROVAL);
        AgentAction pending2 = buildAction(ActionStatus.PENDING_APPROVAL);
        AgentAction approved = buildAction(ActionStatus.APPROVED);

        repository.save(pending1);
        repository.save(approved);
        repository.save(pending2);

        List<AgentAction> pendingResults = repository.findByStatus(ActionStatus.PENDING_APPROVAL);

        assertThat(pendingResults).hasSize(2);
        assertThat(pendingResults).extracting(AgentAction::getId).contains(pending1.getId(), pending2.getId());
    }

    @Test
    void findAll_emptyRepository_returnsEmptyList() {
        List<AgentAction> all = repository.findAll();

        assertThat(all).isEmpty();
    }

    @Test
    void findByStatus_emptyRepository_returnsEmptyList() {
        List<AgentAction> results = repository.findByStatus(ActionStatus.PENDING_APPROVAL);

        assertThat(results).isEmpty();
    }

    @Test
    void findRecent_limitExceedsSize_returnsAllActions() {
        AgentAction first = buildAction(ActionStatus.PENDING_APPROVAL);
        AgentAction second = buildAction(ActionStatus.APPROVED);

        repository.save(first);
        repository.save(second);

        List<AgentAction> recent = repository.findRecent(10);

        assertThat(recent).hasSize(2);
    }

    private AgentAction buildAction(ActionStatus status) {
        return AgentAction.builder()
                .id(UUID.randomUUID().toString())
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(status)
                .reasoning("test reason")
                .proposedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }

    private AgentAction buildActionAt(Instant proposedAt) {
        return AgentAction.builder()
                .id(UUID.randomUUID().toString())
                .type(ActionType.DRAIN)
                .target("node-1")
                .status(ActionStatus.PENDING_APPROVAL)
                .reasoning("test reason")
                .proposedAt(proposedAt)
                .expiresAt(proposedAt.plusSeconds(900))
                .build();
    }
}
