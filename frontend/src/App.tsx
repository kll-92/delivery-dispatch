import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { accountApi, ApiError, authApi, dashboardApi, operationLogApi, orderApi, riderApi } from './api'
import { demoOrders, demoRiders } from './mockData'
import type { Account, AccountRole, CreateOrderRequest, DashboardSummary, DeliveryOrder, OperationLog, OrderStatus, PageResult, Rider, RiderRequest, SessionUser } from './types'

type Tab = 'overview' | 'riders' | 'orders' | 'accounts' | 'logs'
type Variant = 'A' | 'B' | 'C'

const PAGE_SIZE = 8
const RIDER_PAGE_SIZE = 10

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

function formatTime(value?: string | null, assumeNaiveUtc = true) {
  if (!value) return '刚刚'
  const hasZone = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(value)
  const date = new Date(hasZone || !assumeNaiveUtc ? value : `${value}Z`)
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ').slice(0, 16)
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function localDateTimeToUtcLocal(value: string) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toISOString().slice(0, 19)
}

function formatSnapshot(value?: string | null) {
  if (!value) return '无快照'
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
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

function LoginDialog({ submitting, error, onClose, onSubmit }: {
  submitting: boolean
  error: string
  onClose: () => void
  onSubmit: (username: string, password: string) => void
}) {
  return (
    <div className="dialog-backdrop" onMouseDown={event => { if (event.target === event.currentTarget && !submitting) onClose() }}>
      <section className="create-order-dialog login-dialog" role="dialog" aria-modal="true" aria-labelledby="login-title">
        <div className="dialog-heading"><div><span className="section-label">SECURE SESSION</span><h2 id="login-title">运营人员登录</h2><p>登录后连接受权限保护的真实业务数据。</p></div><button className="dialog-close" type="button" onClick={onClose} disabled={submitting} aria-label="关闭">×</button></div>
        {error && <div className="notice notice-warning" role="alert">{error}</div>}
        <form className="create-order-form" onSubmit={event => { event.preventDefault(); const form = new FormData(event.currentTarget); onSubmit(String(form.get('username') ?? '').trim(), String(form.get('password') ?? '')) }}>
          <label>用户名<input name="username" required autoComplete="username" maxLength={100} /></label>
          <label>密码<input name="password" type="password" required autoComplete="current-password" /></label>
          <div className="dialog-actions"><button className="secondary-button" type="button" disabled={submitting} onClick={onClose}>继续访客演示</button><button className="primary-button" type="submit" disabled={submitting}>{submitting ? '正在登录…' : '登录'}</button></div>
        </form>
      </section>
    </div>
  )
}

function RiderDialog({ rider, submitting, onClose, onSubmit }: {
  rider: Rider | null
  submitting: boolean
  onClose: () => void
  onSubmit: (request: RiderRequest) => void
}) {
  return (
    <div className="dialog-backdrop" onMouseDown={event => { if (event.target === event.currentTarget && !submitting) onClose() }}>
      <section className="create-order-dialog" role="dialog" aria-modal="true" aria-labelledby="rider-dialog-title">
        <div className="dialog-heading"><div><span className="section-label">RIDER PROFILE</span><h2 id="rider-dialog-title">{rider ? '编辑骑手' : '新增骑手'}</h2><p>经纬度可留空；填写时必须同时填写一对坐标。</p></div><button className="dialog-close" type="button" onClick={onClose} disabled={submitting} aria-label="关闭">×</button></div>
        <form className="create-order-form" onSubmit={event => {
          event.preventDefault()
          const form = new FormData(event.currentTarget)
          const longitude = String(form.get('longitude') ?? '').trim()
          const latitude = String(form.get('latitude') ?? '').trim()
          onSubmit({
            ...(rider ? {} : { riderNo: String(form.get('riderNo') ?? '').trim() }),
            name: String(form.get('name') ?? '').trim(),
            phone: String(form.get('phone') ?? '').trim(),
            ...(longitude && latitude ? { longitude: Number(longitude), latitude: Number(latitude) } : {}),
          })
        }}>
          {!rider && <label>骑手编号<input name="riderNo" required maxLength={40} /></label>}
          <label>姓名<input name="name" required maxLength={100} defaultValue={rider?.name ?? ''} /></label>
          <label>电话<input name="phone" required maxLength={30} defaultValue={rider?.phone ?? ''} /></label>
          <label>经度<input name="longitude" type="number" min="-180" max="180" step="any" defaultValue={rider?.longitude ?? ''} /></label>
          <label>纬度<input name="latitude" type="number" min="-90" max="90" step="any" defaultValue={rider?.latitude ?? ''} /></label>
          <div className="dialog-actions"><button className="secondary-button" type="button" disabled={submitting} onClick={onClose}>取消</button><button className="primary-button" type="submit" disabled={submitting}>{submitting ? '正在保存…' : '保存'}</button></div>
        </form>
      </section>
    </div>
  )
}

function FeeEditor({
  initialValue,
  disabled,
  onSave,
  onCancel,
}: {
  initialValue: number
  disabled: boolean
  onSave: (value: number) => void
  onCancel: () => void
}) {
  const [draft, setDraft] = useState(() => initialValue.toFixed(2))

  function submit() {
    if (!draft.trim()) {
      onSave(Number.NaN)
      return
    }
    const deliveryFee = Number(draft)
    if (!Number.isFinite(deliveryFee) || deliveryFee < 0) return
    onSave(deliveryFee)
  }

  return (
    <form className="fee-editor" onSubmit={event => { event.preventDefault(); submit() }}>
      <input
        aria-label="配送费"
        type="text"
        required
        maxLength={11}
        aria-invalid={false}
        inputMode="decimal"
        autoFocus
        value={draft}
        onChange={event => {
          const next = event.target.value
          if (/^\d*(\.\d{0,2})?$/.test(next)) setDraft(next)
        }}
      />
      <button className="fee-save" type="submit" disabled={disabled}>保存</button>
      <button className="fee-cancel" type="button" disabled={disabled} onClick={onCancel}>取消</button>
    </form>
  )
}

function CreateOrderDialog({
  demoMode,
  submitting,
  onClose,
  onSubmit,
}: {
  demoMode: boolean
  submitting: boolean
  onClose: () => void
  onSubmit: (order: CreateOrderRequest) => void
}) {
  useEffect(() => {
    if (submitting) return
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [onClose, submitting])

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const values = new FormData(event.currentTarget)
    const value = (name: string) => String(values.get(name) ?? '').trim()
    onSubmit({
      merchantName: value('merchantName'),
      merchantPhone: value('merchantPhone'),
      merchantAddress: value('merchantAddress'),
      merchantLongitude: Number(value('merchantLongitude')),
      merchantLatitude: Number(value('merchantLatitude')),
      receiverName: value('receiverName'),
      receiverPhone: value('receiverPhone'),
      receiverAddress: value('receiverAddress'),
      receiverLongitude: Number(value('receiverLongitude')),
      receiverLatitude: Number(value('receiverLatitude')),
      deliveryFee: Number(value('deliveryFee')),
    })
  }

  return (
    <div className="dialog-backdrop" onMouseDown={event => { if (event.target === event.currentTarget && !submitting) onClose() }}>
      <section className="create-order-dialog" role="dialog" aria-modal="true" aria-labelledby="create-order-title">
        <div className="dialog-heading">
          <div><span className="section-label">NEW DELIVERY</span><h2 id="create-order-title">新建配送订单</h2><p>填写商家与收货信息，系统会在保存后创建异步派单任务。</p></div>
          <button className="dialog-close" type="button" onClick={onClose} disabled={submitting} aria-label="关闭">×</button>
        </div>
        {demoMode && <p className="demo-explanation">当前是演示模式：提交内容只保存在本页面，不会写入数据库。</p>}
        <form className="create-order-form" onSubmit={handleSubmit}>
          <fieldset>
            <legend>商家取货信息</legend>
            <label>商家名称<input name="merchantName" required autoFocus maxLength={100} placeholder="例如：星巴克中关村店" /></label>
            <label>商家联系电话<input name="merchantPhone" required maxLength={30} inputMode="tel" placeholder="请输入联系电话" /></label>
            <label className="form-span">取货地址<input name="merchantAddress" required maxLength={255} placeholder="请输入完整取货地址" /></label>
            <label>经度<input name="merchantLongitude" required type="number" min="-180" max="180" step="any" placeholder="例如 116.397128" /></label>
            <label>纬度<input name="merchantLatitude" required type="number" min="-90" max="90" step="any" placeholder="例如 39.916527" /></label>
            <p className="coordinate-tip">经纬度使用十进制度数；系统会用取货点位置筛选附近可接单骑手。</p>
          </fieldset>
          <fieldset>
            <legend>收货信息</legend>
            <label>收货联系人<input name="receiverName" required maxLength={100} placeholder="请输入联系人姓名" /></label>
            <label>收货手机号<input name="receiverPhone" required maxLength={30} inputMode="tel" placeholder="请输入联系电话" /></label>
            <label className="form-span">收货地址<input name="receiverAddress" required maxLength={255} placeholder="请输入完整收货地址" /></label>
            <label>经度<input name="receiverLongitude" required type="number" min="-180" max="180" step="any" placeholder="例如 116.4074" /></label>
            <label>纬度<input name="receiverLatitude" required type="number" min="-90" max="90" step="any" placeholder="例如 39.9042" /></label>
          </fieldset>
          <label className="fee-field">配送费（元）<input name="deliveryFee" type="number" required min="0" max="99999999.99" step="0.01" defaultValue="0" /></label>
          <div className="dialog-actions"><button className="secondary-button" type="button" onClick={onClose} disabled={submitting}>返回</button><button className="primary-button" type="submit" disabled={submitting}>{submitting ? '正在创建…' : demoMode ? '创建演示订单' : '创建订单'}</button></div>
        </form>
      </section>
    </div>
  )
}

function App() {
  const [variant, setVariant] = useState<Variant>(getVariant)
  const [tab, setTab] = useState<Tab>('overview')
  const [riders, setRiders] = useState<Rider[]>(demoRiders)
  const [riderDirectory, setRiderDirectory] = useState<Rider[]>(demoRiders)
  const [assignableRiders, setAssignableRiders] = useState<Rider[]>([])
  const [orders, setOrders] = useState<DeliveryOrder[]>(demoOrders)
  const [recentOrders, setRecentOrders] = useState<DeliveryOrder[]>(demoOrders.slice(0, 4))
  const [dashboardSummary, setDashboardSummary] = useState<DashboardSummary | null>(null)
  const [currentDeliveryOrder, setCurrentDeliveryOrder] = useState<DeliveryOrder | null>(demoOrders.find(order => order.status === 2) ?? null)
  const [loading, setLoading] = useState(false)
  const [source, setSource] = useState<'api' | 'demo'>('demo')
  const [user, setUser] = useState<SessionUser | null>(null)
  const [authChecking, setAuthChecking] = useState(true)
  const [loginOpen, setLoginOpen] = useState(false)
  const [loginSubmitting, setLoginSubmitting] = useState(false)
  const [loginError, setLoginError] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busyOrderIds, setBusyOrderIds] = useState<Set<number>>(() => new Set())
  const busyOrderIdsRef = useRef(new Set<number>())
  const dataRequestGeneration = useRef(0)
  const sessionGeneration = useRef(0)
  const logRequestGeneration = useRef(0)
  const [assignment, setAssignment] = useState<Record<number, string>>({})
  const [editingFeeOrderId, setEditingFeeOrderId] = useState<number | null>(null)
  const [createOrderOpen, setCreateOrderOpen] = useState(false)
  const [creatingOrder, setCreatingOrder] = useState(false)
  const [dataUpdatedAt, setDataUpdatedAt] = useState<string | null>(null)
  const [connectionFailed, setConnectionFailed] = useState(false)
  const [orderPageResult, setOrderPageResult] = useState<PageResult<DeliveryOrder>>({ records: [], total: 0, page: 1, size: PAGE_SIZE })
  const [riderPageResult, setRiderPageResult] = useState<PageResult<Rider>>({ records: [], total: 0, page: 1, size: RIDER_PAGE_SIZE })
  const [orderPage, setOrderPage] = useState(1)
  const [riderPage, setRiderPage] = useState(1)
  const [orderFilter, setOrderFilter] = useState({ status: '', orderNo: '', createdFrom: '', createdTo: '' })
  const [orderFilterDraft, setOrderFilterDraft] = useState({ status: '', orderNo: '', createdFrom: '', createdTo: '' })
  const [riderFilter, setRiderFilter] = useState({ status: '', keyword: '' })
  const [riderFilterDraft, setRiderFilterDraft] = useState({ status: '', keyword: '' })
  const [logResult, setLogResult] = useState<PageResult<OperationLog>>({ records: [], total: 0, page: 1, size: PAGE_SIZE })
  const [logPage, setLogPage] = useState(1)
  const [logFilter, setLogFilter] = useState({ targetType: '', targetId: '', actor: '', action: '' })
  const [logFilterDraft, setLogFilterDraft] = useState({ targetType: '', targetId: '', actor: '', action: '' })
  const [accounts, setAccounts] = useState<Account[]>([])
  const [accountDialogOpen, setAccountDialogOpen] = useState(false)
  const [accountBusy, setAccountBusy] = useState(false)
  const [riderDialog, setRiderDialog] = useState<Rider | null | 'new'>(null)
  const [riderBusy, setRiderBusy] = useState(false)
  const [pageActionBusy, setPageActionBusy] = useState(false)
  const [expandedLogs, setExpandedLogs] = useState<Set<number>>(() => new Set())
  const isAdmin = user?.role === 'ADMIN'
  const noticeWarning = /失败|异常|未修改|未删除|不能|请输入/.test(notice)
  const showLayoutPicker = import.meta.env.DEV && new URLSearchParams(window.location.search).get('layoutPicker') === '1'

  function enterGuestMode(message = '') {
    dataRequestGeneration.current += 1
    sessionGeneration.current += 1
    setLoading(false)
    setUser(null)
    setNotice('')
    setSource('demo')
    setRiders(demoRiders)
    setRiderDirectory(demoRiders)
    setAssignableRiders(demoRiders.filter(rider => rider.status === 1 && rider.currentOrderCount < 3))
    setOrders(demoOrders)
    setRecentOrders(demoOrders.slice(0, 4))
    setDashboardSummary(null)
    setCurrentDeliveryOrder(demoOrders.find(order => order.status === 2) ?? null)
    setOrderPageResult({ records: demoOrders.slice(0, PAGE_SIZE), total: demoOrders.length, page: 1, size: PAGE_SIZE })
    setRiderPageResult({ records: demoRiders.slice(0, RIDER_PAGE_SIZE), total: demoRiders.length, page: 1, size: RIDER_PAGE_SIZE })
    setAccounts([])
    setLogResult({ records: [], total: 0, page: 1, size: PAGE_SIZE })
    setConnectionFailed(false)
    setError(message)
    setDataUpdatedAt(null)
    setOrderPage(1)
    setRiderPage(1)
    setOrderFilter({ status: '', orderNo: '', createdFrom: '', createdTo: '' })
    setOrderFilterDraft({ status: '', orderNo: '', createdFrom: '', createdTo: '' })
    setRiderFilter({ status: '', keyword: '' })
    setRiderFilterDraft({ status: '', keyword: '' })
  }

  function clearLiveData() {
    dataRequestGeneration.current += 1
    sessionGeneration.current += 1
    setRiders([])
    setRiderDirectory([])
    setAssignableRiders([])
    setOrders([])
    setRecentOrders([])
    setDashboardSummary(null)
    setCurrentDeliveryOrder(null)
    setOrderPageResult({ records: [], total: 0, page: 1, size: PAGE_SIZE })
    setRiderPageResult({ records: [], total: 0, page: 1, size: RIDER_PAGE_SIZE })
    setAccounts([])
    setLogResult({ records: [], total: 0, page: 1, size: PAGE_SIZE })
    setOrderPage(1)
    setRiderPage(1)
    setLogPage(1)
    setOrderFilter({ status: '', orderNo: '', createdFrom: '', createdTo: '' })
    setOrderFilterDraft({ status: '', orderNo: '', createdFrom: '', createdTo: '' })
    setRiderFilter({ status: '', keyword: '' })
    setRiderFilterDraft({ status: '', keyword: '' })
    setLogFilter({ targetType: '', targetId: '', actor: '', action: '' })
    setLogFilterDraft({ targetType: '', targetId: '', actor: '', action: '' })
    setDataUpdatedAt(null)
  }

  async function handleLogin(username: string, password: string) {
    setLoginSubmitting(true)
    setLoginError('')
    try {
      const session = await authApi.login(username, password)
      clearLiveData()
      setUser(session)
      setSource('api')
      setError('')
      setNotice(`已登录：${session.username}（${session.role === 'ADMIN' ? '管理员' : '调度员'}）`)
      setLoginOpen(false)
      setOrderPage(1)
      setRiderPage(1)
    } catch (loginFailure) {
      setLoginError(loginFailure instanceof Error ? loginFailure.message : '登录失败，请检查账号信息')
    } finally {
      setLoginSubmitting(false)
    }
  }

  async function handleLogout() {
    setPageActionBusy(true)
    try {
      await authApi.logout()
      enterGuestMode('已安全退出登录，当前仅显示本地演示数据。')
      setTab('overview')
    } catch (logoutFailure) {
      if (logoutFailure instanceof ApiError && logoutFailure.status === 401) {
        enterGuestMode('登录已过期，请重新登录后查看真实业务数据。')
        setTab('overview')
        return
      }
      const message = logoutFailure instanceof Error ? logoutFailure.message : '退出登录失败'
      setNotice(`${message} · 当前会话状态未确认`)
    } finally {
      setPageActionBusy(false)
    }
  }

  const loadData = useCallback(async () => {
    if (!user) return
    const generation = ++dataRequestGeneration.current
    setLoading(true)
    setError('')
    try {
      const params = {
        page: orderPage,
        size: PAGE_SIZE,
        status: orderFilter.status === '' ? '' as const : Number(orderFilter.status),
        orderNo: orderFilter.orderNo,
        createdFrom: localDateTimeToUtcLocal(orderFilter.createdFrom),
        createdTo: localDateTimeToUtcLocal(orderFilter.createdTo),
      }
      const [nextOrders, nextRiders, nextAssignable, nextRecent, nextSummary, nextDelivery] = await Promise.all([
        orderApi.page(params),
        riderApi.page({ page: riderPage, size: RIDER_PAGE_SIZE, status: riderFilter.status === '' ? '' : Number(riderFilter.status), keyword: riderFilter.keyword }),
        riderApi.list(),
        orderApi.page({ page: 1, size: 4 }),
        dashboardApi.summary(),
        orderApi.page({ page: 1, size: 1, status: 2 }),
      ])
      if (generation !== dataRequestGeneration.current || !user) return
      setOrderPageResult(nextOrders)
      setRiderPageResult(nextRiders)
      setOrders(nextOrders.records)
      setRecentOrders(nextRecent.records)
      setDashboardSummary(nextSummary)
      setCurrentDeliveryOrder(nextDelivery.records[0] ?? null)
      setRiders(nextRiders.records)
      setRiderDirectory(nextAssignable)
      setAssignableRiders(nextAssignable.filter(rider => rider.status === 1 && rider.currentOrderCount < 3))
      setSource('api')
      setConnectionFailed(false)
      setDataUpdatedAt(new Date().toISOString())
    } catch (requestError) {
      if (generation !== dataRequestGeneration.current || !user) return
      if (requestError instanceof ApiError && requestError.status === 401) {
        enterGuestMode('登录已过期，请重新登录后查看真实业务数据。')
      } else {
        const message = requestError instanceof Error ? requestError.message : '后端暂不可用'
        setConnectionFailed(!(requestError instanceof ApiError))
        setError(`刷新失败，保留当前真实数据；数据可能已过期：${message}`)
      }
    } finally {
      if (generation === dataRequestGeneration.current) setLoading(false)
    }
  }, [user, orderPage, riderPage, orderFilter, riderFilter])

  useEffect(() => {
    let active = true
    authApi.me().then(session => {
      if (!active) return
      clearLiveData()
      setUser(session)
      setSource('api')
    }).catch(authFailure => {
      if (!active) return
      const message = authFailure instanceof ApiError && authFailure.status === 401
        ? ''
        : '无法验证登录状态，当前仅显示本地演示数据。'
      enterGuestMode(message)
    }).finally(() => { if (active) setAuthChecking(false) })
    return () => { active = false }
  }, [])

  useEffect(() => {
    if (user) void loadData()
  }, [loadData, user])

  useEffect(() => {
    if (!user || tab !== 'accounts' || !isAdmin) return
    let active = true
    const generation = sessionGeneration.current
    accountApi.list().then(result => {
      if (active && generation === sessionGeneration.current) setAccounts(result)
    }).catch(errorValue => {
      if (active && generation === sessionGeneration.current) handleProtectedError(errorValue, '账号列表加载失败')
    })
    return () => { active = false }
  }, [user, tab, isAdmin])

  useEffect(() => {
    if (!user || tab !== 'logs' || !isAdmin) return
    let active = true
    const generation = sessionGeneration.current
    const requestGeneration = ++logRequestGeneration.current
    operationLogApi.page({ page: logPage, size: PAGE_SIZE, ...logFilter }).then(result => {
      if (active && generation === sessionGeneration.current && requestGeneration === logRequestGeneration.current) setLogResult(result)
    }).catch(errorValue => {
      if (active && generation === sessionGeneration.current && requestGeneration === logRequestGeneration.current) handleProtectedError(errorValue, '操作记录加载失败')
    })
    return () => { active = false }
  }, [user, tab, isAdmin, logPage, logFilter])

  function handleProtectedError(value: unknown, fallback: string) {
    if (value instanceof ApiError && value.status === 401) {
      enterGuestMode('登录已过期，请重新登录后查看真实业务数据。')
    } else {
      setConnectionFailed(!(value instanceof ApiError))
      setError(value instanceof Error ? value.message : fallback)
    }
  }

  function changeOrderPage(page: number) {
    dataRequestGeneration.current += 1
    setOrderPage(page)
  }

  function applyOrderFilter(filter: typeof orderFilter) {
    dataRequestGeneration.current += 1
    setOrderFilter(filter)
    setOrderPage(1)
  }

  function changeRiderPage(page: number) {
    dataRequestGeneration.current += 1
    setRiderPage(page)
  }

  function applyRiderFilter(filter: typeof riderFilter) {
    dataRequestGeneration.current += 1
    setRiderFilter(filter)
    setRiderPage(1)
  }

  function applyLogFilter(filter: typeof logFilter) {
    logRequestGeneration.current += 1
    setLogFilter(filter)
    setLogPage(1)
  }

  function changeLogPage(page: number) {
    logRequestGeneration.current += 1
    setLogPage(page)
  }

  useEffect(() => {
    if (!showLayoutPicker) return
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
  }, [variant, showLayoutPicker])

  function switchVariant(next: Variant) {
    setVariant(next)
    const url = new URL(window.location.href)
    url.searchParams.set('variant', next)
    window.history.replaceState({}, '', url)
  }

  function replaceOrder(updated: DeliveryOrder) {
    setOrders(current => current.map(order => order.id === updated.id ? { ...order, ...updated } : order))
    setOrderPageResult(current => ({ ...current, records: current.records.map(order => order.id === updated.id ? { ...order, ...updated } : order) }))
    setRecentOrders(current => current.map(order => order.id === updated.id ? { ...order, ...updated } : order))
    setCurrentDeliveryOrder(current => current?.id === updated.id ? (updated.status === 2 ? { ...current, ...updated } : null) : current)
  }

  function updateDemoOrder(orderId: number, update: Partial<DeliveryOrder>) {
    setOrders(current => current.map(order => order.id === orderId ? { ...order, ...update } : order))
    setOrderPageResult(current => ({ ...current, records: current.records.map(order => order.id === orderId ? { ...order, ...update } : order) }))
    setRecentOrders(current => current.map(order => order.id === orderId ? { ...order, ...update } : order))
    setCurrentDeliveryOrder(current => {
      if (current?.id !== orderId) return current
      const next = { ...current, ...update }
      return next.status === 2 ? next : null
    })
  }

  function upsertRider(updated: Rider, prepend = false) {
    setRiderDirectory(current => current.some(rider => rider.id === updated.id)
      ? current.map(rider => rider.id === updated.id ? updated : rider)
      : prepend ? [updated, ...current].slice(0, 200) : current)
    setRiders(current => current.some(rider => rider.id === updated.id)
      ? current.map(rider => rider.id === updated.id ? updated : rider)
      : prepend ? [updated, ...current] : current)
    setRiderPageResult(current => ({
      ...current,
      total: current.total + (prepend && !current.records.some(rider => rider.id === updated.id) ? 1 : 0),
      records: current.records.some(rider => rider.id === updated.id)
        ? current.records.map(rider => rider.id === updated.id ? updated : rider)
        : prepend ? [updated, ...current.records].slice(0, current.size) : current.records,
    }))
  }

  function beginOrderBusy(orderId: number) {
    if (busyOrderIdsRef.current.has(orderId)) return false
    const next = new Set(busyOrderIdsRef.current)
    next.add(orderId)
    busyOrderIdsRef.current = next
    setBusyOrderIds(next)
    return true
  }

  function endOrderBusy(orderId: number) {
    const next = new Set(busyOrderIdsRef.current)
    next.delete(orderId)
    busyOrderIdsRef.current = next
    setBusyOrderIds(next)
  }

  async function runOrderAction(
    orderId: number,
    action: () => Promise<DeliveryOrder>,
    fallback: Partial<DeliveryOrder>,
  ): Promise<boolean> {
    if (!beginOrderBusy(orderId)) return false
    setNotice('')
    if (source === 'demo') {
      updateDemoOrder(orderId, fallback)
      setNotice('演示操作已完成，仅修改当前页面数据，未连接或写入真实业务接口。')
      endOrderBusy(orderId)
      return true
    }
    try {
      replaceOrder(await action())
      setNotice('操作成功，订单状态已刷新')
      void loadData()
      return true
    } catch (actionError) {
      if (actionError instanceof ApiError && actionError.status === 401) {
        enterGuestMode('登录已过期，请重新登录后查看真实业务数据。')
      }
      setNotice(actionError instanceof Error
        ? `${actionError.message} · 未修改页面数据，请稍后重试`
        : '请求失败，未修改页面数据，请稍后重试')
      return false
    } finally {
      endOrderBusy(orderId)
    }
  }

  function handleDispatch(order: DeliveryOrder) {
    const available = assignableRiders[0]
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

  async function handleCreateOrder(request: CreateOrderRequest) {
    setCreatingOrder(true)
    setNotice('')
    try {
      if (source === 'demo') {
        const now = new Date().toISOString()
        const order: DeliveryOrder = {
          ...request,
          id: -Date.now(),
          orderNo: `DEMO${Date.now()}`,
          riderId: null,
          status: 0,
          createdAt: now,
          updatedAt: now,
        }
        setOrders(current => [order, ...current])
        setRecentOrders(current => [order, ...current].slice(0, 4))
        setOrderPage(1)
        setOrderFilter({ status: '', orderNo: '', createdFrom: '', createdTo: '' })
        setOrderFilterDraft({ status: '', orderNo: '', createdFrom: '', createdTo: '' })
        setNotice('演示订单已添加到当前页面，仅保存在本页面，未写入数据库。')
      } else {
        const order = await orderApi.create(request)
        setOrders(current => [order, ...current])
        setRecentOrders(current => [order, ...current].slice(0, 4))
        setOrderPage(1)
        setOrderFilter({ status: '', orderNo: '', createdFrom: '', createdTo: '' })
        setOrderFilterDraft({ status: '', orderNo: '', createdFrom: '', createdTo: '' })
        setNotice('订单已创建，系统已记录异步派单任务。')
      }
      setCreateOrderOpen(false)
      if (source === 'api') void loadData()
    } catch (createError) {
      if (createError instanceof ApiError && createError.status === 401) enterGuestMode('登录已过期，请重新登录后查看真实业务数据。')
      setNotice(createError instanceof Error ? `${createError.message} · 订单未创建` : '创建订单失败，请稍后重试')
    } finally {
      setCreatingOrder(false)
    }
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

  function beginFeeEdit(order: DeliveryOrder) {
    setEditingFeeOrderId(order.id)
    setNotice('')
  }

  function handleUpdateFee(order: DeliveryOrder, deliveryFee: number) {
    if (!Number.isFinite(deliveryFee) || deliveryFee < 0 || deliveryFee > 99_999_999.99 || Math.round(deliveryFee * 100) !== deliveryFee * 100) {
      setNotice('配送费须为 0 至 99,999,999.99 元，最多保留两位小数')
      return
    }
    void runOrderAction(order.id, () => orderApi.updateFee(order.id, deliveryFee), { deliveryFee })
      .then(success => {
        if (success) {
          setEditingFeeOrderId(null)
        }
      })
  }

  async function handleDelete(order: DeliveryOrder) {
    const active = order.status === 1 || order.status === 2
    const suffix = active ? '这会同时释放已占用的骑手名额。' : ''
    if (!window.confirm(`确定删除订单 ${order.orderNo} 吗？${suffix}`)) return
    const reason = window.prompt(source === 'demo' ? '填写演示删除说明（仅留在当前操作中）' : '请填写删除原因（操作将写入审计记录）', '测试数据清理')?.trim()
    if (!reason) {
      setNotice('已取消删除，请填写非空删除原因后重试。')
      return
    }
    if (!beginOrderBusy(order.id)) return
    setNotice('')
    if (source === 'demo') {
      setOrders(current => current.filter(item => item.id !== order.id))
      setRecentOrders(current => current.filter(item => item.id !== order.id))
      setCurrentDeliveryOrder(current => current?.id === order.id ? null : current)
      setOrderPageResult(current => ({ ...current, records: current.records.filter(item => item.id !== order.id), total: Math.max(0, current.total - 1) }))
      setNotice('演示订单已在当前页面删除；未连接或写入真实业务接口。')
      endOrderBusy(order.id)
      return
    }
    try {
      await orderApi.remove(order.id, reason)
      setOrders(current => current.filter(item => item.id !== order.id))
      setRecentOrders(current => current.filter(item => item.id !== order.id))
      setCurrentDeliveryOrder(current => current?.id === order.id ? null : current)
      setOrderPageResult(current => ({ ...current, records: current.records.filter(item => item.id !== order.id), total: Math.max(0, current.total - 1) }))
      setNotice('订单记录已删除')
      void loadData()
    } catch (actionError) {
      if (actionError instanceof ApiError && actionError.status === 401) enterGuestMode('登录已过期，请重新登录后查看真实业务数据。')
      setNotice(actionError instanceof Error ? `${actionError.message} · 未删除页面数据，请稍后重试` : '删除失败，未修改页面数据，请稍后重试')
    } finally {
      endOrderBusy(order.id)
    }
  }

  const availableRiders = useMemo(() => source === 'demo'
    ? riders.filter(rider => rider.status === 1 && rider.currentOrderCount < 3)
    : assignableRiders, [riders, assignableRiders, source])

  async function handleRiderSave(request: RiderRequest) {
    const longitude = request.longitude
    const latitude = request.latitude
    const hasLongitude = longitude !== undefined
    const hasLatitude = latitude !== undefined
    if (hasLongitude !== hasLatitude || (hasLongitude && latitude !== undefined && (!Number.isFinite(longitude) || !Number.isFinite(latitude) || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90))) {
      setNotice('经纬度必须同时填写，且经度范围为 -180 至 180、纬度范围为 -90 至 90。')
      return
    }
    const editing = riderDialog !== 'new' ? riderDialog : null
    setRiderBusy(true)
    try {
      if (source === 'demo') {
        if (editing) {
          setRiders(current => current.map(rider => rider.id === editing.id ? { ...rider, ...request, updatedAt: new Date().toISOString() } : rider))
          setRiderDirectory(current => current.map(rider => rider.id === editing.id ? { ...rider, ...request } : rider))
          setAssignableRiders(current => current.map(rider => rider.id === editing.id ? { ...rider, ...request } : rider))
        } else {
          const created: Rider = { ...request, id: -Date.now(), riderNo: request.riderNo ?? `DEMO-${Date.now()}`, status: 1, currentOrderCount: 0, longitude: request.longitude ?? 0, latitude: request.latitude ?? 0 }
          setRiders(current => [created, ...current])
          setRiderDirectory(current => [created, ...current].slice(0, 200))
          setAssignableRiders(current => [created, ...current])
        }
        setNotice('演示骑手信息已在当前页面更新，未写入数据库。')
      } else {
        const saved = editing ? await riderApi.update(editing.id, request) : await riderApi.create(request)
        upsertRider(saved, !editing)
        setAssignableRiders(current => [...current.filter(rider => rider.id !== saved.id), ...(saved.status === 1 && saved.currentOrderCount < 3 ? [saved] : [])])
        if (!editing) setRiderPage(1)
        setNotice(editing ? '骑手资料已更新' : '骑手已创建')
        void loadData()
      }
      setRiderDialog(null)
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) enterGuestMode('登录已过期，请重新登录后查看真实业务数据。')
      setNotice(failure instanceof Error ? `${failure.message} · 未保存骑手资料` : '保存骑手失败')
    } finally {
      setRiderBusy(false)
    }
  }

  async function handleRiderEnabled(rider: Rider) {
    const enabled = rider.status !== 1
    setPageActionBusy(true)
    try {
      if (source === 'demo') {
        const updated = { ...rider, status: enabled ? 1 : 3 }
        setRiders(current => current.map(item => item.id === rider.id ? updated : item))
        setRiderDirectory(current => current.map(item => item.id === rider.id ? updated : item))
        setAssignableRiders(current => enabled && updated.currentOrderCount < 3 ? [...current.filter(item => item.id !== rider.id), updated] : current.filter(item => item.id !== rider.id))
        setNotice(`演示骑手已${enabled ? '启用' : '停用'}，仅在当前页面生效。`)
      } else {
        const updated = await riderApi.setEnabled(rider.id, enabled)
        upsertRider(updated)
        setAssignableRiders(current => enabled && updated.currentOrderCount < 3 ? [...current.filter(item => item.id !== updated.id), updated] : current.filter(item => item.id !== updated.id))
        setNotice(`骑手已${enabled ? '启用' : '停用'}`)
        void loadData()
      }
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) enterGuestMode('登录已过期，请重新登录后查看真实业务数据。')
      setNotice(failure instanceof Error ? failure.message : '骑手状态更新失败')
    } finally {
      setPageActionBusy(false)
    }
  }

  async function handleRiderLocation(rider: Rider) {
    const longitudeText = window.prompt(`输入 ${rider.name} 的经度（-180 至 180）`, String(rider.longitude))
    if (longitudeText === null) return
    const latitudeText = window.prompt(`输入 ${rider.name} 的纬度（-90 至 90）`, String(rider.latitude))
    if (latitudeText === null) return
    const longitude = Number(longitudeText)
    const latitude = Number(latitudeText)
    if (!Number.isFinite(longitude) || longitude < -180 || longitude > 180 || !Number.isFinite(latitude) || latitude < -90 || latitude > 90) {
      setNotice('请输入有效的经纬度坐标。')
      return
    }
    setPageActionBusy(true)
    try {
      if (source === 'demo') {
        setRiders(current => current.map(item => item.id === rider.id ? { ...item, longitude, latitude } : item))
        setRiderDirectory(current => current.map(item => item.id === rider.id ? { ...item, longitude, latitude } : item))
        setNotice('演示骑手位置已更新，仅在当前页面生效。')
      } else {
        const updated = await riderApi.updateLocation(rider.id, longitude, latitude)
        upsertRider(updated)
        setNotice('骑手位置已更新')
        void loadData()
      }
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) enterGuestMode('登录已过期，请重新登录后查看真实业务数据。')
      setNotice(failure instanceof Error ? failure.message : '骑手位置更新失败')
    } finally {
      setPageActionBusy(false)
    }
  }

  async function handleAccountCreate(username: string, password: string, role: AccountRole) {
    if (!username || !password) {
      setNotice('用户名和密码不能为空。')
      return
    }
    setAccountBusy(true)
    try {
      if (source === 'demo') {
        const account: Account = { id: -Date.now(), username, role, enabled: true, createdAt: new Date().toISOString() }
        setAccounts(current => [account, ...current])
        setNotice('演示账号已在当前页面添加，未写入数据库。')
      } else {
        const created = await accountApi.create(username, password, role)
        setAccounts(current => [created, ...current])
        setNotice('账号已创建')
      }
      setAccountDialogOpen(false)
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) enterGuestMode('登录已过期，请重新登录后查看真实业务数据。')
      setNotice(failure instanceof Error ? `${failure.message} · 账号未创建` : '账号创建失败')
    } finally {
      setAccountBusy(false)
    }
  }

  async function handleAccountEnabled(account: Account) {
    if (account.username === user?.username && account.enabled) {
      setNotice('不能禁用当前登录账号。')
      return
    }
    setAccountBusy(true)
    try {
      if (source === 'demo') {
        setAccounts(current => current.map(item => item.id === account.id ? { ...item, enabled: !account.enabled } : item))
        setNotice('演示账号状态已修改，仅在当前页面生效。')
      } else {
        const updated = await accountApi.setEnabled(account.id, !account.enabled)
        setAccounts(current => current.map(item => item.id === updated.id ? updated : item))
        setNotice(`账号已${updated.enabled ? '启用' : '禁用'}`)
      }
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) enterGuestMode('登录已过期，请重新登录后查看真实业务数据。')
      setNotice(failure instanceof Error ? failure.message : '账号状态更新失败')
    } finally {
      setAccountBusy(false)
    }
  }
  const lastSyncLabel = source === 'demo'
    ? '演示数据'
    : dataUpdatedAt
      ? new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(dataUpdatedAt))
      : '等待同步'
  const orderRows = useMemo(() => {
    if (source === 'api') return orders
    const filtered = orders.filter(order => {
      if (orderFilter.status !== '' && order.status !== Number(orderFilter.status)) return false
      if (orderFilter.orderNo && !order.orderNo.toLowerCase().includes(orderFilter.orderNo.toLowerCase())) return false
      const created = order.createdAt ? new Date(order.createdAt).getTime() : 0
      if (orderFilter.createdFrom && created < new Date(orderFilter.createdFrom).getTime()) return false
      if (orderFilter.createdTo && created > new Date(orderFilter.createdTo).getTime()) return false
      return true
    })
    return filtered.slice((orderPage - 1) * PAGE_SIZE, orderPage * PAGE_SIZE)
  }, [orders, orderFilter, orderPage, source])
  const orderTotal = source === 'demo'
    ? orders.filter(order => {
      if (orderFilter.status !== '' && order.status !== Number(orderFilter.status)) return false
      if (orderFilter.orderNo && !order.orderNo.toLowerCase().includes(orderFilter.orderNo.toLowerCase())) return false
      const created = order.createdAt ? new Date(order.createdAt).getTime() : 0
      if (orderFilter.createdFrom && created < new Date(orderFilter.createdFrom).getTime()) return false
      if (orderFilter.createdTo && created > new Date(orderFilter.createdTo).getTime()) return false
      return true
    }).length
    : orderPageResult.total
  const riderRows = useMemo(() => {
    if (source === 'api') return riderPageResult.records
    const filtered = riders.filter(rider => (riderFilter.status === '' || rider.status === Number(riderFilter.status))
      && (!riderFilter.keyword || `${rider.name} ${rider.riderNo} ${rider.phone}`.toLowerCase().includes(riderFilter.keyword.toLowerCase())))
    return filtered.slice((riderPage - 1) * RIDER_PAGE_SIZE, riderPage * RIDER_PAGE_SIZE)
  }, [riders, riderPageResult.records, riderFilter, riderPage, source])
  const riderTotal = source === 'demo'
    ? riders.filter(rider => (riderFilter.status === '' || rider.status === Number(riderFilter.status))
      && (!riderFilter.keyword || `${rider.name} ${rider.riderNo} ${rider.phone}`.toLowerCase().includes(riderFilter.keyword.toLowerCase()))).length
    : riderPageResult.total
  const canManage = source === 'demo' || isAdmin
  const dashboardCounts: DashboardSummary = source === 'api' && dashboardSummary ? dashboardSummary : {
    totalOrders: orders.length,
    pendingOrders: orders.filter(order => order.status === 0).length,
    assignedOrders: orders.filter(order => order.status === 1).length,
    deliveringOrders: orders.filter(order => order.status === 2).length,
    completedOrders: orders.filter(order => order.status === 3).length,
    canceledOrders: orders.filter(order => order.status === 4).length,
    totalRiders: riders.length,
    availableRiders: riders.filter(rider => rider.status === 1 && rider.currentOrderCount < 3).length,
  }

  const nav = (
    <nav className="nav-list" aria-label="主导航">
      <button className={tab === 'overview' ? 'nav-item active' : 'nav-item'} onClick={() => setTab('overview')}>
        <span>◈</span>总览 <em>01</em>
      </button>
      <button className={tab === 'riders' ? 'nav-item active' : 'nav-item'} onClick={() => setTab('riders')}>
        <span>♙</span>骑手中心 <em>{dashboardCounts.totalRiders.toString().padStart(2, '0')}</em>
      </button>
      <button className={tab === 'orders' ? 'nav-item active' : 'nav-item'} onClick={() => setTab('orders')}>
        <span>▤</span>订单调度 <em>{dashboardCounts.totalOrders.toString().padStart(2, '0')}</em>
      </button>
      {isAdmin && <>
        <button className={tab === 'accounts' ? 'nav-item active' : 'nav-item'} onClick={() => setTab('accounts')}><span>♙</span>账号管理</button>
        <button className={tab === 'logs' ? 'nav-item active' : 'nav-item'} onClick={() => setTab('logs')}><span>≋</span>操作记录</button>
      </>}
    </nav>
  )

  const header = (
    <header className="topbar">
      <div className="topbar-heading">
        <div className="eyebrow">{source === 'api' ? `运营数据 · 最近同步 ${lastSyncLabel}` : '项目演示 · 使用示例数据'}</div>
        <h1>{tab === 'overview' ? '同城配送调度与履约平台' : tab === 'riders' ? '骑手资源中心' : tab === 'orders' ? '订单调度台' : tab === 'accounts' ? '账号权限管理' : '操作记录'}</h1>
      </div>
      <div className="topbar-actions">
        <span className={`connection ${source} ${connectionFailed ? 'connection-error' : ''}`}><i />{source === 'demo' ? '访客演示' : connectionFailed ? '连接异常' : 'API 已响应'}</span>
        {user && <button className="icon-button" onClick={() => void loadData()} title="刷新数据" disabled={loading}>↻</button>}
        {user ? <><div className="operator"><span>{user.username.slice(0, 2).toUpperCase()}</span><div><strong>{user.username}</strong><small>{user.role === 'ADMIN' ? '管理员' : '调度员'}</small></div></div><button className="secondary-button session-button" disabled={pageActionBusy} onClick={() => void handleLogout()}>{pageActionBusy ? '处理中…' : '退出'}</button></> : <button className="primary-button session-button" onClick={() => { setLoginOpen(true); setLoginError('') }}>运营人员登录</button>}
      </div>
    </header>
  )

  function renderOverview() {
    const distribution = [0, 1, 2, 3].map(status => ({
      ...ORDER_STATUS[status as OrderStatus],
      count: [dashboardCounts.pendingOrders, dashboardCounts.assignedOrders, dashboardCounts.deliveringOrders, dashboardCounts.completedOrders][status] ?? 0,
    }))
    return (
      <>
        <section className="hero-strip">
          <div>
            <span className="hero-kicker">LOCAL DELIVERY · ORDER FULFILLMENT</span>
            <h2>从创建到送达，<br /><i>配送全程可追踪。</i></h2>
            <p>运营人员创建订单，系统匹配附近可接单骑手，并持续跟踪派单、配送与完成状态。</p>
          </div>
          <div className={`route-summary ${currentDeliveryOrder ? '' : 'route-summary-empty'}`} aria-label="实时配送">
            <div className="route-summary-head"><span className="route-badge">实时配送</span>{currentDeliveryOrder && <span className="route-status"><i />配送中</span>}</div>
            {currentDeliveryOrder ? <>
              <div className="route-track"><div><small>取货点</small><strong>{currentDeliveryOrder.merchantName}</strong></div><span className="route-arrow">→</span><div><small>收货点</small><strong>{currentDeliveryOrder.receiverName}</strong></div></div>
              <small>{currentDeliveryOrder.orderNo} · 当前配送订单</small>
            </> : <>
              <p>当前没有配送中的订单</p>
              <button type="button" className="route-empty-action" onClick={() => setTab('orders')}>查看订单队列 →</button>
            </>}
          </div>
        </section>
        <section className="metric-grid">
          <Metric label="订单总量" value={dashboardCounts.totalOrders} detail={`${dashboardCounts.pendingOrders} 单待派单`} icon="↗" tone="orange" />
          <Metric label="可接单骑手" value={dashboardCounts.availableRiders} detail={`${dashboardCounts.totalRiders} 名已登记骑手`} icon="♙" tone="mint" />
          <Metric label="配送中" value={dashboardCounts.deliveringOrders} detail={`${dashboardCounts.assignedOrders + dashboardCounts.deliveringOrders} 单已派单或配送中`} icon="◎" tone="blue" />
          <Metric label="累计已完成" value={dashboardCounts.completedOrders} detail="历史完成订单总数" icon="✓" tone="purple" />
        </section>
        <section className="panel project-overview-panel">
          <div className="panel-heading">
            <div><span className="section-label">BUSINESS & ENGINEERING</span><h3>业务能力与技术实现</h3></div>
            <span className="count-badge">同城配送履约</span>
          </div>
          <div className="project-overview-grid">
            <div className="project-summary">
              <p><strong>DispatchOS</strong> 面向同城即时配送运营场景，串联订单创建、附近骑手匹配、人工调度、配送状态推进与订单完成；调度台也支持调整配送费和管理订单记录。</p>
              <p className="project-summary-note">MySQL 保存业务数据 · Redis 提供缓存与 GEO 检索 · RocketMQ 承接异步派单 · Swagger / Actuator 辅助接口调试与运行检查</p>
            </div>
            <div className="project-capabilities">
              <div className="capability-item"><span className="capability-index">01</span><div><strong>附近骑手调度</strong><small><b>Redis GEO + 负载排序</b> · 筛选附近可用骑手并自动派单。</small></div></div>
              <div className="capability-item"><span className="capability-index">02</span><div><strong>订单与名额一致性</strong><small><b>MySQL + @Transactional</b> · 条件更新订单，保护骑手名额。</small></div></div>
              <div className="capability-item"><span className="capability-index">03</span><div><strong>异步派单链路</strong><small><b>Transactional Outbox + RocketMQ</b> · 可靠投递派单事件。</small></div></div>
              <div className="capability-item"><span className="capability-index">04</span><div><strong>骑手详情缓存保护</strong><small><b>Cache Aside</b> · 空值缓存、随机 TTL 与互斥重建。</small></div></div>
            </div>
          </div>
          <div className="project-lifecycle" aria-label="订单生命周期">
            <div className="lifecycle-heading"><span>订单履约状态</span><small>{source === 'api' ? '全库订单状态汇总，不受分页筛选影响' : '本地演示订单统计'}</small></div>
            <div className="lifecycle-steps">
              {distribution.map((item, index) => <Fragment key={item.label}>
                {index > 0 && <span className="lifecycle-arrow">→</span>}
                <div className={`lifecycle-step tone-${item.tone}`}><b>{index}</b><strong>{item.label}</strong><small>{item.count} 单</small></div>
              </Fragment>)}
            </div>
            <div className="lifecycle-cancel-branch"><span>待派单阶段可取消</span><b>已取消 {dashboardCounts.canceledOrders} 单</b></div>
          </div>
        </section>
        <section className="content-grid overview-grid">
          <div className="panel rider-panel">
            <div className="panel-heading"><div><span className="section-label">RIDER RADAR</span><h3>骑手资源</h3></div><button className="text-button" onClick={() => setTab('riders')}>查看全部 →</button></div>
            <div className="rider-stack">
              {riders.slice(0, 3).map(rider => (
                <div className="rider-line" key={rider.id}><div className="avatar">{rider.name.slice(0, 1)}</div><div className="rider-line-copy"><strong>{rider.name}<small>{rider.riderNo}</small></strong><span>{rider.currentOrderCount} 个进行中订单</span></div><span className={`availability ${rider.status !== 1 ? 'disabled' : rider.currentOrderCount >= 3 ? 'capacity-full' : 'available'}`}><i />{rider.status !== 1 ? '已停用' : rider.currentOrderCount >= 3 ? '容量已满' : '可接单'}</span></div>
              ))}
              {!riders.length && <div className="empty-state">暂无骑手数据</div>}
            </div>
          </div>
        </section>
        <section className="panel recent-panel">
          <div className="panel-heading"><div><span className="section-label">RECENT DISPATCHES</span><h3>最近订单</h3></div><button className="text-button" onClick={() => setTab('orders')}>进入调度台 →</button></div>
          <OrderTable orders={recentOrders.slice(0, 4)} riders={riderDirectory} compact />
        </section>
      </>
    )
  }

  function renderRiders() {
    return (
      <section className="panel page-panel">
        <div className="panel-heading"><div><span className="section-label">RIDER DIRECTORY</span><h3>全部骑手</h3></div><div className="order-heading-actions"><span className="count-badge">{riderTotal} RIDERS</span>{canManage && <button className="primary-button" onClick={() => setRiderDialog('new')}>＋ 新增骑手</button>}</div></div>
        <form className="filter-bar" onSubmit={event => { event.preventDefault(); applyRiderFilter(riderFilterDraft) }}>
          <label>状态<select value={riderFilterDraft.status} onChange={event => { const value = event.currentTarget.value; setRiderFilterDraft(current => ({ ...current, status: value })) }}><option value="">全部状态</option><option value="1">可接单</option><option value="3">已停用</option></select></label>
          <label>关键词<input value={riderFilterDraft.keyword} onChange={event => { const value = event.currentTarget.value; setRiderFilterDraft(current => ({ ...current, keyword: value })) }} placeholder="姓名、编号或电话" /></label>
          <button className="secondary-button" type="submit">筛选</button>
          <button className="text-button" type="button" onClick={() => { const cleared = { status: '', keyword: '' }; setRiderFilterDraft(cleared); applyRiderFilter(cleared) }}>清除</button>
        </form>
        <div className="table-wrap"><table><thead><tr><th>骑手</th><th>状态</th><th>当前位置</th><th>进行中订单</th><th>最后同步</th>{canManage && <th>管理</th>}</tr></thead><tbody>{riderRows.map(rider => <tr key={rider.id}><td><div className="table-person"><div className="avatar">{rider.name.slice(0, 1)}</div><div><strong>{rider.name}</strong><small>{rider.riderNo} · {rider.phone}</small></div></div></td><td><span className={`availability ${rider.status === 1 ? 'available' : 'disabled'}`}><i />{rider.status === 1 ? '可接单' : '已停用'}</span></td><td><span className="coordinates">{Number(rider.longitude).toFixed(4)}, {Number(rider.latitude).toFixed(4)}</span></td><td><strong className="order-count">{rider.currentOrderCount}</strong></td><td className="muted-text">{formatTime(rider.updatedAt, source === 'api')}</td>{canManage && <td><div className="row-actions"><button className="text-button" disabled={pageActionBusy || riderBusy} onClick={() => setRiderDialog(rider)}>编辑</button><button className="text-button" disabled={pageActionBusy} onClick={() => void handleRiderLocation(rider)}>上报位置</button><button className="text-button" disabled={pageActionBusy} onClick={() => void handleRiderEnabled(rider)}>{rider.status === 1 ? '停用' : '启用'}</button></div></td>}</tr>)}</tbody></table>{!riderRows.length && <div className="empty-state">{riderTotal ? '当前筛选没有匹配骑手' : '暂无骑手数据'}</div>}</div>
        {renderPager(riderPage, riderTotal, RIDER_PAGE_SIZE, changeRiderPage)}
      </section>
    )
  }

  function renderPager(page: number, total: number, size: number, setPage: (page: number) => void) {
    const pages = Math.max(1, Math.ceil(total / size))
    return <div className="pager"><span>共 {total} 条 · 第 {Math.min(page, pages)} / {pages} 页</span><div><button className="secondary-button" disabled={page <= 1 || loading} onClick={() => setPage(Math.max(1, page - 1))}>上一页</button><button className="secondary-button" disabled={page >= pages || loading} onClick={() => setPage(Math.min(pages, page + 1))}>下一页</button></div></div>
  }

  function renderOrders() {
    return (
      <section className="panel page-panel">
        <div className="panel-heading"><div><span className="section-label">DISPATCH QUEUE</span><h3>订单调度</h3><p className="panel-description">创建配送任务，跟进派单、配送、费用与订单记录。</p></div><div className="order-heading-actions"><span className="count-badge">{orderTotal} 条订单</span><button className="primary-button create-order-button" onClick={() => { setCreateOrderOpen(true); setNotice('') }}>＋ 新建订单</button></div></div>
        <form className="filter-bar order-filter-bar" onSubmit={event => {
          event.preventDefault()
          // Native date controls may commit on blur: use the form's submitted values.
          const form = new FormData(event.currentTarget)
          const filter = { ...orderFilterDraft, createdFrom: String(form.get('createdFrom') ?? ''), createdTo: String(form.get('createdTo') ?? '') }
          setOrderFilterDraft(filter)
          applyOrderFilter(filter)
        }}>
          <label>状态<select value={orderFilterDraft.status} onChange={event => { const value = event.currentTarget.value; setOrderFilterDraft(current => ({ ...current, status: value })) }}><option value="">全部状态</option>{Object.entries(ORDER_STATUS).map(([code, value]) => <option key={code} value={code}>{value.label}</option>)}</select></label>
          <label>订单号<input value={orderFilterDraft.orderNo} onChange={event => { const value = event.currentTarget.value; setOrderFilterDraft(current => ({ ...current, orderNo: value })) }} placeholder="输入订单号" /></label>
          <label>创建时间从（本地时间）<input name="createdFrom" type="datetime-local" value={orderFilterDraft.createdFrom} onChange={event => { const value = event.currentTarget.value; setOrderFilterDraft(current => ({ ...current, createdFrom: value })) }} /></label>
          <label>至（本地时间）<input name="createdTo" type="datetime-local" value={orderFilterDraft.createdTo} onChange={event => { const value = event.currentTarget.value; setOrderFilterDraft(current => ({ ...current, createdTo: value })) }} /></label>
          <button className="secondary-button" type="submit">筛选</button><button className="text-button" type="button" onClick={() => { const cleared = { status: '', orderNo: '', createdFrom: '', createdTo: '' }; setOrderFilterDraft(cleared); applyOrderFilter(cleared) }}>清除</button>
        </form>
        <div className="order-cards">{orderRows.map(order => renderOrderCard(order))}{!orderRows.length && <div className="empty-state">{orderTotal ? '当前筛选没有匹配订单' : '暂无订单数据'}</div>}</div>
        {renderPager(orderPage, orderTotal, PAGE_SIZE, changeOrderPage)}
      </section>
    )
  }

  function OrderTable({ orders: rows, riders: rowRiders, compact = false }: { orders: DeliveryOrder[]; riders: Rider[]; compact?: boolean }) {
    return <div className={`table-wrap ${compact ? 'compact-table' : ''}`}><table><thead><tr><th>订单</th><th>商家 → 收货人</th><th>骑手</th><th>状态</th><th>配送费</th></tr></thead><tbody>{rows.map(order => <tr key={order.id}><td><strong className="order-no">{order.orderNo}</strong><small className="muted-text">#{order.id}</small></td><td><div className="route-cell"><strong>{order.merchantName}</strong><span>→ {order.receiverName}</span></div><small className="muted-text">{order.receiverAddress}</small></td><td>{rowRiders.find(rider => rider.id === order.riderId)?.name ?? (order.riderId ? <span className="muted-text">骑手#{order.riderId}</span> : <span className="muted-text">未分配</span>)}</td><td><StatusPill status={order.status} /></td><td className="fee">¥{Number(order.deliveryFee ?? 0).toFixed(2)}</td></tr>)}</tbody></table>{!rows.length && <div className="empty-state">暂无订单数据</div>}</div>
  }

  function renderOrderCard(order: DeliveryOrder) {
    const assignedRider = riderDirectory.find(rider => rider.id === order.riderId)
    const selectedRider = assignment[order.id] ?? ''
    const busy = busyOrderIds.has(order.id)
    const editingFee = editingFeeOrderId === order.id
    const showAdminControls = canManage
    return (
      <article className="order-card" key={order.id}>
        <div className="order-card-main">
          <div className="order-top">
            <div><strong className="order-no">{order.orderNo}</strong><span className="muted-text"> · {formatTime(order.createdAt, source === 'api')}</span></div>
            <StatusPill status={order.status} />
          </div>
          <div className="route-block">
            <div className="route-point pickup"><i>●</i><div><small>取货 / {order.merchantName}</small><strong>{order.merchantAddress}</strong></div></div>
            <div className="route-dash" />
            <div className="route-point dropoff"><i>●</i><div><small>收货 / {order.receiverName}</small><strong>{order.receiverAddress}</strong></div></div>
          </div>
        </div>
        <div className="order-card-side">
          <div className="order-meta">
            <span>配送费</span>
            {showAdminControls && editingFee ? (
              <FeeEditor
                initialValue={Number(order.deliveryFee ?? 0)}
                disabled={busy}
                onSave={value => handleUpdateFee(order, value)}
                onCancel={() => setEditingFeeOrderId(null)}
              />
            ) : (
              <div className="fee-display">
                <strong>¥{Number(order.deliveryFee ?? 0).toFixed(2)}</strong>
                {showAdminControls && <button className="fee-edit" disabled={busy} onClick={() => beginFeeEdit(order)}>修改</button>}
              </div>
            )}
          </div>
          <div className="order-actions">
            {order.status === 0 && <>
              <button className="primary-button" disabled={busy} onClick={() => handleDispatch(order)}>{busy ? '处理中…' : '自动派单'}</button>
              <div className="assign-row"><select value={selectedRider} onChange={event => { const value = event.currentTarget.value; setAssignment(current => ({ ...current, [order.id]: value })) }}><option value="">指定骑手</option>{availableRiders.map(rider => <option key={rider.id} value={rider.id}>{rider.name} · {rider.currentOrderCount}单</option>)}</select><button className="secondary-button" disabled={busy || !selectedRider} onClick={() => handleAssign(order)}>指定</button></div>
            </>}
            {order.status === 1 && <button className="primary-button" disabled={busy} onClick={() => handleTransition(order, 'start')}>{busy ? '处理中…' : '开始配送'}</button>}
            {order.status === 2 && <button className="primary-button" disabled={busy} onClick={() => handleTransition(order, 'complete')}>{busy ? '处理中…' : '标记完成'}</button>}
            {order.status === 0 && <button className="ghost-danger" disabled={busy} onClick={() => handleTransition(order, 'cancel')}>取消待派单</button>}
            {showAdminControls && <button className="ghost-danger delete-order" disabled={busy} onClick={() => void handleDelete(order)}>删除订单</button>}
          </div>
          {assignedRider && <div className="assigned-rider"><span className="avatar tiny">{assignedRider.name.slice(0, 1)}</span><span>当前骑手：<strong>{assignedRider.name}</strong></span></div>}
        </div>
      </article>
    )
  }

  function renderAccounts() {
    return <section className="panel page-panel"><div className="panel-heading"><div><span className="section-label">ACCESS CONTROL</span><h3>运营账号</h3><p className="panel-description">仅显示安全账号信息，不会返回或展示密码哈希。</p></div><button className="primary-button" onClick={() => setAccountDialogOpen(true)}>＋ 新建账号</button></div>
      <div className="table-wrap"><table><thead><tr><th>用户名</th><th>角色</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody>{accounts.map(account => <tr key={account.id}><td><strong>{account.username}</strong></td><td>{account.role === 'ADMIN' ? '管理员' : '调度员'}</td><td><span className={`availability ${account.enabled ? 'available' : 'disabled'}`}><i />{account.enabled ? '已启用' : '已禁用'}</span></td><td className="muted-text">{formatTime(account.createdAt, true)}</td><td>{account.username === user?.username && account.enabled ? <span className="muted-text">当前账号</span> : <button className="text-button" disabled={accountBusy} onClick={() => void handleAccountEnabled(account)}>{account.enabled ? '禁用' : '启用'}</button>}</td></tr>)}</tbody></table>{!accounts.length && <div className="empty-state">暂无账号记录</div>}</div>
    </section>
  }

  function renderLogs() {
    return <section className="panel page-panel"><div className="panel-heading"><div><span className="section-label">AUDIT TRAIL</span><h3>操作记录</h3><p className="panel-description">查看敏感业务变更及操作前后快照。</p></div><span className="count-badge">{logResult.total} 条记录</span></div>
      <form className="filter-bar" onSubmit={event => { event.preventDefault(); applyLogFilter(logFilterDraft) }}>
        <label>对象类型<input value={logFilterDraft.targetType} onChange={event => { const value = event.currentTarget.value; setLogFilterDraft(current => ({ ...current, targetType: value })) }} placeholder="ORDER / RIDER / ACCOUNT" /></label>
        <label>对象 ID<input value={logFilterDraft.targetId} onChange={event => { const value = event.currentTarget.value; setLogFilterDraft(current => ({ ...current, targetId: value })) }} /></label>
        <label>操作人<input value={logFilterDraft.actor} onChange={event => { const value = event.currentTarget.value; setLogFilterDraft(current => ({ ...current, actor: value })) }} /></label>
        <label>动作<input value={logFilterDraft.action} onChange={event => { const value = event.currentTarget.value; setLogFilterDraft(current => ({ ...current, action: value })) }} /></label>
        <button className="secondary-button" type="submit">筛选</button><button className="text-button" type="button" onClick={() => { const cleared = { targetType: '', targetId: '', actor: '', action: '' }; setLogFilterDraft(cleared); applyLogFilter(cleared) }}>清除</button>
      </form>
      <div className="log-list">{logResult.records.map(item => {
        const expanded = expandedLogs.has(item.id)
        return <article className="log-entry" key={item.id}><div className="log-entry-heading"><div><strong>{item.action}</strong><span>{item.targetType} #{item.targetId}</span><span>操作人：{item.actor}</span></div><small>{formatTime(item.createdAt, true)}</small></div>{item.reason && <p className="log-reason">原因：{item.reason}</p>}<button className="text-button" onClick={() => setExpandedLogs(current => { const next = new Set(current); if (next.has(item.id)) next.delete(item.id); else next.add(item.id); return next })}>{expanded ? '收起变更快照' : '展开变更快照'}</button>{expanded && <div className="log-snapshots"><div><small>变更前</small><pre>{formatSnapshot(item.beforeJson)}</pre></div><div><small>变更后</small><pre>{formatSnapshot(item.afterJson)}</pre></div></div>}</article>
      })}{!logResult.records.length && <div className="empty-state">暂无操作记录</div>}</div>
      {renderPager(logPage, logResult.total, PAGE_SIZE, changeLogPage)}
    </section>
  }

  const content = tab === 'overview' ? renderOverview() : tab === 'riders' ? renderRiders() : tab === 'orders' ? renderOrders() : tab === 'accounts' ? (isAdmin ? renderAccounts() : renderOverview()) : (isAdmin ? renderLogs() : renderOverview())

  return (
    <div className={`app app-${variant.toLowerCase()}`}>
      <div className="grain" />
      {variant === 'A' && <aside className="sidebar"><Brand /><div className="sidebar-label">WORKSPACE</div>{nav}<div className="sidebar-footer"><div className={`status-card ${source === 'demo' || connectionFailed ? 'status-demo' : ''}`}><span className="pulse" /><div><strong>{source === 'demo' ? '访客演示数据' : connectionFailed ? '连接异常' : '业务 API 最近已响应'}</strong><small>{source === 'demo' ? '本地操作不会写入数据库' : connectionFailed ? '当前数据可能已过期' : `最近同步 ${lastSyncLabel}`}</small></div></div><small>{user ? `${user.username} · ${user.role}` : '访客模式'}</small></div></aside>}
      <div className="main-shell">
        {variant !== 'A' && <div className="top-brand"><Brand /><div className="variant-caption">{variant === 'B' ? 'OPERATIONS BOARD' : 'FIELD DISPATCH / PROTOTYPE'}</div>{nav}</div>}
        {variant === 'A' && <div className="mobile-nav"><Brand />{nav}</div>}
        {header}
        {source === 'demo' && <div className="notice notice-warning"><span>演</span>访客演示模式：业务操作仅修改当前页面，不会读取或写入真实业务数据。<button className="text-button" onClick={() => { setLoginOpen(true); setLoginError('') }}>登录真实业务</button></div>}
        {error && <div className="notice notice-warning" role="alert"><span>!</span>{error}</div>}
        {notice && <div className={`notice ${noticeWarning ? 'notice-warning' : 'notice-info'}`} role="status"><span>{noticeWarning ? '!' : '✓'}</span>{notice}</div>}
        <main className="workspace">{authChecking || (loading && !dataUpdatedAt && Boolean(user)) ? <div className="loading-state"><span className="loader" /><p>{authChecking ? '正在检查登录状态…' : '正在连接调度服务…'}</p></div> : content}</main>
      </div>
      {showLayoutPicker && <div className="prototype-switcher"><button onClick={() => switchVariant(variant === 'A' ? 'C' : variant === 'B' ? 'A' : 'B')} aria-label="上一个布局">←</button><span><small>LAYOUT PROTOTYPE</small><strong>{variant} · {VARIANTS[variant]}</strong></span><button onClick={() => switchVariant(variant === 'A' ? 'B' : variant === 'B' ? 'C' : 'A')} aria-label="下一个布局">→</button></div>}
      {createOrderOpen && <CreateOrderDialog demoMode={source === 'demo'} submitting={creatingOrder} onClose={() => setCreateOrderOpen(false)} onSubmit={order => void handleCreateOrder(order)} />}
      {loginOpen && <LoginDialog submitting={loginSubmitting} error={loginError} onClose={() => setLoginOpen(false)} onSubmit={(username, password) => void handleLogin(username, password)} />}
      {riderDialog && <RiderDialog rider={riderDialog === 'new' ? null : riderDialog} submitting={riderBusy} onClose={() => setRiderDialog(null)} onSubmit={request => void handleRiderSave(request)} />}
      {accountDialogOpen && <div className="dialog-backdrop" onMouseDown={event => { if (event.target === event.currentTarget && !accountBusy) setAccountDialogOpen(false) }}><section className="create-order-dialog login-dialog" role="dialog" aria-modal="true" aria-labelledby="account-title"><div className="dialog-heading"><div><span className="section-label">NEW ACCOUNT</span><h2 id="account-title">创建运营账号</h2><p>密码只用于本次提交，不会在列表中展示。</p></div><button className="dialog-close" type="button" onClick={() => setAccountDialogOpen(false)} disabled={accountBusy} aria-label="关闭">×</button></div><form className="create-order-form" onSubmit={event => { event.preventDefault(); const form = new FormData(event.currentTarget); void handleAccountCreate(String(form.get('username') ?? '').trim(), String(form.get('password') ?? ''), String(form.get('role')) as AccountRole) }}><label>用户名<input name="username" required maxLength={100} autoComplete="off" /></label><label>初始密码<input name="password" required minLength={8} type="password" autoComplete="new-password" /></label><label>角色<select name="role"><option value="DISPATCHER">调度员</option><option value="ADMIN">管理员</option></select></label><div className="dialog-actions"><button className="secondary-button" type="button" onClick={() => setAccountDialogOpen(false)} disabled={accountBusy}>取消</button><button className="primary-button" type="submit" disabled={accountBusy}>{accountBusy ? '创建中…' : '创建账号'}</button></div></form></section></div>}
    </div>
  )
}

export default App
