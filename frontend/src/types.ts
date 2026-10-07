export type RiderStatus = 1 | 3

export type OrderStatus = 0 | 1 | 2 | 3 | 4
export type AccountRole = 'ADMIN' | 'DISPATCHER'

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export interface DashboardSummary {
  totalOrders: number
  pendingOrders: number
  assignedOrders: number
  deliveringOrders: number
  completedOrders: number
  canceledOrders: number
  totalRiders: number
  availableRiders: number
}

export interface SessionUser {
  username: string
  role: AccountRole
}

export interface Account {
  id: number
  username: string
  role: AccountRole
  enabled: boolean
  createdAt?: string | null
  updatedAt?: string | null
}

export interface OperationLog {
  id: number
  action: string
  targetType: string
  targetId: number | string
  actor: string
  beforeJson?: string | null
  afterJson?: string | null
  reason?: string | null
  createdAt?: string | null
}

export interface Rider {
  id: number
  riderNo: string
  name: string
  phone: string
  status: number
  longitude: number
  latitude: number
  currentOrderCount: number
  createdAt?: string | null
  updatedAt?: string | null
}

export interface RiderRequest {
  riderNo?: string
  name: string
  phone: string
  longitude?: number
  latitude?: number
}

export interface NearbyRider {
  riderId: number
  riderNo: string
  name: string
  distanceKm: number
  currentOrderCount: number
}

export interface DeliveryOrder {
  id: number
  orderNo: string
  merchantName: string
  merchantPhone?: string
  merchantAddress: string
  merchantLongitude?: number
  merchantLatitude?: number
  receiverName: string
  receiverPhone?: string
  receiverAddress: string
  receiverLongitude?: number
  receiverLatitude?: number
  riderId?: number | null
  status: OrderStatus
  deliveryFee: number
  createdAt?: string | null
  updatedAt?: string | null
}

export interface CreateOrderRequest {
  merchantName: string
  merchantPhone: string
  merchantAddress: string
  merchantLongitude: number
  merchantLatitude: number
  receiverName: string
  receiverPhone: string
  receiverAddress: string
  receiverLongitude: number
  receiverLatitude: number
  deliveryFee: number
}
