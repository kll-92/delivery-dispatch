<script setup lang="ts">
// ========== 1. 引入 ==========
import { onMounted, ref } from 'vue'
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

// ========== 4. 加载订单 ==========
async function loadOrders() {
  loading.value = true
  try {
    const res = await http.get('/orders/page', {
      params: { page: page.value, size: size.value, status: status.value }
    })
    orders.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

// ========== 5. 派单弹窗用到的数据 ==========
const dialogVisible = ref(false)
const currentOrder = ref<Order | null>(null)
const riders = ref<Rider[]>([])
const selectedRiderId = ref<number | null>(null)
const assigning = ref(false)

/** 打开派单弹窗：记下当前订单，并加载可选骑手 */
async function openAssignDialog(order: Order) {
  currentOrder.value = order
  selectedRiderId.value = null
  dialogVisible.value = true
  const res = await http.get('/riders/page', { params: { page: 1, size: 50 } })
  riders.value = res.data.records
}

// ============================================================
// TODO：你来实现 assign()，然后把它接到弹窗的"确定派单"按钮上
//
// 接口：PATCH /orders/{orderId}/assign/{riderId}
//
// 步骤：
//   1. 检查有没有选骑手、有没有当前订单，没有就 return
//   2. assigning.value = true
//   3. try 里 await http.patch(`/orders/${currentOrder.value.id}/assign/${selectedRiderId.value}`)
//      然后 ElMessage.success('派单成功')
//      然后关闭弹窗、刷新列表
//   4. catch 里 ElMessage.error('派单失败')
//   5. finally 里 assigning.value = false
//
// 注意：currentOrder.value 可能是 null，TypeScript 会提示，
//      用 if (!currentOrder.value || !selectedRiderId.value) return 先挡掉
// ============================================================
async function assign() {
  if(!currentOrder.value||!selectedRiderId.value) return
  assigning.value = true
  try {
    await http.patch(`/orders/${currentOrder.value.id}/assign/${selectedRiderId.value}`)
    ElMessage.success('派单成功')
    dialogVisible.value=false
    await loadOrders()
  } catch {
    ElMessage.error('派单失败')
  } finally {
    assigning.value = false
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
      <span class="brand">平台端 · 订单调度</span>
      <span class="right">
        {{ session.username }}
        <el-button link type="primary" @click="logout">退出</el-button>
      </span>
    </el-header>

    <el-main>
      <el-card>
        <!-- 工具栏 -->
        <div class="toolbar">
          <el-select
            v-model="status"
            placeholder="全部状态"
            clearable
            style="width: 150px"
            @change="loadOrders"
          >
            <el-option
              v-for="(text, code) in STATUS_TEXT"
              :key="code"
              :label="text"
              :value="Number(code)"
            />
          </el-select>
          <el-button @click="loadOrders">刷新</el-button>
        </div>

        <!-- 订单表格 -->
        <el-table v-loading="loading" :data="orders" border style="margin-top: 12px">
          <el-table-column prop="orderNo" label="订单号" width="240" />
          <el-table-column prop="merchantName" label="商家" width="140" />
          <el-table-column prop="receiverName" label="收货人" width="100" />
          <el-table-column prop="receiverAddress" label="收货地址" show-overflow-tooltip />

          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 3 ? 'success' : row.status === 4 ? 'info' : 'warning'">
                {{ STATUS_TEXT[row.status] ?? row.status }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="骑手" width="90">
            <template #default="{ row }">
              {{ row.riderId ?? '—' }}
            </template>
          </el-table-column>

          <!--
            TODO（第二件事）：操作列
            只有 status === 0（待派单）才显示「派单」按钮，
            点击时调用 openAssignDialog(row)
          -->
        <el-table-column label='操作' width='120'>
      
          <template #default="{row}">
            <el-button v-if="row.status==0" size="small" type="primary" @click="openAssignDialog(row)">派单</el-button>
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

      <!-- 派单弹窗 -->
      <el-dialog v-model="dialogVisible" title="手动派单" width="460px">
        <el-form label-width="80px">
          <el-form-item label="订单号">
            {{ currentOrder?.orderNo }}
          </el-form-item>
          <el-form-item label="选择骑手">
            <el-select v-model="selectedRiderId" placeholder="请选择骑手" style="width: 100%">
              <el-option
                v-for="rider in riders"
                :key="rider.id"
                :label="`${rider.name}（${rider.riderNo}） 已接 ${rider.currentOrderCount}/3`"
                :value="rider.id"
              />
            </el-select>
          </el-form-item>
        </el-form>

        <template #footer>
          <el-button @click="dialogVisible = false">取消</el-button>
          <!--
            TODO（第三件事）：把这个按钮的 @click 接上 assign
            参考：<el-button type="primary" :loading="assigning" @click="assign">确定派单</el-button>
          -->
          <el-button type="primary" :loading="assigning" @click="assign">确定派单</el-button>
        </template>
      </el-dialog>
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
.toolbar {
  display: flex;
  justify-content: space-between;
}
.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
