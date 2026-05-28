const config = {
  HEALTHY:     { dot: '#22c55e', bg: 'rgba(34,197,94,0.1)',  border: 'rgba(34,197,94,0.3)',  text: '#4ade80', pulse: true },
  SUSPECT:     { dot: '#f59e0b', bg: 'rgba(245,158,11,0.1)', border: 'rgba(245,158,11,0.3)', text: '#fbbf24', pulse: true },
  DEAD:        { dot: '#ef4444', bg: 'rgba(239,68,68,0.1)',  border: 'rgba(239,68,68,0.3)',  text: '#f87171', pulse: false },
  UNREACHABLE: { dot: '#6b7280', bg: 'rgba(107,114,128,0.1)',border: 'rgba(107,114,128,0.3)',text: '#9ca3af', pulse: false },
  UP:          { dot: '#22c55e', bg: 'rgba(34,197,94,0.1)',  border: 'rgba(34,197,94,0.3)',  text: '#4ade80', pulse: true },
  DOWN:        { dot: '#ef4444', bg: 'rgba(239,68,68,0.1)',  border: 'rgba(239,68,68,0.3)',  text: '#f87171', pulse: false },
  UNKNOWN:     { dot: '#6b7280', bg: 'rgba(107,114,128,0.1)',border: 'rgba(107,114,128,0.3)',text: '#9ca3af', pulse: false },
}

export default function StatusBadge({ status }) {
  const c = config[status] ?? config.UNKNOWN
  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold"
      style={{ background: c.bg, border: `1px solid ${c.border}`, color: c.text }}
    >
      <span
        className={`h-1.5 w-1.5 rounded-full flex-shrink-0 ${c.pulse ? 'animate-pulse-dot' : ''}`}
        style={{ background: c.dot, boxShadow: `0 0 6px ${c.dot}` }}
      />
      {status ?? 'UNKNOWN'}
    </span>
  )
}
