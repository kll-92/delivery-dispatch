<script setup lang="ts">
// ========== 1. 引入 ==========
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http, { resetCsrf } from '../api/http'
import { session } from '../router'

// ========== 2. 类型 ==========
interface Order {
  id: number
  orderNo: string
  merchantName: string
  receiverName: string
  receiverAddress: string
  status: number
  riderId: number | null
  deliveryFee: number
  createdAt: string
}

interface Rider {
  id: number
  riderNo: string
  name: string
  status: number
  currentOrderCount: number
}

// ========== 3. 状态 ==========
const loading = ref(false)
const orders = ref<Order[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const status = ref<number | undefined>(undefined)

const STATUS_TEXT: Record<number, string> = {
  0: '待派单',
  1: '已派单',
  2: '配送中',
  3: '已完成',
  4: '已取消'
}

const activeId = ref<number>()
const currentOrder = computed(() => orders.value.find(order => order.id === activeId.value))
const showDetails = ref(false)
const riders = ref<Rider[]>([])
const selectedRiderId = ref<number>()
const ridersLoading = ref(false)
const ridersFailed = ref(false)
const ordersFailed = ref(false)
const assigning = ref(false)
let ordersRequest = 0
let ridersRequest = 0

async function loadOrders() {
  const request = ++ordersRequest
  loading.value = true
  ordersFailed.value = false
  try {
    const { data } = await http.get('/orders/page', {
      params: { page: page.value, size: size.value, status: status.value }
    })
    if (request !== ordersRequest) return
    total.value = data.total
    const lastPage = Math.max(1, Math.ceil(total.value / size.value))
    if (page.value > lastPage) {
      page.value = lastPage
      return
    }
    orders.value = data.records
    if (!currentOrder.value) activeId.value = orders.value[0]?.id
  } catch {
    if (request === ordersRequest) {
      orders.value = []
      ordersFailed.value = true
    }
  } finally {
    if (request === ordersRequest) loading.value = false
  }
}

async function loadRiders() {
  const request = ++ridersRequest
  selectedRiderId.value = undefined
  riders.value = []
  ridersFailed.value = false
  ridersLoading.value = currentOrder.value?.status === 0
  if (!ridersLoading.value) return
  try {
    const { data } = await http.get('/riders/page', { params: { page: 1, size: 100, status: 1 } })
    if (request === ridersRequest) riders.value = data.records.filter((r: Rider) => r.currentOrderCount < 3)
  } catch {
    if (request === ridersRequest) ridersFailed.value = true
  } finally {
    if (request === ridersRequest) ridersLoading.value = false
  }
}

async function assign() {
  const order = currentOrder.value
  if (!order || order.status !== 0 || !selectedRiderId.value || assigning.value || loading.value) return
  assigning.value = true
  try {
    await http.patch(`/orders/${order.id}/assign/${selectedRiderId.value}`)
    ElMessage.success('派单成功')
  } catch {
    // The HTTP interceptor displays the server's reason, including capacity conflicts.
  } finally {
    await loadOrders()
    assigning.value = false
  }
}

const timeFormat = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })

function localTime(value: string) {
  if (!value) return ''
  const date = new Date(/(?:Z|[+-]\d{2}:\d{2})$/i.test(value) ? value : value + 'Z')
  return Number.isNaN(date.getTime()) ? '' : timeFormat.format(date)
}

function selectOrder(order: Order) {
  activeId.value = order.id
  showDetails.value = true
}

async function logout() {
  await resetCsrf()
  await http.post('/auth/logout')
  location.href = '/login'
}

watch([status, size], () => { page.value = 1 }, { flush: 'sync' })
watch([status, size, page], loadOrders)
watch(currentOrder, loadRiders)
onMounted(loadOrders)
</script>

<template>
  <main class="dispatch" :class="{ 'details-open': showDetails }">
    <aside class="rail">
      <span class="logo" aria-label="同城配送">◈</span>
      <span class="rail-mark" title="订单调度">⬡</span>
      <span class="account" :title="session.username">{{ session.username?.slice(0, 1) }}</span>
      <button class="logout" @click="logout">退出</button>
    </aside>
    <section class="inbox" aria-label="订单列表">
      <header>
        <h1>订单调度</h1>
        <button class="refresh" @click="loadOrders">刷新</button>
        <select v-model="status" aria-label="筛选订单状态">
          <option :value="undefined">全部状态</option>
          <option v-for="(label, code) in STATUS_TEXT" :key="code" :value="Number(code)">{{ label }}</option>
        </select>
      </header>
      <div v-loading="loading" class="queue">
        <button v-for="order in orders" :key="order.id" class="order"
          :class="{ selected: order.id === activeId }" :aria-pressed="order.id === activeId"
          @click="selectOrder(order)">
          <b>{{ order.merchantName }}</b>
          <span class="status" :class="'s' + order.status">{{ STATUS_TEXT[order.status] ?? order.status }}</span>
          <small :title="order.receiverAddress">送至 {{ order.receiverAddress }}</small>
          <time :datetime="order.createdAt">{{ localTime(order.createdAt) }}</time>
        </button>
        <p v-if="!orders.length && !loading" class="empty">{{ ordersFailed ? '订单加载失败，请刷新重试' : '暂无此状态的订单' }}</p>
      </div>
      <footer>
        <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total"
          :page-sizes="[10, 20, 50]" layout="sizes, prev, slot, next" size="small">
          <span>{{ page }} / {{ Math.max(1, Math.ceil(total / size)) }}</span>
        </el-pagination>
        <button class="mobile-logout" @click="logout">退出登录</button>
      </footer>
    </section>
    <section class="detail" aria-label="配送详情">
      <button class="back" @click="showDetails = false">← 返回订单</button>
      <template v-if="currentOrder">
        <p class="crumb">订单 <i>/</i> 配送详情</p>
        <h2>{{ currentOrder.merchantName }}</h2>
        <p class="number">订单号 · {{ currentOrder.orderNo }}</p>
        <img class="city" src="../assets/dispatch-city.jpg" alt="" />
        <div class="destination"><span class="pin" aria-hidden="true">⌖</span><div>
          <strong>{{ currentOrder.receiverAddress }}</strong><p>收件人 · {{ currentOrder.receiverName }}</p>
        </div></div>
        <template v-if="currentOrder.status === 0">
          <h3>可用骑手</h3>
          <div v-loading="ridersLoading" class="riders" role="radiogroup" aria-label="可用骑手">
            <label v-for="rider in riders" :key="rider.id" :class="{ picked: selectedRiderId === rider.id }">
              <input v-model="selectedRiderId" type="radio" name="rider" :value="rider.id" :disabled="assigning" />
              <span class="avatar">{{ rider.name.slice(0, 1) }}</span>
              <b>{{ rider.name }}</b><span>{{ rider.riderNo }}</span><small>已接 {{ rider.currentOrderCount }}/3</small>
            </label>
            <p v-if="!ridersLoading && !riders.length" class="empty">{{ ridersFailed ? '骑手加载失败，请刷新重试' : '暂无可用骑手' }}</p>
          </div>
          <button class="confirm" :disabled="!selectedRiderId || assigning || loading" @click="assign">{{ assigning ? '派单中…' : '确认派单' }}</button>
        </template>
        <p v-else class="result">{{ STATUS_TEXT[currentOrder.status] ?? currentOrder.status }} <span v-if="currentOrder.riderId"> · 骑手 ID {{ currentOrder.riderId }}</span></p>
      </template>
      <p v-else class="empty">请选择订单查看配送详情</p>
    </section>
  </main>
</template>

<style scoped>
.dispatch {
  display:grid;
  grid-template-columns:72px minmax(320px,35%) minmax(0,1fr);
  min-height:100vh;
  background:white;
  color:#202638;
  font:15px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei",sans-serif;
  text-align:left;
}
.dispatch * {
  box-sizing:border-box;
}
.dispatch button,.dispatch select {
  font:inherit;
  cursor:pointer;
}
.dispatch :focus-visible {
  outline:3px solid #667fff;
  outline-offset:2px;
}
.dispatch button:disabled {
  opacity:.55;
  cursor:default;
}
.rail {
  display:flex;
  align-items:center;
  flex-direction:column;
  padding:25px 0;
  background:#f1f4ff;
  gap:32px;
}
.logo {
  font-size:36px;
  color:#355aff;
  line-height:1;
}
.rail-mark,.account,.pin,.avatar {
  display:grid;
  place-items:center;
  color:#365bed;
  background:#e4eaff;
  border-radius:10px;
}
.rail-mark {
  width:44px;
  height:44px;
  font-size:30px;
}
.account {
  margin-top:auto;
  width:34px;
  height:34px;
  border-radius:50%;
}
.inbox {
  padding:0 10px;
  border-right:1px solid #e1e5ed;
}
.inbox header {
  height:100px;
  margin:0 8px;
  display:flex;
  align-items:center;
  justify-content:space-between;
  border-bottom:1px solid #ebedf3;
}
.dispatch h1 {
  font-size:26px;
  font-weight:650;
  margin:0;
  color:#202638;
}
.inbox select {
  border:1px solid #dce0eb;
  border-radius:7px;
  color:#505769;
  background:white;
  padding:6px;
}
.order {
  display:grid;
  width:100%;
  grid-template-columns:minmax(0,1fr) auto;
  gap:7px 10px;
  padding:22px 16px;
  text-align:left;
  border:0;
  border-bottom:1px solid #ecedf1;
  background:white;
  align-items:center;
}
.order:hover {
  background:#f6f8ff;
}
.order.selected {
  background:#edf0ff;
  box-shadow:inset 3px 0 #435fff;
  border-radius:5px;
}
.order b {
  font-weight:600;
}
.order small,.order time,.number,.crumb,.destination p,.riders small {
  color:#677185;
}
.order small {
  overflow:hidden;
  text-overflow:ellipsis;
  white-space:nowrap;
}
.order time,.status {
  font-size:13px;
  text-align:right;
}
.status:before {
  content:'';
  display:inline-block;
  width:7px;
  height:7px;
  border-radius:50%;
  margin-right:6px;
  background:#aab1bf;
}
.s0:before {
  background:#5074ff;
}
.s1:before {
  background:#7d65ca;
}
.s2:before {
  background:#df932b;
}
.detail {
  padding:30px clamp(20px,3vw,52px) 38px;
  max-width:1100px;
}
.crumb,.number,.destination p {
  margin:0;
}
.crumb i {
  padding:0 8px;
  color:#abb0bc;
}
.detail h2 {
  font-size:clamp(25px,2.3vw,36px);
  font-weight:620;
  letter-spacing:-.035em;
  margin:26px 0 4px;
}
.city {
  display:block;
  width:100%;
  max-height:340px;
  object-fit:cover;
  border-radius:8px;
  margin:24px 0 18px;
}
.destination,.riders {
  border:1px solid #e2e6f0;
  border-radius:9px;
  overflow:hidden;
}
.destination {
  display:flex;
  gap:15px;
  align-items:center;
  padding:17px;
  background:#fafbff;
}
.destination strong {
  font-size:18px;
  font-weight:600;
}
.pin {
  height:46px;
  width:46px;
  font-size:28px;
  flex-shrink:0;
}
.destination p {
  font-size:14px;
  margin-top:2px;
}
.detail h3 {
  font-size:18px;
  margin:24px 0 10px;
  font-weight:600;
}
.riders label {
  display:flex;
  align-items:center;
  gap:13px;
  padding:13px 16px;
  cursor:pointer;
}
.riders label+label {
  border-top:1px solid #e8ebf0;
}
.picked {
  background:#eef1ff;
}
.riders input {
  accent-color:#4967ff;
}
.avatar {
  height:32px;
  width:32px;
  border-radius:50%;
}
.riders small {
  margin-left:auto;
}
.confirm {
  background:#4261ef;
  color:white;
  border:0;
  border-radius:8px;
  margin-top:20px;
  padding:11px 30px;
  font-weight:600;
}
.result,.empty {
  padding:22px;
  color:#586782;
}
.back {
  display:none;
}
@media(max-width:760px) {
  .dispatch {
    grid-template-columns:1fr;
  }
  .rail,.detail {
    display:none;
  }
  .details-open .detail {
    display:block;
  }
  .details-open .inbox {
    display:none;
  }
  .inbox {
    border:0;
  }
  .inbox header {
    height:76px;
  }
  .back {
    display:inline-block;
    background:white;
    border:0;
    color:#4261ef;
    padding:4px 0;
    margin-bottom:20px;
  }
  .detail {
    padding:18px;
  }
  .crumb {
    display:none;
  }
  .detail h2 {
    margin-top:0;
  }
  .city {
    max-height:190px;
  }
}
.dispatch {
  height:100dvh;
  min-height:0;
}
.rail {
  gap:20px;
}
.logout,.refresh,.mobile-logout {
  color:#556480;
  background:transparent;
  border:0;
}
.refresh {
  margin-left:auto;
  margin-right:8px;
}
.inbox {
  display:flex;
  flex-direction:column;
  min-height:0;
}
.inbox header {
  flex:none;
}
.queue {
  flex:1;
  min-height:64px;
  overflow:auto;
}
.inbox footer {
  display:flex;
  align-items:center;
  justify-content:space-between;
  border-top:1px solid #e5e9f2;
  padding:14px 4px;
}
.detail {
  overflow:auto;
  width:100%;
}
.riders {
  min-height:64px;
  max-height:320px;
  overflow:auto;
}
.riders label span:not(.avatar) {
  color:#677185;
  font-size:13px;
}
.dispatch .order b {
  overflow-wrap:anywhere;
}
.mobile-logout {
  display:none;
}
.order:focus-visible {
  outline-offset:-3px;
}
@media(max-width:760px) {
  .mobile-logout {
    display:inline-block;
  }
  .dispatch.details-open .inbox {
    display:none;
  }
  .dispatch .inbox header {
    gap:6px;
  }
  .queue {
    min-height:0;
  }
}
</style>
