const navigationItems = [
  {
    id: 'dashboard',
    label: 'Dashboard',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-5 w-5">
        <rect x="3" y="3" width="7" height="7" rx="1.5" />
        <rect x="14" y="3" width="7" height="7" rx="1.5" />
        <rect x="3" y="14" width="7" height="7" rx="1.5" />
        <rect x="14" y="14" width="7" height="7" rx="1.5" />
      </svg>
    ),
  },
  {
    id: 'chat',
    label: 'Agent Chat',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-5 w-5">
        <path d="M7 10h10" />
        <path d="M7 14h6" />
        <path d="M5 19l-2 2V6a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H5Z" />
      </svg>
    ),
  },
  {
    id: 'recommendations',
    label: 'Recommendations',
    badge: 'soon',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-5 w-5">
        <path d="M9 18h6" />
        <path d="M10 22h4" />
        <path d="M12 2a7 7 0 0 0-4 12.75c.64.44 1 1.13 1 1.9V17h6v-.35c0-.77.36-1.46 1-1.9A7 7 0 0 0 12 2Z" />
      </svg>
    ),
  },
  {
    id: 'approvals',
    label: 'Approvals',
    badge: 'soon',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-5 w-5">
        <path d="M9 12l2 2 4-4" />
        <circle cx="12" cy="12" r="9" />
      </svg>
    ),
  },
  {
    id: 'audit',
    label: 'Audit History',
    badge: 'soon',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-5 w-5">
        <path d="M12 7v5l3 3" />
        <circle cx="12" cy="12" r="9" />
      </svg>
    ),
  },
]

export default function Sidebar({ activePage, onNavigate }) {
  return (
    <aside
      className="relative flex w-64 flex-shrink-0 flex-col py-6 px-3"
      style={{
        background: 'linear-gradient(180deg, #0a0505 0%, #050505 100%)',
        borderRight: '1px solid #2a0f0f',
        boxShadow: '4px 0 30px rgba(220,38,38,0.06)',
      }}
    >
      {/* Red edge accent line */}
      <div
        className="absolute inset-y-0 left-0 w-px"
        style={{ background: 'linear-gradient(180deg, transparent, #dc2626 30%, #dc2626 70%, transparent)' }}
      />

      {/* Logo */}
      <div className="mb-8 px-3">
        <div className="flex items-center gap-3">
          <div
            className="relative flex h-10 w-10 items-center justify-center rounded-xl animate-float"
            style={{
              background: 'linear-gradient(135deg, #1a0505, #3b0a0a)',
              border: '1px solid rgba(220,38,38,0.4)',
              boxShadow: '0 0 20px rgba(220,38,38,0.2)',
            }}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="#ef4444" strokeWidth="2" className="h-5 w-5">
              <polygon points="12,2 22,8 22,16 12,22 2,16 2,8" />
              <polygon points="12,7 17,10 17,14 12,17 7,14 7,10" fill="rgba(220,38,38,0.2)" />
            </svg>
          </div>
          <div>
            <p className="text-[10px] font-bold uppercase tracking-[0.35em] text-red-500">EdgeFabric</p>
            <h1 className="text-base font-bold text-white leading-tight">Agentic Ops</h1>
          </div>
        </div>
        {/* Separator */}
        <div className="mt-5 h-px" style={{ background: 'linear-gradient(90deg, #dc2626, transparent)' }} />
      </div>

      {/* Nav */}
      <nav className="flex-1 space-y-1">
        {navigationItems.map((item) => {
          const isActive = item.id === activePage
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => onNavigate(item.id)}
              className="group relative flex w-full items-center justify-between rounded-lg px-3 py-2.5 text-left text-sm font-medium transition-all duration-200"
              style={
                isActive
                  ? {
                      background: 'linear-gradient(90deg, rgba(220,38,38,0.25), rgba(220,38,38,0.08))',
                      border: '1px solid rgba(220,38,38,0.35)',
                      color: '#f87171',
                      boxShadow: '0 0 12px rgba(220,38,38,0.15)',
                    }
                  : {
                      background: 'transparent',
                      border: '1px solid transparent',
                      color: '#9ca3af',
                    }
              }
            >
              {/* Active left bar */}
              {isActive && (
                <span
                  className="absolute left-0 top-1/4 h-1/2 w-0.5 rounded-r"
                  style={{ background: '#ef4444', boxShadow: '0 0 8px #ef4444' }}
                />
              )}

              <span className="flex items-center gap-3">
                <span
                  className="transition-colors duration-200"
                  style={{ color: isActive ? '#ef4444' : '#6b7280' }}
                >
                  {item.icon}
                </span>
                <span>{item.label}</span>
              </span>

              {item.badge && (
                <span
                  className="rounded px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-widest"
                  style={{
                    background: 'rgba(220,38,38,0.12)',
                    border: '1px solid rgba(220,38,38,0.25)',
                    color: '#f87171',
                  }}
                >
                  {item.badge}
                </span>
              )}
            </button>
          )
        })}
      </nav>

      {/* Bottom status card */}
      <div
        className="mt-4 rounded-xl p-4 text-xs"
        style={{
          background: 'rgba(220,38,38,0.04)',
          border: '1px solid rgba(220,38,38,0.15)',
        }}
      >
        <div className="mb-2 flex items-center gap-2">
          <span
            className="h-2 w-2 rounded-full animate-pulse-dot"
            style={{ background: '#22c55e', boxShadow: '0 0 6px #22c55e' }}
          />
          <span className="font-semibold text-white">Cluster Online</span>
        </div>
        <p className="leading-5 text-gray-500">
          Live data — auto-refreshes every 30 seconds. Ask the agent for insights.
        </p>
      </div>
    </aside>
  )
}
