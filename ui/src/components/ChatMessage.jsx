import ReactMarkdown from 'react-markdown'

export default function ChatMessage({ role, text }) {
  const isUser = role === 'user'

  if (isUser) {
    return (
      <div className="flex justify-end animate-slide-in-right">
        <div
          className="max-w-lg rounded-2xl rounded-br-sm px-4 py-3 text-sm text-white"
          style={{
            background: 'linear-gradient(135deg, #7f1d1d, #991b1b)',
            border: '1px solid rgba(239,68,68,0.3)',
            boxShadow: '0 4px 16px rgba(220,38,38,0.2)',
          }}
        >
          {text}
        </div>
      </div>
    )
  }

  return (
    <div className="flex justify-start animate-slide-in-left">
      <div
        className="max-w-2xl rounded-2xl rounded-bl-sm px-4 py-3 text-sm"
        style={{
          background: 'linear-gradient(135deg, #0f0707, #0d0d0d)',
          border: '1px solid rgba(220,38,38,0.2)',
          boxShadow: '0 4px 16px rgba(0,0,0,0.4)',
        }}
      >
        <div className="mb-2 flex items-center gap-1.5">
          <span
            className="h-1.5 w-1.5 rounded-full"
            style={{ background: '#ef4444', boxShadow: '0 0 6px #ef4444' }}
          />
          <span className="text-[10px] font-bold uppercase tracking-widest text-red-400">
            EdgeFabric Agent
          </span>
        </div>
        <div className="ef-prose text-gray-200">
          <ReactMarkdown>{text}</ReactMarkdown>
        </div>
      </div>
    </div>
  )
}
