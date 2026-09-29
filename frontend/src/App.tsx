import { useCallback, useEffect, useMemo, useState } from 'react'
import { orderApi, riderApi } from './api'
import { demoOrders, demoRiders } from './mockData'
import type { DeliveryOrder, OrderStatus, Rider } from './types'

type Tab = 'overview' | 'riders' | 'orders'
type Variant = 'A' | 'B' | 'C'

const ORDER_STATUS: Record<OrderStatus, { label: string; tone: string }> = {
  0: { label: '待派单', tone: 'warning' },
  1: { label: '已派单', tone: 'info' },
  2: { label: '配送中', tone: 'accent' },
  3: { label: '已完成', tone: 'success' },
  4: { label: '已取消', tone: 'muted' },
}

const VARIANTS: Record<Variant, string> = {
  A: 'Command Center',
  B: 'Operations Board',
  C: 'Dispatch Desk',
}

function getVariant(): Variant {
  const value = new URLSearchParams(window.location.search).get('variant')
  return value === 'B' || value === 'C' ? value : 'A'
}

function formatTime(value?: string | null) {
  if (!value) return '刚刚'
  return value.replace('T', ' ').slice(0, 16)
}

function StatusPill({ status }: { status: OrderStatus }) {
  const detail = ORDER_STATUS[status]
  return <span className={`pill pill-${detail.tone}`}><i />{detail.label}</span>
}

function Metric({ label, value, detail, icon, tone }: {
  label: string
  value: string | number
  detail: string
  icon: string
  tone: string
}) {
  return (
    <div className="metric-card">
      <div className={`metric-icon ${tone}`}>{icon}</div>
      <div>
        <p>{label}</p>
        <strong>{value}</strong>
        <small>{detail}</small>
      </div>
    </div>
  )
}

function Brand() {
  return (
    <div className="brand">
      <div className="brand-mark"><span /><span /><span /></div>
      <div>
        <strong>Dispatch<span>OS</span></strong>
        <small>配送调度中心</small>
      </div>
    </div>
  )
}

function App() {
  const [variant, setVariant] = useState<Variant>(getVariant)
  const [tab, setTab] = useState<Tab>('overview')
  const [riders, setRiders] = useState<Rider[]>([])
  const [orders, setOrders] = useState<DeliveryOrder[]>([])
  const [loading, setLoading] = useState(true)
  const [source, setSource] = useState<'api' | 'demo'>('api')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busyOrderId, setBusyOrderId] = useState<number | null>(null)
  const [assignment, setAssignment] = useState<Record<number, string>>({})

  const loadData = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [nextRiders, nextOrders] = await Promise.all([riderApi.list(), orderApi.list()])
      setRiders(nextRiders)
      setOrders(nextOrders)
      setSource('api')
    } catch (requestError) {
      setRiders(demoRiders)
      setOrders(demoOrders)
      setSource('demo')
      setError(requestError instanceof Error ? requestError.message : '后端暂不可用')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void loadData() }, [loadData])

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target?.matches('input, textarea, select, [contenteditable="true"]')) return
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
      event.preventDefault()
      const options: Variant[] = ['A', 'B', 'C']
      const currentIndex = options.indexOf(variant)
      const nextIndex = event.key === 'ArrowRight'
        ? (currentIndex + 1) % options.length
        : (currentIndex + options.length - 1) % options.length
      switchVariant(options[nextIndex])
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  })

  function switchVariant(next: Variant) {
    setVariant(next)
    const url = new URL(window.location.href)
    url.searchParams.set('variant', next)
    window.history.replaceState({}, '', url)
  }

  function replaceOrder(updated: DeliveryOrder) {
    setOrders(current => current.map(order => order.id === updated.id ? { ...order, ...updated } : order))
  }

  function updateDemoOrder(orderId: number, update: Partial<DeliveryOrder>) {
    setOrders(current => current.map(order => order.id === orderId ? { ...order, ...update } : order))
  }

  async function runOrderAction(
    orderId: number,
    action: () => Promise<DeliveryOrder>,
    fallback: Partial<DeliveryOrder>,
  ) {
    setBusyOrderId(orderId)
    setNotice('')
    try {
      replaceOrder(await action())
      setNotice('操作成功，订单状态已刷新')
    } catch (actionError) {
      updateDemoOrder(orderId, fallback)
      setSource('demo')
      setNotice(actionError instanceof Error
        ? `${actionError.message} · 当前已切换为演示模式`
        : '后端暂不可用，当前已切换为演示模式')
    } finally {
      setBusyOrderId(null)
    }
  }

  function handleDispatch(order: DeliveryOrder) {
    const available = riders.find(rider => rider.status === 1)
    void runOrderAction(order.id, () => orderApi.dispatch(order.id), {
      status: 1,
      riderId: available?.id ?? null,
    })
  }

  function handleAssign(order: DeliveryOrder) {
    const riderId = Number(assignment[order.id])
    if (!riderId) {
      setNotice('请先选择一名可用骑手')
      return
    }
    void runOrderAction(order.id, () => orderApi.assign(order.id, riderId), { status: 1, riderId })
  }

  function handleTransition(order: DeliveryOrder, next: 'start' | 'complete' | 'cancel') {
    const action = next === 'start'
      ? () => orderApi.startDelivery(order.id)
      : next === 'complete'
        ? () => orderApi.complete(order.id)
        : () => orderApi.cancel(order.id)
    const status: OrderStatus = next === 'start' ? 2 : next === 'complete' ? 3 : 4
    void runOrderAction(order.id, action, { status })
  }

  const availableRiders = useMemo(() => riders.filter(rider => rider.status === 1), [riders])
  const activeOrders = useMemo(() => orders.filter(order => order.status === 1 || order.status === 2), [orders])
  const pendingOrders = useMemo(() => orders.filter(order => order.status === 0), [orders])
  const completedOrders = useMemo(() => orders.filter(order => order.status === 3), [orders])

  const nav = (
    <nav className="nav-list">
      <button className={tab === 'overview' ? 'nav-item active' : 'nav-item'} onClick={() => setTab('overview')}>
        <span>◈</span>总览 <em>01</em>
      </button>
      <button className={tab === 'riders' ? 'nav-item active' : 'nav-item'} onClick={() => setTab('riders')}>
        <span>♙</span>骑手中心 <em>{riders.length.toString().padStart(2, '0')}</em>
      </button>
      <button className={tab === 'orders' ? 'nav-item active' : 'nav-item'} onClick={() => setTab('orders')}>
        <span>▤</span>订单调度 <em>{orders.length.toString().padStart(2, '0')}</em>
      </button>
    </nav>
  )

  const header = (
    <header className="topbar">
      <div className="topbar-heading">
        <div className="eyebrow">LIVE OPERATIONS / 09.29.2026</div>
        <h1>{tab === 'overview' ? '今天的配送，一目了然。' : tab === 'riders' ? '骑手资源中心' : '订单调度台'}</h1>
      </div>
      <div className="topbar-actions">
        <span className={`connection ${source}`}><i />{source === 'api' ? 'API 已连接' : '演示数据'}</span>
        <button className="icon-button" onClick={() => void loadData()} title="刷新数据">↻</button>
        <div className="operator"><span>KL</span><div><strong>运营值班</strong><small>在线</small></div></div>
      </div>
    </header>
  )

  function renderOverview() {
    const distribution = [0, 1, 2, 3, 4].map(status => ({
      ...ORDER_STATUS[status as OrderStatus],
      count: orders.filter(order => order.status === status).length,
    }))
    return (
      <>
        <section className="hero-strip">
          <div>
            <span className="hero-kicker">TODAY'S ROUTE PULSE</span>
            <h2>让每一单，都找到<br /><i>最合适的路。</i></h2>
            <p>实时追踪骑手资源和订单状态，把调度从“等待”变成“确定”。</p>
          </div>
          <div className="route-orbit" aria-hidden="true"><span className="orbit-line" /><b>R</b><i>●</i><em>配送中</em></div>
        </section>
        <section className="metric-grid">
          <Metric label="今日订单" value={orders.length} detail={`${pendingOrders.length} 单等待派送`} icon="↗" tone="orange" />
          <Metric label="在线骑手" value={availableRiders.length} detail={`${riders.length} 名登记骑手`} icon="♙" tone="mint" />
          <Metric label="配送中" value={orders.filter(order => order.status === 2).length} detail="实时配送任务" icon="◎" tone="blue" />
          <Metric label="已完成" value={completedOrders.length} detail="今日已闭环订单" icon="✓" tone="purple" />
        </section>
        <section className="content-grid overview-grid">
          <div className="panel flow-panel">
            <div className="panel-heading"><div><span className="section-label">ORDER FLOW</span><h3>订单流转</h3></div><span className="live-dot">● LIVE</span></div>
            <div className="flow-list">
              {distribution.map((item, index) => (
                <div className="flow-row" key={item.label}>
                  <div className={`flow-number tone-${item.tone}`}>{String(item.count).padStart(2, '0')}</div>
                  <div className="flow-copy"><strong>{item.label}</strong><span>{index === 0 ? '等待系统匹配骑手' : index === 1 ? '已锁定配送资源' : index === 2 ? '骑手正在路上' : index === 3 ? '成功送达收货人' : '已终止配送流程'}</span></div>
                  <div className="flow-bar"><i style={{ width: `${orders.length ? Math.max(8, (item.count / orders.length) * 100) : 8}%` }} /></div>
                </div>
              ))}
            </div>
          </div>
          <div className="panel rider-panel">
            <div className="panel-heading"><div><span className="section-label">RIDER RADAR</span><h3>骑手资源</h3></div><button className="text-button" onClick={() => setTab('riders')}>查看全部 →</button></div>
            <div className="rider-stack">
              {riders.slice(0, 3).map(rider => (
                <div className="rider-line" key={rider.id}><div className="avatar">{rider.name.slice(0, 1)}</div><div className="rider-line-copy"><strong>{rider.name}<small>{rider.riderNo}</small></strong><span>{rider.currentOrderCount} 个进行中订单</span></div><span className={`availability ${rider.status === 1 ? 'available' : 'disabled'}`}><i />{rider.status === 1 ? '可接单' : '已停用'}</span></div>
              ))}
              {!riders.length && <div className="empty-state">暂无骑手数据</div>}
            </div>
          </div>
        </section>
        <section className="panel recent-panel">
          <div className="panel-heading"><div><span className="section-label">RECENT DISPATCHES</span><h3>最近订单</h3></div><button className="text-button" onClick={() => setTab('orders')}>进入调度台 →</button></div>
          <OrderTable orders={orders.slice(0, 4)} riders={riders} compact />
        </section>
      </>
    )
  }

  function renderRiders() {
    return (
      <section className="panel page-panel">
        <div className="panel-heading"><div><span className="section-label">RIDER DIRECTORY</span><h3>全部骑手</h3></div><span className="count-badge">{riders.length} RIDERS</span></div>
        <div className="table-wrap"><table><thead><tr><th>骑手</th><th>状态</th><th>当前位置</th><th>进行中订单</th><th>最后同步</th></tr></thead><tbody>{riders.map(rider => <tr key={rider.id}><td><div className="table-person"><div className="avatar">{rider.name.slice(0, 1)}</div><div><strong>{rider.name}</strong><small>{rider.riderNo} · {rider.phone}</small></div></div></td><td><span className={`availability ${rider.status === 1 ? 'available' : 'disabled'}`}><i />{rider.status === 1 ? '可接单' : '已停用'}</span></td><td><span className="coordinates">{Number(rider.longitude).toFixed(4)}, {Number(rider.latitude).toFixed(4)}</span></td><td><strong className="order-count">{rider.currentOrderCount}</strong></td><td className="muted-text">{formatTime(rider.updatedAt)}</td></tr>)}</tbody></table>{!riders.length && <div className="empty-state">暂无骑手数据</div>}</div>
      </section>
    )
  }

  function renderOrders() {
    return (
      <section className="panel page-panel">
        <div className="panel-heading"><div><span className="section-label">DISPATCH QUEUE</span><h3>订单调度</h3></div><span className="count-badge">{pendingOrders.length} PENDING</span></div>
        <div className="order-cards">{orders.map(order => <OrderCard key={order.id} order={order} />)}{!orders.length && <div className="empty-state">暂无订单数据</div>}</div>
      </section>
    )
  }

  function OrderTable({ orders: rows, riders: rowRiders, compact = false }: { orders: DeliveryOrder[]; riders: Rider[]; compact?: boolean }) {
    return <div className={`table-wrap ${compact ? 'compact-table' : ''}`}><table><thead><tr><th>订单</th><th>商家 → 收货人</th><th>骑手</th><th>状态</th><th>配送费</th></tr></thead><tbody>{rows.map(order => <tr key={order.id}><td><strong className="order-no">{order.orderNo}</strong><small className="muted-text">#{order.id}</small></td><td><div className="route-cell"><strong>{order.merchantName}</strong><span>→ {order.receiverName}</span></div><small className="muted-text">{order.receiverAddress}</small></td><td>{rowRiders.find(rider => rider.id === order.riderId)?.name ?? <span className="muted-text">未分配</span>}</td><td><StatusPill status={order.status} /></td><td className="fee">¥{Number(order.deliveryFee ?? 0).toFixed(2)}</td></tr>)}</tbody></table>{!rows.length && <div className="empty-state">暂无订单数据</div>}</div>
  }

  function OrderCard({ order }: { order: DeliveryOrder }) {
    const assignedRider = riders.find(rider => rider.id === order.riderId)
    const selectedRider = assignment[order.id] ?? ''
    const busy = busyOrderId === order.id
    return <article className="order-card"><div className="order-card-main"><div className="order-top"><div><strong className="order-no">{order.orderNo}</strong><span className="muted-text"> · {formatTime(order.createdAt)}</span></div><StatusPill status={order.status} /></div><div className="route-block"><div className="route-point pickup"><i>●</i><div><small>取货 / {order.merchantName}</small><strong>{order.merchantAddress}</strong></div></div><div className="route-dash" /><div className="route-point dropoff"><i>●</i><div><small>收货 / {order.receiverName}</small><strong>{order.receiverAddress}</strong></div></div></div></div><div className="order-card-side"><div className="order-meta"><span>配送费</span><strong>¥{Number(order.deliveryFee ?? 0).toFixed(2)}</strong></div><div className="order-actions">{order.status === 0 && <><button className="primary-button" disabled={busy} onClick={() => handleDispatch(order)}>{busy ? '处理中…' : '自动派单'}</button><div className="assign-row"><select value={selectedRider} onChange={event => setAssignment(current => ({ ...current, [order.id]: event.target.value }))}><option value="">指定骑手</option>{availableRiders.map(rider => <option key={rider.id} value={rider.id}>{rider.name} · {rider.currentOrderCount}单</option>)}</select><button className="secondary-button" disabled={busy || !selectedRider} onClick={() => handleAssign(order)}>指定</button></div></>}{order.status === 1 && <button className="primary-button" disabled={busy} onClick={() => handleTransition(order, 'start')}>{busy ? '处理中…' : '开始配送'}</button>}{order.status === 2 && <button className="primary-button" disabled={busy} onClick={() => handleTransition(order, 'complete')}>{busy ? '处理中…' : '标记完成'}</button>}{(order.status === 0 || order.status === 1 || order.status === 2) && <button className="ghost-danger" disabled={busy} onClick={() => handleTransition(order, 'cancel')}>取消订单</button>}</div>{assignedRider && <div className="assigned-rider"><span className="avatar tiny">{assignedRider.name.slice(0, 1)}</span><span>当前骑手：<strong>{assignedRider.name}</strong></span></div>}</div></article>
  }

  const content = tab === 'overview' ? renderOverview() : tab === 'riders' ? renderRiders() : renderOrders()

  return (
    <div className={`app app-${variant.toLowerCase()}`}>
      <div className="grain" />
      {variant === 'A' && <aside className="sidebar"><Brand /><div className="sidebar-label">WORKSPACE</div>{nav}<div className="sidebar-footer"><div className="status-card"><span className="pulse" /><div><strong>调度服务正常</strong><small>Redis · RocketMQ 在线</small></div></div><small>DispatchOS v0.1 · Prototype</small></div></aside>}
      <div className="main-shell">
        {variant !== 'A' && <div className="top-brand"><Brand /><div className="variant-caption">{variant === 'B' ? 'OPERATIONS BOARD' : 'FIELD DISPATCH / PROTOTYPE'}</div>{nav}</div>}
        {header}
        {error && <div className="notice notice-warning"><span>!</span>后端接口暂不可用，已载入演示数据：{error}</div>}
        {notice && <div className="notice notice-info"><span>✓</span>{notice}</div>}
        <main className="workspace">{loading ? <div className="loading-state"><span className="loader" /><p>正在连接调度服务…</p></div> : content}</main>
      </div>
      {import.meta.env.DEV && <div className="prototype-switcher"><button onClick={() => switchVariant(variant === 'A' ? 'C' : variant === 'B' ? 'A' : 'B')} aria-label="上一个布局">←</button><span><small>LAYOUT PROTOTYPE</small><strong>{variant} · {VARIANTS[variant]}</strong></span><button onClick={() => switchVariant(variant === 'A' ? 'B' : variant === 'B' ? 'C' : 'A')} aria-label="下一个布局">→</button></div>}
    </div>
  )
}

export default App
