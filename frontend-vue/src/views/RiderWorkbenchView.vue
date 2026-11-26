<script setup lang="ts">
import RoleWorkspace from "../components/RoleWorkspace.vue"
import { localDateTime } from "./deliveryDisplay"
// ========== 1. 引入需要的东西 ==========
import { onMounted, ref } from 'vue'                 // onMounted：组件挂载后执行一次；ref：响应式变量
import { ElMessage } from 'element-plus'            // 弹提示用
import http, { resetCsrf } from '../api/http'       // 封装好的请求工具
import { session } from '../router'                 // 当前登录用户信息

// ========== 2. 类型说明（只给编辑器看，运行时不存在）==========
interface RiderProfile {
  id: number
  riderNo: string
  name: string
  phone: string
  status: number
  currentOrderCount: number
  lastLocationAt: string
}

interface Order {
  id: number
  orderNo: string
  receiverName: string
  receiverAddress: string
  status: number
  deliveryFee: number
}

// ========== 3. 响应式变量 ==========
const loading = ref(false)
const profile = ref<RiderProfile | null>(null)   // 初始是 null，表示还没加载
const orders = ref<Order[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

// 订单状态码 → 中文
const STATUS_TEXT: Record<number, string> = {
  0: '待派单',
  1: '已派单',
  2: '配送中',
  3: '已完成',
  4: '已取消'
}

// ========== 4. 加载数据 ==========
async function loadProfile() {
  const res = await http.get('/rider/me/profile')
  profile.value = res.data
}

async function loadOrders() {
  loading.value = true
  try {
    const res = await http.get('/rider/me/orders', {
      params: { page: page.value, size: size.value }
    })
    orders.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false      // 不管成功失败，都要把 loading 关掉
  }
}

async function logout() {
  await resetCsrf()
  await http.post('/auth/logout')
  location.href = '/login'
}

// ========== 5. 组件挂载后自动加载一次 ==========
onMounted(async () => {
  await loadProfile()
  await loadOrders()
})

// ============================================================
// 你要实现 4 个函数，然后在上面的表格里加按钮调用它们
//
//   accept(orderId)    → PATCH /rider/me/orders/{id}/accept
//   start(orderId)     → PATCH /rider/me/orders/{id}/start
//   complete(orderId)  → PATCH /rider/me/orders/{id}/complete
//   reportLocation()   → POST  /rider/me/location
//                         body: { longitude: 113.95, latitude: 22.54 }
//
// 每个函数写完都要刷新列表：await loadOrders()
const accepting = ref(false)
async function accept(orderId:number){
  if(accepting.value) return
  accepting.value = true
  try {
    await http.patch(`/rider/me/orders/${orderId}/accept`)
    ElMessage.success('接单成功')
    await loadOrders()
  } catch(e) {
    ElMessage.error('接单失败');
  } finally {
    accepting.value=false
  }
  
}
const starting =ref(false)
async function start(orderId:number){
  if(starting.value) return 
  starting.value=true
  try {
    await http.patch(`/rider/me/orders/${orderId}/start`)
    ElMessage.success('开始成功')
    await loadOrders()
  } catch(e) {
    ElMessage.error('开始失败')
  } finally {
    starting.value=false
  }
}
const completing = ref(false)
async function complete(orderId: number) {
  if (completing.value) return
  completing.value = true
  try {
    await http.patch(`/rider/me/orders/${orderId}/complete`)
    ElMessage.success('已送达')
    await loadOrders()
    await loadProfile()          // 送达会释放名额，接单数要跟着变
  } catch {
    ElMessage.error('送达失败')
  } finally {
    completing.value = false
  }
}

const reporting = ref(false)
async function reportLocation() {
  if (reporting.value) return
  reporting.value = true
  try {
    // POST 的第二个参数就是请求体，不能包 params
    await http.post('/rider/me/location', { longitude: 113.95, latitude: 22.54 })
    ElMessage.success('位置已上报')
    await loadProfile()
  } catch {
    ElMessage.error('上报失败')
  } finally {
    reporting.value = false
  }
}

// ============================================================
</script>

<template>
  <RoleWorkspace role-label="骑手端" @logout="logout">
    <header class="title-row"><div><h1>配送工作台</h1><p class="subtitle">我的配送任务</p></div></header>
    <div class="rider-layout">
      <aside class="profile panel" v-if="profile">
        <div class="profile-head"><span class="avatar">{{ profile.name.slice(0,1) }}</span><div>
          <h2>{{ profile.name }}</h2><span class="meta">骑手 · {{ profile.riderNo }}</span>
        </div></div>
        <div class="capacity"><span class="meta">当前接单数</span><strong>{{ profile.currentOrderCount }} <small>/ 3</small></strong>
          <div class="meter" aria-hidden="true"><i v-for="n in 3" :key="n" :class="{ used: n <= profile.currentOrderCount }" /></div>
        </div>
        <span class="meta">上次位置更新</span><p class="location">{{ localDateTime(profile.lastLocationAt) }}</p>
        <el-button plain type="primary" :loading="reporting" @click="reportLocation">上报位置</el-button>
      </aside>
      <aside v-else class="panel empty">正在读取骑手资料…</aside>
      <section class="tasks panel" v-loading="loading">
        <div class="tasks-head"><h2>配送任务</h2><span class="meta">共 {{ total }} 笔</span></div>
        <article v-for="order in orders" :key="order.id" class="task">
          <span class="state" :data-state="order.status">{{ STATUS_TEXT[order.status] ?? order.status }}</span>
          <strong>{{ order.receiverAddress }}</strong><p>{{ order.receiverName }} 收</p><small>{{ order.orderNo }}</small>
          <div class="action">
            <el-button v-if="order.status === 0" type="primary" :disabled="accepting" @click="accept(order.id)">接单</el-button>
            <el-button v-if="order.status === 1" type="primary" :disabled="starting" @click="start(order.id)">开始配送</el-button>
            <el-button v-if="order.status === 2" type="success" :disabled="completing" @click="complete(order.id)">确认送达</el-button>
          </div>
        </article>
        <p v-if="!loading && !orders.length" class="empty">暂无配送任务</p>
        <el-pagination class="pager" v-model:current-page="page" v-model:page-size="size" :page-sizes="[10,20,50]"
          :total="total" :pager-count="5" layout="sizes, prev, pager, next" @current-change="loadOrders" @size-change="loadOrders" />
      </section>
    </div>
  </RoleWorkspace>
</template>

<style scoped src="./rolePages.css"></style>
<style scoped>
.title-row .subtitle { margin-top:4px; }
.rider-layout { display:grid; grid-template-columns:250px minmax(0,1fr); gap:22px; align-items:start; }
.profile { padding:22px; background:linear-gradient(135deg,#ecf1ff 0%,white 70%); }
.profile-head { display:flex; align-items:center; gap:12px; }
.avatar { display:grid; place-items:center; width:42px; height:42px; color:#425fee; background:#dce5ff; border-radius:13px; font-weight:650; }
.capacity { display:grid; gap:8px; margin:24px 0; }
.capacity strong { font-size:32px; }.capacity small { color:#78879e; font-size:18px; }
.meter { display:flex; gap:5px; }.meter i { flex:1; height:5px; border-radius:4px; background:#dce2ee; }.meter .used { background:#627cf2; }
.location { font-size:14px; margin:3px 0 14px; }
.tasks-head { display:flex; justify-content:space-between; align-items:center; padding:17px 22px; border-bottom:1px solid #e7ebf2; }
.task { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:4px 12px; padding:15px 22px; border-bottom:1px solid #e7ebf2; }
.task strong,.task p,.task small { grid-column:1; overflow-wrap:anywhere; }
.task strong { font-size:17px; }.task p { color:#495b76; font-size:14px; }.task small { color:#748097; font-size:12px; }
.action { grid-column:2; grid-row:2 / span 2; align-self:center; }
@media(max-width:760px) {
 .rider-layout { grid-template-columns:minmax(0,1fr); gap:14px; }.profile { padding:16px; display:grid; grid-template-columns:1fr auto; gap:6px 16px; align-items:center; }
 .capacity { margin:0; grid-row:1 / span 2; grid-column:2; }.capacity strong { font-size:23px; }
 .profile-head { grid-column:1; }.location { margin:0; }.profile button { justify-self:end; grid-column:2;grid-row:3; }
 .task,.tasks-head { padding:14px 16px; }.title-row { margin-bottom:14px; }
}
</style>
