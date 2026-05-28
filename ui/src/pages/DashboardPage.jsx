import { useCallback, useEffect, useState } from 'react'
import { fetchAlerts, fetchSlo, fetchSnapshot } from '../api/agentApi'
import DiagnosisPanel from '../components/DiagnosisPanel'
import NodeCard from '../components/NodeCard'
import StatusBadge from '../components/StatusBadge'

function SloPanel({ slo }) {
  if (!slo) return null
  const burnRate = slo.burnRate1h
  const avail = slo.availabilityRatio
  const health = slo.overallHealth ?? 'UNKNOWN'
  const healthColor = health === 'OK' ? '#4ade80' : health === 'WARNING' ? '#fbbf24' : health === 'CRITICAL' ? '#f87171' : '#6b7280'
  const healthBg = health === 'OK' ? 'rgba(34,197,94,0.08)' : health === 'WARNING' ? 'rgba(245,158,11,0.08)' : health === 'CRITICAL' ? 'rgba(239,68,68,0.08)' : 'rgba(255,255,255,0.04)'

  return (
    <div
      className="mb-6 rounded-xl p-4 animate-fade-in-up"
      style={{ background: healthBg, border: `1px solid ${healthColor}33`, animationDelay: '200ms', opacity: 0, animationFillMode: 'forwards' }}
    >
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="h-1 w-6 rounded-full" style={{ background: `linear-gradient(90deg, ${healthColor}, transparent)` }} />
          <h2 className="text-sm font-bold uppercase tracking-wider text-gray-300">SLO Status</h2>
        </div>
        <span
          className="rounded-full px-3 py-1 text-[11px] font-bold uppercase tracking-wider"
          style={{ background: healthBg, border: `1px solid ${healthColor}55`, color: healthColor }}
        >
          {health}
        </span>
      </div>
      <div className="grid gap-3 sm:grid-cols-3">
        {[
          { label: 'Availability', value: avail != null ? `${(avail * 100).toFixed(4)}%` : '---', color: avail != null && avail >= 0.999 ? '#4ade80' : '#f87171' },
          { label: 'Burn Rate (1h)', value: burnRate != null ? burnRate.toFixed(2) : '---', color: burnRate != null && burnRate > 10 ? '#f87171' : burnRate != null && burnRate > 1 ? '#fbbf24' : '#4ade80' },
          { label: 'Error Rate (5m)', value: slo.errorRate5m != null ? `${(slo.errorRate5m * 100).toFixed(3)}%` : '---', color: '#9ca3af' },
        ].map(({ label, value, color }) => (
          <div key={label} className="rounded-lg p-3" style={{ background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.06)' }}>
            <p className="text-[11px] uppercase tracking-wider text-gray-500">{label}</p>
            <p className="mt-1 font-mono text-lg font-bold tabular-nums" style={{ color, textShadow: `0 0 12px ${color}66` }}>{value}</p>
          </div>
        ))}
      </div>
    </div>
  )
}

function AlertFeed({ alerts }) {
  if (!alerts || alerts.length === 0) return null
  const severityColor = (s) => {
    if (!s) return '#6b7280'
    const u = s.toUpperCase()
    if (u === 'CRITICAL') return '#f87171'
    if (u === 'WARNING') return '#fbbf24'
    return '#60a5fa'
  }
  return (
    <div className="mb-6 animate-fade-in-up" style={{ animationDelay: '400ms', opacity: 0, animationFillMode: 'forwards' }}>
      <div className="mb-3 flex items-center gap-2">
        <div className="h-1 w-6 rounded-full" style={{ background: 'linear-gradient(90deg, #f87171, transparent)' }} />
        <h2 className="text-sm font-bold uppercase tracking-wider text-gray-300">Recent Alerts ({alerts.length})</h2>
      </div>
      <div className="space-y-2 max-h-56 overflow-y-auto pr-1">
        {alerts.slice(0, 8).map((a, i) => (
          <div
            key={i}
            className="rounded-lg px-3 py-2.5 flex items-start gap-3"
            style={{ background: `rgba(0,0,0,0.3)`, border: `1px solid ${severityColor(a.severity)}22` }}
          >
            <span
              className="mt-0.5 rounded-full px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider flex-shrink-0"
              style={{ background: `${severityColor(a.severity)}18`, border: `1px solid ${severityColor(a.severity)}44`, color: severityColor(a.severity) }}
            >
              {a.severity ?? 'INFO'}
            </span>
            <div className="min-w-0">
              <p className="truncate text-xs font-semibold text-gray-200">{a.alertName ?? a.name ?? 'Alert'}</p>
              {a.summary && <p className="mt-0.5 text-[11px] text-gray-500 line-clamp-1">{a.summary}</p>}
              {a.aiAnalysis && <p className="mt-1 text-[11px] text-gray-400 italic line-clamp-2">🤖 {a.aiAnalysis}</p>}
            </div>
            <span className="ml-auto flex-shrink-0 text-[10px] text-gray-600">
              {a.receivedAt ? new Date(a.receivedAt).toLocaleTimeString() : ''}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

export default function DashboardPage({ onAnalyze }) {
  const [snapshot, setSnapshot] = useState(null)
  const [slo, setSlo] = useState(null)
  const [alerts, setAlerts] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [lastUpdated, setLastUpdated] = useState(null)
  const [analyzing, setAnalyzing] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const [snapData, sloData, alertData] = await Promise.allSettled([
        fetchSnapshot(),
        fetchSlo(),
        fetchAlerts(),
      ])
      if (snapData.status === 'fulfilled') { setSnapshot(snapData.value); setError(null) }
      else setError('Could not reach agentic-ops at :8090. Make sure it is running.')
      if (sloData.status === 'fulfilled') setSlo(sloData.value)
      if (alertData.status === 'fulfilled') setAlerts(alertData.value)
      setLastUpdated(new Date())
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
    const id = setInterval(refresh, 30000)
    return () => clearInterval(id)
  }, [refresh])

  const handleAnalyze = () => {
    setAnalyzing(true)
    setTimeout(() => {
      setAnalyzing(false)
      onAnalyze?.('Analyze my cluster health now. Check all node statuses, hit rates, memory usage, gossip health, SLO burn rate, latency P99, and any active alerts. Give me specific recommendations and flag any risks.')
    }, 600)
  }

  if (loading) {
    return (
      <div className="p-8">
        <div className="mb-6 h-8 w-48 rounded-lg skeleton" />
        <div className="mb-8 grid grid-cols-4 gap-4">
          {[...Array(4)].map((_, i) => <div key={i} className="h-28 rounded-xl skeleton" />)}
        </div>
        <div className="grid grid-cols-3 gap-4">
          {[...Array(4)].map((_, i) => <div key={i} className="h-44 rounded-xl skeleton" />)}
        </div>
      </div>
    )
  }

  if (error && !snapshot) {
    return (
      <div className="flex h-full items-center justify-center p-8">
        <div
          className="max-w-md rounded-xl p-6 text-center"
          style={{ background: 'rgba(220,38,38,0.08)', border: '1px solid rgba(220,38,38,0.3)' }}
        >
          <div className="mb-3 text-3xl">⚠</div>
          <p className="font-semibold text-red-400">{error}</p>
        </div>
      </div>
    )
  }

  const summaryCards = [
    { label: 'Total Nodes', value: snapshot?.totalNodes, color: '#f3f4f6', glow: 'rgba(243,244,246,0.1)' },
    { label: 'Healthy', value: snapshot?.healthyNodes, color: '#4ade80', glow: 'rgba(34,197,94,0.15)' },
    { label: 'Suspect', value: snapshot?.suspectNodes, color: '#fbbf24', glow: 'rgba(245,158,11,0.15)' },
    { label: 'Dead / Unreachable', value: snapshot?.deadNodes, color: '#f87171', glow: 'rgba(239,68,68,0.15)' },
  ]

  return (
    <div className="h-full overflow-y-auto p-6">
      {/* Header */}
      <div className="mb-6 flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between animate-fade-in">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold text-white">Cluster Overview</h1>
            <span
              className="flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[10px] font-bold uppercase tracking-widest"
              style={{ background: 'rgba(34,197,94,0.1)', border: '1px solid rgba(34,197,94,0.3)', color: '#4ade80' }}
            >
              <span className="h-1.5 w-1.5 rounded-full bg-green-400 animate-pulse-dot" />
              Live
            </span>
          </div>
          {lastUpdated && (
            <p className="mt-1 text-xs text-gray-500">
              Updated {lastUpdated.toLocaleTimeString()} &mdash; auto-refreshes every 30s
            </p>
          )}
        </div>

        <button
          type="button"
          onClick={handleAnalyze}
          disabled={analyzing}
          className="relative overflow-hidden rounded-xl px-6 py-2.5 text-sm font-bold text-white transition-all duration-300 disabled:opacity-70"
          style={{
            background: analyzing
              ? 'linear-gradient(135deg, #7f1d1d, #991b1b)'
              : 'linear-gradient(135deg, #991b1b, #dc2626)',
            border: '1px solid rgba(239,68,68,0.5)',
            boxShadow: analyzing ? '0 0 30px rgba(220,38,38,0.5)' : '0 0 15px rgba(220,38,38,0.3)',
            animation: analyzing ? undefined : 'glowPulse 2.5s ease-in-out infinite',
          }}
        >
          {analyzing ? (
            <span className="flex items-center gap-2">
              <svg className="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M21 12a9 9 0 1 1-6.219-8.56" />
              </svg>
              Analyzing...
            </span>
          ) : (
            <span className="flex items-center gap-2">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="h-4 w-4">
                <path d="M9 3H5a2 2 0 0 0-2 2v4" /><path d="M9 21H5a2 2 0 0 1-2-2v-4" />
                <path d="M15 3h4a2 2 0 0 1 2 2v4" /><path d="M15 21h4a2 2 0 0 0 2-2v-4" />
                <circle cx="12" cy="12" r="3" />
              </svg>
              Analyze Now
            </span>
          )}
        </button>
      </div>

      {/* Summary cards */}
      <div className="mb-6 grid grid-cols-2 gap-3 xl:grid-cols-4">
        {summaryCards.map((card, i) => (
          <div
            key={card.label}
            className="animate-fade-in-up rounded-xl p-4"
            style={{
              background: 'linear-gradient(135deg, rgba(13,5,5,1), rgba(10,10,10,1))',
              border: `1px solid ${card.glow.replace('0.1', '0.25').replace('0.15', '0.25')}`,
              boxShadow: `0 4px 20px rgba(0,0,0,0.4), inset 0 1px 0 ${card.glow}`,
              animationDelay: `${i * 80}ms`,
              opacity: 0,
              animationFillMode: 'forwards',
            }}
          >
            <p className="text-xs font-medium uppercase tracking-wider text-gray-500">{card.label}</p>
            <p className="mt-2 text-4xl font-extrabold tabular-nums" style={{ color: card.color, textShadow: `0 0 20px ${card.glow}` }}>
              {card.value ?? 0}
            </p>
          </div>
        ))}
      </div>

      {/* SLO Panel */}
      <SloPanel slo={slo} />

      {/* AI Diagnosis Panel (Explain) */}
      <DiagnosisPanel />

      {/* Alerts Feed */}
      <AlertFeed alerts={alerts} />

      {/* Load Balancer */}
      {snapshot?.loadBalancer && (
        <div
          className="mb-6 rounded-xl p-4 animate-fade-in-up"
          style={{ background: 'linear-gradient(135deg, #0d0505, #0a0a0a)', border: '1px solid rgba(220,38,38,0.2)', boxShadow: '0 4px 20px rgba(0,0,0,0.3)', animationDelay: '320ms', opacity: 0, animationFillMode: 'forwards' }}
        >
          <div className="mb-3 flex items-center gap-2">
            <div className="h-1 w-6 rounded-full" style={{ background: 'linear-gradient(90deg, #dc2626, transparent)' }} />
            <h2 className="text-sm font-bold uppercase tracking-wider text-gray-300">Load Balancer</h2>
          </div>
          <div className="grid gap-4 text-sm sm:grid-cols-2 xl:grid-cols-4">
            {[
              { label: 'Status', value: <StatusBadge status={snapshot.loadBalancer.status} /> },
              { label: 'Active Nodes', value: snapshot.loadBalancer.activeNodeCount ?? '---' },
              { label: 'Ring Size', value: snapshot.loadBalancer.ringSize ?? '---' },
              { label: 'Hash Algorithm', value: snapshot.loadBalancer.hashAlgorithm ?? '---' },
            ].map(({ label, value }) => (
              <div key={label}>
                <p className="text-[11px] uppercase tracking-wider text-gray-500">{label}</p>
                <p className="mt-1 font-mono font-semibold text-gray-200">{value}</p>
              </div>
            ))}
          </div>
          {snapshot.loadBalancer.activeNodeIds?.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-1.5">
              {snapshot.loadBalancer.activeNodeIds.map((id) => (
                <span key={id} className="rounded px-2 py-0.5 font-mono text-[10px] text-gray-400" style={{ background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)' }}>
                  {id}
                </span>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Cluster aggregates */}
      {snapshot && (snapshot.totalClusterHits != null || snapshot.avgHitRate != null) && (
        <div className="mb-6 grid grid-cols-3 gap-3">
          {[
            { label: 'Total Cluster Hits', value: snapshot.totalClusterHits?.toLocaleString() ?? '---', color: '#4ade80' },
            { label: 'Total Cluster Misses', value: snapshot.totalClusterMisses?.toLocaleString() ?? '---', color: '#f87171' },
            { label: 'Avg Hit Rate', value: snapshot.avgHitRate != null ? `${(snapshot.avgHitRate).toFixed(1)}%` : '---', color: '#a78bfa' },
          ].map(({ label, value, color }) => (
            <div key={label} className="rounded-xl p-3" style={{ background: 'linear-gradient(135deg, #0d0505, #0a0a0a)', border: '1px solid rgba(255,255,255,0.07)' }}>
              <p className="text-[11px] uppercase tracking-wider text-gray-500">{label}</p>
              <p className="mt-1 font-mono text-lg font-bold tabular-nums" style={{ color }}>{value}</p>
            </div>
          ))}
        </div>
      )}

      {/* Node grid */}
      <div className="mb-3 flex items-center gap-2">
        <div className="h-1 w-6 rounded-full" style={{ background: 'linear-gradient(90deg, #dc2626, transparent)' }} />
        <h2 className="text-sm font-bold uppercase tracking-wider text-gray-300">
          Cache Nodes ({snapshot?.nodes?.length ?? 0})
        </h2>
      </div>

      {snapshot?.nodes?.length === 0 ? (
        <div className="rounded-xl p-8 text-center text-sm text-gray-500" style={{ border: '1px dashed rgba(220,38,38,0.2)' }}>
          No nodes discovered. Is the Load Balancer running?
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
          {snapshot?.nodes?.map((node, i) => (
            <NodeCard
              key={node.nodeId ?? `${node.host}-${node.port}`}
              node={node}
              style={{ opacity: 0, animation: `fadeInUp 0.5s ease-out ${400 + i * 80}ms forwards` }}
            />
          ))}
        </div>
      )}
    </div>
  )
}
