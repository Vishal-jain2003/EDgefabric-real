# EdgeFabric SLO Definitions and Burn-Rate Alerting

## Overview

This document defines the Service Level Objectives (SLOs) for EdgeFabric and explains the burn-rate methodology used for alerting.

---

## Service Level Indicators (SLIs)

### 1. Availability SLI

**Definition**: Percentage of time the cache service is reachable and serving requests.

**Measurement**:
```promql
availability = sum(rate(up{job=~"edgefabric-.*"}[1m])) / count(up{job=~"edgefabric-.*"})
```

**SLO Target**: 99.9% (three nines)

**Error Budget**: 0.1% = 43.2 minutes/month = 8.64 hours/year

**Why this matters**: Availability directly impacts customer ability to access cached content. Downtime translates to failed API requests and degraded user experience.

---

### 2. Latency SLI

**Definition**: 99th percentile (p99) request latency for cache GET operations.

**Measurement**:
```promql
latency_p99 = histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{job="edgefabric-loadbalancer"}[30m]))
```

**SLO Target**: p99 < 200ms for 99.5% of 30-minute windows

**Error Budget**: 0.5% = 216 minutes/month = 3.6 hours/year of p99 > 200ms

**Why this matters**: High latency impacts user-facing applications. A slow cache is almost as bad as no cache.

---

### 3. Error Rate SLI

**Definition**: Percentage of cache operations that result in misses (NOT including legitimate 404s for keys that were never written).

**Measurement**:
```promql
error_rate = sum(rate(edgefabric_cache_misses_total[5m])) / (sum(rate(edgefabric_cache_hits_total[5m])) + sum(rate(edgefabric_cache_misses_total[5m])))
```

**SLO Target**: Error rate < 1%

**Error Budget**: 1% of all cache operations can be misses

**Why this matters**: High miss rates indicate cache pollution, incorrect TTL configuration, or eviction pressure — all of which reduce cache effectiveness.

---

### 4. Quorum Success SLI

**Definition**: Percentage of quorum write operations that successfully meet the W=2 quorum threshold.

**Measurement**:
```promql
quorum_success = sum(rate(edgefabric_quorum_writes_total{result="success"}[1m])) / sum(rate(edgefabric_quorum_writes_total[1m]))
```

**SLO Target**: 99.9% (three nines)

**Error Budget**: 0.1% = 43.2 minutes/month of quorum write failures

**Why this matters**: Quorum write failures mean data is not being durably stored across replicas. This directly impacts data durability and eventual consistency guarantees.

---

## Burn-Rate Methodology

### What is Burn Rate?

**Burn rate** is the speed at which we consume our error budget relative to the SLO target.

- **Burn rate = 1.0**: consuming error budget at exactly the expected rate (SLO will be met precisely)
- **Burn rate = 2.0**: consuming error budget 2x faster than allowed (will exhaust budget in 15 days instead of 30)
- **Burn rate = 14.4**: consuming error budget 14.4x faster (will exhaust budget in 2 days)

### Why Multi-Window Burn-Rate Alerts?

Single-threshold alerts (e.g., "error rate > 5%") suffer from:
- **False positives**: brief spikes that don't threaten the SLO
- **Slow detection**: sustained issues at 1.5% miss rate won't trigger a 5% threshold but still burn error budget

**Burn-rate alerts solve this by:**
1. **Short window (1h)**: detect fast-burning incidents early
2. **Long window (6h)**: prevent false positives from transient spikes
3. **Multiple severity tiers**: different burn rates trigger different alert severities

### Burn-Rate Tiers

| Severity | Burn Rate | Time to Exhaust Budget | Alert Window |
|----------|-----------|------------------------|--------------|
| CRITICAL | 14.4x | 2 days | 1h |
| WARNING | 6.0x | 5 days | 3h |
| INFO | 3.0x | 10 days | 6h |

---

## Alerting Rules (Burn-Rate Examples)

### Availability SLO Burn-Rate (CRITICAL)

```yaml
- alert: AvailabilitySLOBurnRateHigh
  expr: |
    (
      (1 - (sum(rate(up{job=~"edgefabric-.*"}[1h])) / count(up{job=~"edgefabric-.*"})))
      / (1 - 0.999)
    ) > 14.4
  for: 5m
  labels:
    severity: critical
```

**Interpretation**: If availability drops below 99.9% for a sustained period, and the error rate is 14.4x higher than our budget allows, we'll exhaust the entire monthly error budget in 2 days.

---

## Error Budget Tracking

### Monthly Error Budget Calculation

For a 99.9% availability SLO over 30 days:

- **Total minutes**: 30 days × 24 hours × 60 minutes = 43,200 minutes
- **Error budget**: 0.1% of 43,200 = **43.2 minutes**
- **Remaining budget** = 43.2 - (actual downtime minutes)

### Error Budget Policy

| Remaining Budget | Action |
|------------------|--------|
| > 50% (21.6 min) | Green — normal operations, can deploy risky changes |
| 25-50% (10.8-21.6 min) | Yellow — deploy with caution, increase monitoring |
| < 25% (< 10.8 min) | Red — feature freeze, only deploy critical fixes |
| Exhausted | FREEZE — no deployments until budget resets next month |

---

## Rolling Window vs Calendar Window

**EdgeFabric uses a 30-day rolling window** (not calendar month) to smooth out budget consumption and avoid the "reset cliff" on the 1st of the month.

**Formula**:
```promql
error_budget_remaining = 43.2 - sum_over_time(
  (1 - (up{job=~"edgefabric-.*"})) [30d:1m]
)
```

---

## SLO Review Cadence

- **Weekly**: Review error budget consumption, burn-rate alert frequency
- **Monthly**: Adjust SLO targets if consistently under/over budget
- **Quarterly**: Re-evaluate SLI definitions based on user impact data

---

## References

- [Google SRE Workbook: Implementing SLOs](https://sre.google/workbook/implementing-slos/)
- [Google SRE Workbook: Alerting on SLOs](https://sre.google/workbook/alerting-on-slos/)
- [Prometheus Alerting Best Practices](https://prometheus.io/docs/practices/alerting/)

---

**Document Version**: 1.0  
**Last Updated**: 2026-05-12  
**Owned By**: EdgeFabric SRE Team
