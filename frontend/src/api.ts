import type {
  Account,
  CreateOrderRequest,
  DashboardSummary,
  DeliveryOrder,
  NearbyRider,
  OperationLog,
  PageResult,
  Rider,
  RiderRequest,
  SessionUser,
} from './types'

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

export class ApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

let csrfToken: { token: string; headerName: string } | null = null
let csrfRequest: Promise<{ token: string; headerName: string }> | null = null
let csrfGeneration = 0

function clearCsrfToken() {
  csrfGeneration += 1
  csrfToken = null
  csrfRequest = null
}

async function fetchCsrfToken() {
  if (csrfToken) return csrfToken
  const generation = csrfGeneration
  const pending = csrfRequest ??= (async () => {
    const response = await fetch(`${API_BASE}/auth/csrf`, { credentials: 'include' })
    if (!response.ok) throw new ApiError(`无法获取安全令牌（${response.status}）`, response.status)
    const body = await response.json() as { token: string; headerName: string }
    const token = { token: body.token, headerName: body.headerName }
    if (generation === csrfGeneration) csrfToken = token
    return token
  })()
  try {
    const token = await pending
    if (generation !== csrfGeneration) throw new ApiError('登录状态已变化，请重试当前操作', 401)
    return token
  } finally {
    if (csrfRequest === pending) csrfRequest = null
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') {
    const csrf = await fetchCsrfToken()
    headers.set(csrf.headerName, csrf.token)
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    method,
    headers,
    credentials: 'include',
  })

  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const body = await response.json() as { message?: string }
      if (body.message) message = body.message
    } catch {
      // 保留通用错误提示
    }
    if (response.status === 401 || response.status === 403) clearCsrfToken()
    throw new ApiError(message, response.status)
  }

  if (response.status === 204) return undefined as T
  if (response.headers.get('content-length') === '0') return undefined as T
  return response.json() as Promise<T>
}

export const authApi = {
  me: () => request<SessionUser>('/auth/me'),
  login: async (username: string, password: string) => {
    const user = await request<SessionUser>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    })
    clearCsrfToken()
    return user
  },
  logout: async () => {
    try {
      await request<void>('/auth/logout', { method: 'POST' })
    } finally {
      clearCsrfToken()
    }
  },
}

export const dashboardApi = {
  summary: () => request<DashboardSummary>('/dashboard/summary'),
}

export const riderApi = {
  list: () => request<Rider[]>('/riders'),
  page: (params: { page: number; size: number; status?: number | ''; keyword?: string }) => {
    const query = new URLSearchParams({ page: String(params.page), size: String(params.size) })
    if (params.status !== undefined && params.status !== '') query.set('status', String(params.status))
    if (params.keyword?.trim()) query.set('keyword', params.keyword.trim())
    return request<PageResult<Rider>>(`/riders/page?${query}`)
  },
  nearby: (longitude: number, latitude: number, radiusKm = 5, limit = 20) =>
    request<NearbyRider[]>(`/riders/nearby?longitude=${longitude}&latitude=${latitude}&radiusKm=${radiusKm}&limit=${limit}`),
  create: (rider: RiderRequest) => request<Rider>('/riders', { method: 'POST', body: JSON.stringify(rider) }),
  update: (id: number, rider: RiderRequest) => request<Rider>(`/riders/${id}`, { method: 'PUT', body: JSON.stringify(rider) }),
  updateLocation: (id: number, longitude: number, latitude: number) =>
    request<Rider>(`/riders/${id}/location`, { method: 'PUT', body: JSON.stringify({ longitude, latitude }) }),
  setEnabled: (id: number, enabled: boolean) =>
    request<Rider>(`/riders/${id}/${enabled ? 'enable' : 'disable'}`, { method: 'PATCH' }),
}

export const orderApi = {
  list: () => request<DeliveryOrder[]>('/orders'),
  page: (params: { page: number; size: number; status?: number | ''; orderNo?: string; createdFrom?: string; createdTo?: string }) => {
    const query = new URLSearchParams({ page: String(params.page), size: String(params.size) })
    if (params.status !== undefined && params.status !== '') query.set('status', String(params.status))
    if (params.orderNo?.trim()) query.set('orderNo', params.orderNo.trim())
    if (params.createdFrom) query.set('createdFrom', params.createdFrom)
    if (params.createdTo) query.set('createdTo', params.createdTo)
    return request<PageResult<DeliveryOrder>>(`/orders/page?${query}`)
  },
  create: (order: CreateOrderRequest) => request<DeliveryOrder>('/orders', { method: 'POST', body: JSON.stringify(order) }),
  dispatch: (orderId: number) => request<DeliveryOrder>(`/orders/${orderId}/dispatch`, { method: 'PATCH' }),
  assign: (orderId: number, riderId: number) => request<DeliveryOrder>(`/orders/${orderId}/assign/${riderId}`, { method: 'PATCH' }),
  startDelivery: (orderId: number) => request<DeliveryOrder>(`/orders/${orderId}/start-delivery`, { method: 'PATCH' }),
  complete: (orderId: number) => request<DeliveryOrder>(`/orders/${orderId}/complete`, { method: 'PATCH' }),
  cancel: (orderId: number) => request<DeliveryOrder>(`/orders/${orderId}/cancel`, { method: 'PATCH' }),
  updateFee: (orderId: number, deliveryFee: number) => request<DeliveryOrder>(`/orders/${orderId}/fee`, { method: 'PATCH', body: JSON.stringify({ deliveryFee }) }),
  remove: (orderId: number, reason: string) => request<void>(`/orders/${orderId}?reason=${encodeURIComponent(reason)}`, { method: 'DELETE' }),
}

export const accountApi = {
  list: () => request<Account[]>('/accounts'),
  create: (username: string, password: string, role: SessionUser['role']) =>
    request<Account>('/accounts', { method: 'POST', body: JSON.stringify({ username, password, role }) }),
  setEnabled: (id: number, enabled: boolean) =>
    request<Account>(`/accounts/${id}/enabled`, { method: 'PATCH', body: JSON.stringify({ enabled }) }),
}

export const operationLogApi = {
  page: (params: { page: number; size: number; targetType?: string; targetId?: string; actor?: string; action?: string }) => {
    const query = new URLSearchParams({ page: String(params.page), size: String(params.size) })
    for (const key of ['targetType', 'targetId', 'actor', 'action'] as const) {
      const value = params[key]?.trim()
      if (value) query.set(key, value)
    }
    return request<PageResult<OperationLog>>(`/operation-logs/page?${query}`)
  },
}
