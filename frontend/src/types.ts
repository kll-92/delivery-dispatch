export type RiderStatus = 1 | 3

export type OrderStatus = 0 | 1 | 2 | 3 | 4

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
