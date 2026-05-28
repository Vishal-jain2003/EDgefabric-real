import { useEffect, useRef } from 'react'
import StatusBadge from './StatusBadge'

function formatPct(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return null
  const n = Number(value)
  return n <= 1 ? n * 100 : n
}

function hitRateColor(hr) {
  if (hr === null) return '#6b7280'
  if (hr > 70) return '#4ade80'
  if (hr >= 40) return '#fbbf24'
  return '#f87171'
}

function memBarColor(pct) {
  if (pct < 70) return 'linear-gradient(90deg, #16a34a, #22c55e)'
  if (pct <= 85) return 'linear-gradient(90deg, #b45309, #f59e0b)'
  return 'linear-gradient(90deg, #991b1b, #ef4444)'
}

function latencyColor(ms) {
  if (ms === null || ms === undefined) return '#6b7280'
  if (ms < 5) return '#4ade80'
  if (ms < 20) return '#a3e635'
  if (ms < 100) return '#fbbf24'
  return '#f87171'
}

function statusBorderColor(status) {
  if (status === 'HEALTHY') return 'rgba(34,197,94,0.35)'
  if (status === 'SUSPECT') return 'rgba(245,158,11,0.35)'
  if (status === 'DEAD' || status === 'UNREACHABLE') return 'rgba(239,68,68,0.35)'
  return 'rgba(220,38,38,0.15)'
}

function fmt(n, decimals = 0) {
  if (n === null || n === undefined) return '---'
  return Number(n).toLocaleString(undefined, { maximumFractionDigits: decimals })
}

export default function NodeCard({ node, style }) {
  const barRef = useRef(null)
  const hitRate = formatPct(node.hitRate ?? node.hitRatePercent)
  const memRatio = formatPct(
    node.memoryUsedRatio ??
      (node.memoryUsedBytes != null && node.memoryMaxBytes
        ? node.memoryUsedBytes / node.memoryMaxBytes
        : null),
  )
  const usagePct = memRatio === null ? 0 : Math.min(Math.max(memRatio, 0), 100)
  const host = node.host || node.hostname || node.ipAddress || '---'
  const port = node.port ?? '---'
  const gossipStatus = node.gossipStatus || node.membershipStatus || node.status || 'UNKNOWN'
  const evictions = node.evictionsPerMinute ?? node.evictionsMinute ?? node.evictionsPerMin
  const p50 = node.p50LatencyMs
  const p99 = node.p99LatencyMs
  const drainActive = node.drainActive === true

  useEffect(() => {
    if (barRef.current) {
      barRef.current.style.setProperty('--target-width', `${usagePct}%`)
    }
  }, [usagePct])

  return (
    <div
      className="group relative overflow-hidden rounded-xl p-4 transition-all duration-300 hover:-translate-y-0.5"
      style={{
        background: 'linear-gradient(135deg, #0d0505, #0a0a0a)',
        border: `1px solid ${statusBorderColor(node.status)}`,
        boxShadow: '0 4px 20px rgba(0,0,0,0.4)',
        ...style,
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.boxShadow = `0 8px 32px rgba(0,0,0,0.6), 0 0 20px ${statusBorderColor(node.status)}`
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.boxShadow = '0 4px 20px rgba(0,0,0,0.4)'
      }}
    >
      <div
        className="absolute right-0 top-0 h-12 w-12 opacity-20"
        style={{ background: 'radial-gradient(circle at top right, rgba(220,38,38,0.6), transparent)' }}
      />

      {/* Header */}
      <div className="mb-3 flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="truncate text-sm font-bold text-white" title={node.nodeId}>
            {node.nodeId?.replace('cache-node-', 'NODE-') ?? 'Unknown'}
          </h3>
          <p className="mt-0.5 font-mono text-xs text-gray-500">{host}:{port}</p>
        </div>
        <div className="flex flex-col items-end gap-1">
          <StatusBadge status={node.status ?? 'UNKNOWN'} />
          {drainActive && (
            <span
              className="rounded-full px-2 py-0.5 text-[9px] font-bold uppercase tracking-wider"
              style={{ background: 'rgba(251,191,36,0.15)', border: '1px solid rgba(251,191,36,0.4)', color: '#fbbf24' }}
            >
              DRAINING
            </span>
          )}
        </div>
      </div>

      <div className="space-y-2.5 text-sm">
        {/* Hit rate */}
        <div className="flex items-center justify-between">
          <span className="text-gray-500">Hit Rate</span>
          <span className="font-bold tabular-nums" style={{ color: hitRateColor(hitRate), textShadow: `0 0 10px ${hitRateColor(hitRate)}66` }}>
            {hitRate === null ? '---' : `${hitRate.toFixed(1)}%`}
          </span>
        </div>

        {/* Hits / Misses */}
        <div className="grid grid-cols-2 gap-2">
          <div className="rounded-lg p-2" style={{ background: 'rgba(34,197,94,0.05)', border: '1px solid rgba(34,197,94,0.1)' }}>
            <p className="text-[10px] text-gray-500">Hits</p>
            <p className="mt-0.5 font-mono text-xs font-semibold text-green-400">{fmt(node.totalHits)}</p>
          </div>
          <div className="rounded-lg p-2" style={{ background: 'rgba(239,68,68,0.05)', border: '1px solid rgba(239,68,68,0.1)' }}>
            <p className="text-[10px] text-gray-500">Misses</p>
            <p className="mt-0.5 font-mono text-xs font-semibold text-red-400">{fmt(node.totalMisses)}</p>
          </div>
        </div>

        {/* Latency P50/P99 */}
        <div className="grid grid-cols-2 gap-2">
          <div className="rounded-lg p-2" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
            <p className="text-[10px] text-gray-500">P50 Latency</p>
            <p className="mt-0.5 font-mono text-xs font-semibold" style={{ color: latencyColor(p50) }}>
              {p50 != null ? `${p50.toFixed(1)}ms` : '---'}
            </p>
          </div>
          <div className="rounded-lg p-2" style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)' }}>
            <p className="text-[10px] text-gray-500">P99 Latency</p>
            <p className="mt-0.5 font-mono text-xs font-semibold" style={{ color: latencyColor(p99) }}>
              {p99 != null ? `${p99.toFixed(1)}ms` : '---'}
            </p>
          </div>
        </div>

        {/* Memory bar */}
        <div>
          <div className="mb-1 flex items-center justify-between">
            <span className="text-gray-500">Memory</span>
            <span className="font-medium tabular-nums text-gray-300">
              {memRatio === null ? '---' : `${usagePct.toFixed(1)}%`}
            </span>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full" style={{ background: 'rgba(255,255,255,0.06)' }}>
            <div
              ref={barRef}
              className="h-full rounded-full animate-bar-grow"
              style={{ backgroundImage: memBarColor(usagePct), width: `${usagePct}%` }}
            />
          </div>
        </div>

        {/* Gossip / Evictions */}
        <div className="grid grid-cols-2 gap-2 rounded-lg p-2" style={{ background: 'rgba(220,38,38,0.04)', border: '1px solid rgba(220,38,38,0.08)' }}>
          <div>
            <p className="text-[10px] text-gray-500">Gossip</p>
            <p className="mt-0.5 text-xs font-semibold text-gray-200">{gossipStatus}</p>
          </div>
          <div>
            <p className="text-[10px] text-gray-500">Evictions/min</p>
            <p className="mt-0.5 text-xs font-semibold text-gray-200">{evictions != null ? evictions.toFixed(1) : '---'}</p>
          </div>
        </div>

        {/* Cache size */}
        {node.cacheSize != null && (
          <div className="flex items-center justify-between">
            <span className="text-gray-500 text-xs">Cache Entries</span>
            <span className="font-mono text-xs text-gray-300">{fmt(node.cacheSize)}</span>
          </div>
        )}
      </div>
    </div>
  )
}
