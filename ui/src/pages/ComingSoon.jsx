function ComingSoon({ title, description, jiraRef }) {
  return (
    <div className="flex h-full flex-col p-6">
      <div className="mb-6 animate-fade-in">
        <h1 className="text-2xl font-bold text-white">{title}</h1>
        <p className="mt-1 text-sm text-gray-500">{description}</p>
      </div>
      <div
        className="flex flex-1 flex-col items-center justify-center rounded-2xl text-center animate-fade-in-up"
        style={{ border: '1px dashed rgba(220,38,38,0.25)', background: 'rgba(220,38,38,0.02)' }}
      >
        <div
          className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl"
          style={{ background: 'rgba(220,38,38,0.08)', border: '1px solid rgba(220,38,38,0.2)' }}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="#ef4444" strokeWidth="1.8" className="h-8 w-8">
            <circle cx="12" cy="12" r="10" />
            <polyline points="12 6 12 12 16 14" />
          </svg>
        </div>
        <p className="text-lg font-bold text-gray-300">Coming Soon</p>
        {jiraRef && (
          <p className="mt-2 text-xs text-gray-600">Waiting on {jiraRef}</p>
        )}
      </div>
    </div>
  )
}

export default ComingSoon
