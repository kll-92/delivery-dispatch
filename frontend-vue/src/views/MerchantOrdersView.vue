<script setup lang="ts">
import RoleWorkspace from "../components/RoleWorkspace.vue"
import { localDateTime } from "./deliveryDisplay"
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http, { resetCsrf } from '../api/http'
import { session } from '../router'

interface Order {
  id: number
  orderNo: string
  receiverName: string
  receiverAddress: string
  status: number
  deliveryFee: number
  createdAt: string
}

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

/** 加载「我的订单」。注意：不传 merchantId —— 后端从登录态解析。 */
async function loadOrders() {
  loading.value = true
  try {
    const { data } = await http.get('/merchant/me/orders', {
      params: { page: page.value, size: size.value, status: status.value }
    })
    orders.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

// ---------- 新建订单 ----------
const dialogVisible = ref(false)
const submitting = ref(false)
const form = ref({
  receiverName: '',
  receiverPhone: '',
  receiverAddress: '',
  receiverLongitude: 113.95,
  receiverLatitude: 22.54,
  deliveryFee: 5
})

async function submitOrder() {
  submitting.value = true
  try {
    await http.post('/merchant/me/orders', form.value)
    ElMessage.success('下单成功')
    dialogVisible.value = false
    form.value = {
      receiverName: '',
      receiverPhone: '',
      receiverAddress: '',
      receiverLongitude: 113.95,
      receiverLatitude: 22.54,
      deliveryFee: 5
    }
    page.value = 1
    await loadOrders()
  } finally {
    submitting.value = false
  }
}

async function logout() {
  await resetCsrf()
  await http.post('/auth/logout')
  location.href = '/login'
}

onMounted(loadOrders)
</script>

<template>
  <RoleWorkspace role-label="商家端" @logout="logout">
    <header class="hero panel">
      <div class="hero-copy"><h1>我的订单</h1><p class="subtitle">本店配送订单 · 共 {{ total }} 笔{{ status !== undefined ? '符合筛选' : '' }}</p>
        <el-button type="primary" size="large" @click="dialogVisible = true">＋ 新建订单</el-button>
      </div>
      <img src="../assets/dispatch-city.jpg" alt="" />
    </header>
    <section class="panel ledger" aria-label="配送订单">
      <div class="ledger-head"><h2>配送订单</h2>
        <select v-model="status" aria-label="筛选订单状态" @change="page = 1; loadOrders()">
          <option :value="undefined">全部状态</option>
          <option v-for="(text, code) in STATUS_TEXT" :key="code" :value="Number(code)">{{ text }}</option>
        </select>
      </div>
      <div v-loading="loading" class="rows">
        <div class="ledger-row labels"><span>配送地址 / 收货人</span><span>创建时间 / 订单号</span><span>配送费</span><span>状态</span></div>
        <article v-for="order in orders" :key="order.id" class="ledger-row">
          <div><strong>{{ order.receiverAddress }}</strong><small>{{ order.receiverName }}</small></div>
          <div class="order-id"><time>{{ localDateTime(order.createdAt) }}</time><small>{{ order.orderNo }}</small></div>
          <span class="fee">¥ {{ Number(order.deliveryFee).toFixed(2) }}</span>
          <span class="state" :data-state="order.status">{{ STATUS_TEXT[order.status] ?? order.status }}</span>
        </article>
        <p v-if="!loading && !orders.length" class="empty">暂无符合条件的订单</p>
      </div>
      <el-pagination class="pager" v-model:current-page="page" v-model:page-size="size" :page-sizes="[10,20,50]"
        :total="total" :pager-count="5" layout="sizes, prev, pager, next" @current-change="loadOrders" @size-change="loadOrders" />
    </section>
    <el-dialog v-model="dialogVisible" title="新建订单" width="min(480px, 94vw)">
      <el-form label-width="90px">
        <el-form-item label="收货人">
          <el-input v-model="form.receiverName" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="form.receiverPhone" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.receiverAddress" />
        </el-form-item>
        <el-form-item label="经度">
          <el-input-number v-model="form.receiverLongitude" :precision="4" :step="0.001" />
        </el-form-item>
        <el-form-item label="纬度">
          <el-input-number v-model="form.receiverLatitude" :precision="4" :step="0.001" />
        </el-form-item>
        <el-form-item label="配送费">
          <el-input-number v-model="form.deliveryFee" :precision="2" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitOrder">提交</el-button>
      </template>
    </el-dialog>
  </RoleWorkspace>
</template>

<style scoped src="./rolePages.css"></style>
<style scoped>
.hero { display:flex; align-items:stretch; overflow:hidden; background:#f0f4ff; margin-bottom:24px; }
.hero-copy { flex:1; padding:28px 32px; align-self:center; }
.hero-copy p { margin:7px 0 20px; }
.hero img { width:46%; object-fit:cover; object-position:center 48%; max-height:195px; mask-image:linear-gradient(90deg,transparent,black 22%); }
.ledger { overflow:hidden; }
.ledger-head { display:flex; align-items:center; justify-content:space-between; padding:20px 24px; }
select { padding:7px 10px; border-radius:7px; border:1px solid #d7dfee; background:white; color:#43516b; }
.ledger-row { display:grid; grid-template-columns:minmax(180px,1.4fr) minmax(200px,1fr) 85px 78px; align-items:center; gap:16px; padding:15px 24px; border-bottom:1px solid #ecf0f5; font-size:14px; }
.ledger-row.labels { padding-block:9px; background:#f7f9fc; color:#64738b; font-size:12px; }
.ledger-row small { display:block; color:#697991; margin-top:5px; font-size:12px; overflow-wrap:anywhere; }
.fee,time { font-variant-numeric:tabular-nums; }
@media(max-width:760px) {
 .hero-copy { padding:20px; } .hero img { width:33%; }
 .ledger-row { grid-template-columns:1fr auto; gap:10px; padding:13px 16px; }
 .labels { display:none; } .order-id { grid-column:1; grid-row:2; }
 .fee { grid-column:2; grid-row:2; } .state { grid-column:2; grid-row:1; }
 .ledger-head { padding:16px; }
}
</style>
