import axios from 'axios'

const api = axios.create({ baseURL: '/api/v1' })

export const fetchSnapshot     = ()           => api.get('/observe/snapshot').then((r) => r.data)
export const fetchSlo          = ()           => api.get('/observe/slo').then((r) => r.data)
export const fetchRing         = ()           => api.get('/observe/ring').then((r) => r.data)
export const fetchLatency      = ()           => api.get('/observe/latency').then((r) => r.data)
export const fetchSelfHealing  = ()           => api.get('/observe/self-healing').then((r) => r.data)
export const fetchAlerts       = ()           => api.get('/alerts/recent').then((r) => r.data)
export const sendChat          = (message)   => api.post('/agent/chat', { message }).then((r) => r.data)
export const fetchHealth       = ()           => api.get('/system/health').then((r) => r.data)

// Explain API (MCP Explain server proxied via agentic-ops)
const explainApi = axios.create({ baseURL: '/api/v1/explain' })

export const explainClusterHealth = ()         => explainApi.get('/cluster-health').then((r) => r.data)
export const explainNodeAnomaly   = (nodeId)   => explainApi.get(`/node/${nodeId}`).then((r) => r.data)
export const explainSlo           = ()         => explainApi.get('/slo').then((r) => r.data)
export const explainLatency       = (nodeId)   => explainApi.get('/latency', { params: nodeId ? { node_id: nodeId } : {} }).then((r) => r.data)
export const explainSelfHealing   = ()         => explainApi.get('/self-healing').then((r) => r.data)
