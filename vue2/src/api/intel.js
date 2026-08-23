import request from '@/utils/request'

const SCOPE_KEY = 'market-intel-scope-v1'

export function getIntelScope() {
  try {
    const value = JSON.parse(localStorage.getItem(SCOPE_KEY) || '{}')
    return { provider: value.provider || 'xiaohongshu', accountId: value.accountId || 'default' }
  } catch (_) {
    return { provider: 'xiaohongshu', accountId: 'default' }
  }
}

export function setIntelScope(scope) {
  const value = { provider: scope.provider || 'xiaohongshu', accountId: scope.accountId || 'default' }
  localStorage.setItem(SCOPE_KEY, JSON.stringify(value))
  return value
}

const queryScope = params => ({ ...getIntelScope(), ...(params || {}) })
const bodyScope = data => {
  const scope = getIntelScope()
  return { provider: scope.provider, account_id: scope.accountId, ...(data || {}) }
}

export const getRank = params => request({ url: '/api/intel/rank', method: 'get', params: queryScope(params) })
export const getTrend = params => request({ url: '/api/intel/trend', method: 'get', params: queryScope(params) })
export const getCompetitors = params => request({ url: '/api/intel/competitor', method: 'get', params: queryScope(params) })
export const getCompetitorNotes = (id, params) => request({ url: `/api/intel/competitor/${id}/notes`, method: 'get', params: queryScope(params) })
export const addCompetitor = (data, params) => request({ url: '/api/intel/competitor', method: 'post', data: bodyScope(data), params: queryScope(params) })
export const previewCompetitor = data => request({ url: '/api/intel/competitor/preview', method: 'post', data: bodyScope(data) })
export const deleteCompetitor = (id, params) => request({ url: `/api/intel/competitor/${id}`, method: 'delete', params: queryScope(params) })
export const getAlerts = params => request({ url: '/api/intel/alert', method: 'get', params: queryScope(params) })
export const readAlert = (id, params) => request({ url: `/api/intel/alert/${id}/read`, method: 'put', params: queryScope(params) })
export const getKeywords = params => request({ url: '/api/intel/keyword', method: 'get', params: queryScope(params) })
export const addKeyword = (data, params) => request({ url: '/api/intel/keyword', method: 'post', data, params: queryScope(params) })
export const updateKeyword = (id, data, params) => request({ url: `/api/intel/keyword/${id}`, method: 'put', data, params: queryScope(params) })
export const deleteKeyword = (id, params) => request({ url: `/api/intel/keyword/${id}`, method: 'delete', params: queryScope(params) })
export const getIntelStatus = params => request({ url: '/api/intel/status', method: 'get', params: queryScope(params) })
export const runIntel = data => request({ url: '/api/intel/run', method: 'post', data: bodyScope(data) })
export const startIntelQrLogin = params => request({ url: '/api/intel/auth/qrcode', method: 'post', params: queryScope(params) })
export const getIntelQrStatus = (sessionId, params) => request({ url: '/api/intel/auth/qrcode/status', method: 'get', params: { ...queryScope(params), sessionId } })
export const logoutIntel = params => request({ url: '/api/intel/auth', method: 'delete', params: queryScope(params) })
