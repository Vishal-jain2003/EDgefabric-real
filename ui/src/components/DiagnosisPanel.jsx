import { useCallback, useEffect, useState } from 'react'
import { explainClusterHealth, explainSlo, explainLatency, explainSelfHealing } from '../api/agentApi'

const severityConfig = {
  CRITICAL: { color: '#f87171', bg: 'rgba(239,68,68,0.08)', border: 'rgba(239,68,68,0.3)', icon: '🚨' },
  WARNING: { color: '#fbbf24', bg: 'rgba(245,158,11,0.08)', border: 'rgba(245,158,11,0.3)', icon: '⚠️' },
  INFO: { color: '#60a5fa', bg: 'rgba(96,165,250,0.08)', border: 'rgba(96,165,250,0.3)', icon: 'ℹ️' },
  OK: { color: '#4ade80', bg: 'rgba(34,197,94,0.08)', border: 'rgba(34,197,94,0.3)', icon: '✅' },
}

function DiagnosisCard({ diagnosis, label }) {
  if (!diagnosis) return null
  const sev = severityConfig[diagnosis.severity] || severityConfig.OK

  return (
    <div
      className="rounded-lg p-3 transition-all duration-300"
      style={{ background: sev.bg, border: `1px solid ${sev.border}` }}
    >
      <div className="mb-2 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-sm">{sev.icon}</span>
          <span className="text-xs font-bold uppercase tracking-wider text-gray-300">{label}</span>
        </div>
        <span
          className="rounded-full px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider"
          style={{ background: `${sev.color}18`, border: `1px solid ${sev.color}44`, color: sev.color }}
        >
          {diagnosis.severity}
        </span>
      </div>

      {/* Human-readable diagnosis string */}
      <p className="text-xs text-gray-300 leading-relaxed">{diagnosis.diagnosisString || diagnosis.diagnosis_string}</p>

      {/* Failure mode badge */}
      {(diagnosis.failureMode || diagnosis.failure_mode) && (
        <div className="mt-2">
          <span
            className="rounded px-2 py-0.5 font-mono text-[10px]"
            style={{ background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.1)', color: sev.color }}
          >
            {(diagnosis.failureMode || diagnosis.failure_mode).replace(/_/g, ' ')}
          </span>
        </div>
      )}

      {/* Evidence (collapsed by default for non-critical) */}
      {diagnosis.evidence && diagnosis.evidence.length > 0 && (
        <div className="mt-2">
          <div className="flex flex-wrap gap-1">
            {diagnosis.evidence.slice(0, 4).map((ev, i) => (
              <span key={i} className="rounded px-1.5 py-0.5 font-mono text-[9px] text-gray-500" style={{ background: 'rgba(0,0,0,0.3)' }}>
                {ev}
              </span>
            ))}
            {diagnosis.evidence.length > 4 && (
              <span className="text-[9px] text-gray-600">+{diagnosis.evidence.length - 4} more</span>
            )}
          </div>
        </div>
      )}

      {/* Recommendations for non-OK */}
      {diagnosis.severity !== 'OK' && diagnosis.recommendations && diagnosis.recommendations.length > 0 && (
        <div className="mt-2 border-t border-white/5 pt-2">
          <p className="mb-1 text-[9px] font-bold uppercase tracking-wider text-gray-500">Recommendations</p>
          <ul className="space-y-0.5">
            {diagnosis.recommendations.slice(0, 3).map((rec, i) => (
              <li key={i} className="text-[10px] text-gray-400 flex items-start gap-1">
                <span className="mt-0.5 text-[8px]">→</span>
                <span>{rec}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

export default function DiagnosisPanel() {
  const [diagnoses, setDiagnoses] = useState({})
  const [loading, setLoading] = useState(true)
  const [lastRun, setLastRun] = useState(null)
  const [running, setRunning] = useState(false)

  const runDiagnosis = useCallback(async () => {
    setRunning(true)
    try {
      const [cluster, slo, latency, selfHealing] = await Promise.allSettled([
        explainClusterHealth(),
        explainSlo(),
        explainLatency(),
        explainSelfHealing(),
      ])

      const results = {}
      if (cluster.status === 'fulfilled') results.cluster = cluster.value
      if (slo.status === 'fulfilled') results.slo = slo.value
      if (latency.status === 'fulfilled') results.latency = latency.value
      if (selfHealing.status === 'fulfilled') results.selfHealing = selfHealing.value

      setDiagnoses(results)
      setLastRun(new Date())
    } catch {
      // Explain API may not be available yet
    } finally {
      setLoading(false)
      setRunning(false)
    }
  }, [])

  useEffect(() => {
    runDiagnosis()
    const id = setInterval(runDiagnosis, 60000) // refresh every 60s
    return () => clearInterval(id)
  }, [runDiagnosis])

  // Determine overall severity
  const allSeverities = Object.values(diagnoses).map((d) => d?.severity).filter(Boolean)
  const overallSeverity = allSeverities.includes('CRITICAL')
    ? 'CRITICAL'
    : allSeverities.includes('WARNING')
      ? 'WARNING'
      : allSeverities.length > 0
        ? 'OK'
        : 'UNKNOWN'
  const overallConfig = severityConfig[overallSeverity] || severityConfig.OK

  if (loading) {
    return (
      <div className="mb-6 rounded-xl p-4 skeleton" style={{ height: '200px' }} />
    )
  }

  if (Object.keys(diagnoses).length === 0) return null

  return (
    <div
      className="mb-6 rounded-xl p-4 animate-fade-in-up"
      style={{
        background: 'linear-gradient(135deg, #0d0505, #0a0a0a)',
        border: `1px solid ${overallConfig.border}`,
        boxShadow: `0 4px 20px rgba(0,0,0,0.3)`,
        animationDelay: '300ms',
        opacity: 0,
        animationFillMode: 'forwards',
      }}
    >
      {/* Header */}
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="h-1 w-6 rounded-full" style={{ background: `linear-gradient(90deg, ${overallConfig.color}, transparent)` }} />
          <h2 className="text-sm font-bold uppercase tracking-wider text-gray-300">AI Diagnosis</h2>
          <span
            className="rounded-full px-2 py-0.5 text-[9px] font-bold uppercase"
            style={{ background: overallConfig.bg, border: `1px solid ${overallConfig.border}`, color: overallConfig.color }}
          >
            {overallSeverity}
          </span>
        </div>
        <div className="flex items-center gap-3">
          {lastRun && (
            <span className="text-[10px] text-gray-600">
              {lastRun.toLocaleTimeString()}
            </span>
          )}
          <button
            onClick={runDiagnosis}
            disabled={running}
            className="rounded-lg px-3 py-1 text-[10px] font-bold uppercase tracking-wider text-gray-400 transition-all hover:text-white disabled:opacity-50"
            style={{ background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.1)' }}
          >
            {running ? '⟳ Running...' : '⟳ Re-diagnose'}
          </button>
        </div>
      </div>

      {/* Diagnosis grid */}
      <div className="grid gap-2 sm:grid-cols-2">
        {diagnoses.cluster && <DiagnosisCard diagnosis={diagnoses.cluster} label="Cluster Health" />}
        {diagnoses.slo && <DiagnosisCard diagnosis={diagnoses.slo} label="SLO Status" />}
        {diagnoses.latency && <DiagnosisCard diagnosis={diagnoses.latency} label="Latency" />}
        {diagnoses.selfHealing && <DiagnosisCard diagnosis={diagnoses.selfHealing} label="Self-Healing" />}
      </div>
    </div>
  )
}
