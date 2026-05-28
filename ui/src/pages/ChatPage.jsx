import { useEffect, useRef, useState } from 'react'
import { sendChat } from '../api/agentApi'
import ChatMessage from '../components/ChatMessage'

const welcome = {
  role: 'assistant',
  text: 'Hello! I can answer questions about your EdgeFabric cluster using live data.\n\nTry asking:\n- "What\'s the current cluster health?"\n- "Are any nodes at risk of failure?"\n- "What is the cache hit rate across all nodes?"',
}

export default function ChatPage({ initialMessage, onMessageConsumed }) {
  const [messages, setMessages] = useState([welcome])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const bottomRef = useRef(null)
  const hasConsumedRef = useRef(false)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, loading])

  const doSend = async (text) => {
    if (!text.trim() || loading) return
    setMessages((prev) => [...prev, { role: 'user', text: text.trim() }])
    setLoading(true)
    try {
      const data = await sendChat(text.trim())
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', text: data.response ?? data.message ?? 'No response received.' },
      ])
    } catch {
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', text: 'Error: Could not reach the agent. Make sure agentic-ops is running on port 8090 and LLM_API_KEY is set.' },
      ])
    } finally {
      setLoading(false)
    }
  }

  // Auto-send initialMessage (from "Analyze Now" button on dashboard)
  useEffect(() => {
    if (initialMessage && !hasConsumedRef.current) {
      hasConsumedRef.current = true
      onMessageConsumed?.()
      doSend(initialMessage)
    }
  }, [initialMessage])

  const send = () => {
    doSend(input)
    setInput('')
  }

  const handleKey = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      send()
    }
  }

  return (
    <div className="flex h-full flex-col" style={{ background: '#050505' }}>
      {/* Header */}
      <div
        className="flex-shrink-0 px-6 py-4"
        style={{ borderBottom: '1px solid rgba(220,38,38,0.15)', background: 'rgba(13,5,5,0.8)' }}
      >
        <div className="flex items-center gap-3">
          <div
            className="flex h-9 w-9 items-center justify-center rounded-lg"
            style={{ background: 'rgba(220,38,38,0.1)', border: '1px solid rgba(220,38,38,0.3)' }}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="#ef4444" strokeWidth="2" className="h-5 w-5">
              <path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Z" />
              <path d="M12 8v4l3 3" />
            </svg>
          </div>
          <div>
            <h1 className="text-lg font-bold text-white">Agent Chat</h1>
            <p className="text-xs text-gray-500">Ask questions in plain English — reads live cluster data</p>
          </div>
        </div>
      </div>

      {/* Messages */}
      <div className="flex-1 space-y-4 overflow-y-auto p-6">
        {messages.map((msg, i) => (
          <ChatMessage key={`${msg.role}-${i}`} role={msg.role} text={msg.text} />
        ))}

        {loading && (
          <div className="flex justify-start animate-fade-in">
            <div
              className="flex items-center gap-2 rounded-2xl rounded-bl-sm px-5 py-4"
              style={{
                background: 'linear-gradient(135deg, #0f0707, #0d0d0d)',
                border: '1px solid rgba(220,38,38,0.2)',
              }}
            >
              {[0, 150, 300].map((delay) => (
                <span
                  key={delay}
                  className="h-2 w-2 rounded-full animate-bounce"
                  style={{
                    background: '#ef4444',
                    boxShadow: '0 0 6px #ef4444',
                    animationDelay: `${delay}ms`,
                  }}
                />
              ))}
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {/* Input */}
      <div
        className="flex-shrink-0 p-4"
        style={{ borderTop: '1px solid rgba(220,38,38,0.15)', background: 'rgba(13,5,5,0.8)' }}
      >
        <div className="flex gap-3">
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKey}
            placeholder="Ask about your cluster... (Enter to send)"
            rows={2}
            className="flex-1 resize-none rounded-xl px-4 py-3 text-sm text-gray-100 placeholder-gray-600 outline-none transition-all duration-200"
            style={{
              background: '#0d0d0d',
              border: '1px solid rgba(220,38,38,0.2)',
              boxShadow: 'none',
            }}
            onFocus={(e) => {
              e.target.style.border = '1px solid rgba(220,38,38,0.5)'
              e.target.style.boxShadow = '0 0 0 2px rgba(220,38,38,0.1)'
            }}
            onBlur={(e) => {
              e.target.style.border = '1px solid rgba(220,38,38,0.2)'
              e.target.style.boxShadow = 'none'
            }}
          />
          <button
            type="button"
            onClick={send}
            disabled={loading || !input.trim()}
            className="rounded-xl px-5 py-2 text-sm font-bold text-white transition-all duration-200 disabled:cursor-not-allowed disabled:opacity-40"
            style={{
              background: 'linear-gradient(135deg, #991b1b, #dc2626)',
              border: '1px solid rgba(239,68,68,0.4)',
              boxShadow: '0 0 12px rgba(220,38,38,0.25)',
            }}
            onMouseEnter={(e) => !e.currentTarget.disabled && (e.currentTarget.style.boxShadow = '0 0 20px rgba(220,38,38,0.5)')}
            onMouseLeave={(e) => (e.currentTarget.style.boxShadow = '0 0 12px rgba(220,38,38,0.25)')}
          >
            Send
          </button>
        </div>
        <p className="mt-2 text-[11px] text-gray-600">
          Powered by Claude AI &mdash; injects live cluster snapshot on every message
        </p>
      </div>
    </div>
  )
}
