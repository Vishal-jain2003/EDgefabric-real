import { useState } from 'react'
import Sidebar from './components/Sidebar'
import DashboardPage from './pages/DashboardPage'
import ChatPage from './pages/ChatPage'
import RecommendationsPage from './pages/RecommendationsPage'
import ApprovalsPage from './pages/ApprovalsPage'
import AuditPage from './pages/AuditPage'

export default function App() {
  const [activePage, setActivePage] = useState('dashboard')
  const [chatInitialMessage, setChatInitialMessage] = useState(null)

  const navigateToChat = (message) => {
    setChatInitialMessage(message)
    setActivePage('chat')
  }

  const renderPage = () => {
    switch (activePage) {
      case 'dashboard':
        return <DashboardPage onAnalyze={navigateToChat} />
      case 'chat':
        return (
          <ChatPage
            initialMessage={chatInitialMessage}
            onMessageConsumed={() => setChatInitialMessage(null)}
          />
        )
      case 'recommendations':
        return <RecommendationsPage />
      case 'approvals':
        return <ApprovalsPage />
      case 'audit':
        return <AuditPage />
      default:
        return <DashboardPage onAnalyze={navigateToChat} />
    }
  }

  return (
    <div className="flex h-screen overflow-hidden" style={{ background: '#050505' }}>
      <Sidebar activePage={activePage} onNavigate={setActivePage} />
      <main className="flex-1 overflow-hidden">
        {renderPage()}
      </main>
    </div>
  )
}
