# Runbook: HighFalsePositiveRate

## Alert Summary
**Severity**: WARNING  
**Component**: Failure Detector  
**SLO Impact**: Unnecessary node churn → increased latency  

## Diagnosis
Check failure detector settings (ping timeout, suspect timeout) and network latency between nodes.

## Remediation
Increase `failure-detector.ping-timeout-ms` if network is consistently slow.

---
**Last Updated**: 2026-05-12
