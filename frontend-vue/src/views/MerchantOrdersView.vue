<script setup lang="ts">
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
  <el-container class="page">
    <el-header class="header">
      <span class="brand">商家端 · 我的订单</span>
      <span class="right">
        {{ session.username }}
        <el-button link type="primary" @click="logout">退出</el-button>
      </span>
    </el-header>

    <el-main>
      <el-card>
        <div class="toolbar">
          <el-select v-model="status" placeholder="全部状态" clearable style="width: 150px" @change="loadOrders">
            <el-option v-for="(text, code) in STATUS_TEXT" :key="code" :label="text" :value="Number(code)" />
          </el-select>
          <el-button type="primary" @click="dialogVisible = true">新建订单</el-button>
        </div>

        <el-table v-loading="loading" :data="orders" border style="margin-top: 12px">
          <el-table-column prop="orderNo" label="订单号" width="240" />
          <el-table-column prop="receiverName" label="收货人" width="100" />
          <el-table-column prop="receiverAddress" label="收货地址" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 3 ? 'success' : row.status === 4 ? 'info' : 'warning'">
                {{ STATUS_TEXT[row.status] ?? row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="deliveryFee" label="配送费" width="100" />
          <el-table-column prop="createdAt" label="创建时间" width="180" />
        </el-table>

        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          style="margin-top: 16px; justify-content: flex-end"
          @current-change="loadOrders"
          @size-change="loadOrders"
        />
      </el-card>
    </el-main>

    <el-dialog v-model="dialogVisible" title="新建订单" width="480px">
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
.toolbar {
  display: flex;
  justify-content: space-between;
}
</style>
