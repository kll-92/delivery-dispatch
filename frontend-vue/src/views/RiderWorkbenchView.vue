<script setup lang="ts">
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
  <el-container class="page">
    <!-- 顶栏：标题 + 用户名 + 退出 -->
    <el-header class="header">
      <span class="brand">骑手端 · 工作台</span>
      <span class="right">
        {{ session.username }}
        <el-button link type="primary" @click="logout">退出</el-button>
      </span>
    </el-header>

    <el-main>
      <!-- ① 资料卡片：profile 有值才显示（v-if），没值时显示"加载中" -->
      <el-card v-if="profile" class="profile-card">
        <div class="profile-row">
          <span>姓名：{{ profile.name }}</span>
          <span>工号：{{ profile.riderNo }}</span>
          <span>
            当前接单数：<b>{{ profile.currentOrderCount }}</b> / 3
          </span>
          <span class="muted">位置更新时间：{{ profile.lastLocationAt }}</span>
          <el-button type="primary" size="small" @click="reportLocation">上报位置</el-button>
        </div>
      </el-card>
      <el-card v-else>加载中...</el-card>

      <!-- ② 订单表格 -->
      <el-card class="orders-card">
        <el-table v-loading="loading" :data="orders" border>
          <el-table-column prop="orderNo" label="订单号" width="240" />
          <el-table-column prop="receiverName" label="收货人" width="100" />
          <el-table-column prop="receiverAddress" label="收货地址" show-overflow-tooltip />

          <!-- 状态列：用 v-if 决定显示什么按钮 -->
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="row.status === 3 ? 'success' : row.status === 4 ? 'info' : 'warning'">
                {{ STATUS_TEXT[row.status] ?? row.status }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button v-if="row.status === 0" size="small" type="primary" @click="accept(row.id)">接单</el-button>
              <el-button v-if="row.status === 1" size="small" type="warning" @click="start(row.id)">开始配送</el-button>
              <el-button v-if="row.status === 2" size="small" type="success" @click="complete(row.id)">送达</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          class="pager"
          @current-change="loadOrders"
          @size-change="loadOrders"
        />
      </el-card>
    </el-main>
  </el-container>
</template>

<style scoped>
.page {
  height: 100%;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.brand {
  font-weight: 600;
}
.right {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #606266;
}
.profile-card {
  margin-bottom: 16px;
}
.profile-row {
  display: flex;
  align-items: center;
  gap: 24px;
}
.muted {
  color: #909399;
  font-size: 13px;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
