import type { DeliveryOrder, Rider } from './types'

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })

  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const body = await response.json() as { message?: string }
      if (body.message) message = body.message
    } catch {
      // 保留通用错误提示
    }
    throw new Error(message)
  }

  return response.json() as Promise<T>
}

export const riderApi = {
  list: () => request<Rider[]>('/riders'),
  nearby: (longitude: number, latitude: number, radiusKm = 5, limit = 20) =>
    request<Rider[]>(`/riders/nearby?longitude=${longitude}&latitude=${latitude}&radiusKm=${radiusKm}&limit=${limit}`),
}

export const orderApi = {
  list: () => request<DeliveryOrder[]>('/orders'),
  dispatch: (orderId: number) => request<DeliveryOrder>(`/orders/${orderId}/dispatch`, { method: 'PATCH' }),
  assign: (orderId: number, riderId: number) =>
    request<DeliveryOrder>(`/orders/${orderId}/assign/${riderId}`, { method: 'PATCH' }),
  startDelivery: (orderId: number) =>
    request<DeliveryOrder>(`/orders/${orderId}/start-delivery`, { method: 'PATCH' }),
  complete: (orderId: number) => request<DeliveryOrder>(`/orders/${orderId}/complete`, { method: 'PATCH' }),
  cancel: (orderId: number) => request<DeliveryOrder>(`/orders/${orderId}/cancel`, { method: 'PATCH' }),
}
