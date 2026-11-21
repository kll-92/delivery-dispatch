<script setup lang="ts">
import { ref } from 'vue'
import http, { resetCsrf } from '../api/http'
import { session } from '../router'

/**
 * ============================================
 * 你的任务：实现平台端订单列表与派单
 * ============================================
 *
 * 接口：
 *   GET   /orders/page?page=&size=&status=     订单分页
 *   PATCH /orders/{orderId}/assign/{riderId}   手动派单
 *   GET   /riders/nearby?longitude=&latitude=&radiusKm=&limit=  附近骑手
 *
 * 提示：先做「列表 + 分页」，再做「派单」。别一次写两个。
 * 表格列可以直接抄 MerchantOrdersView.vue 的写法。
 */

const loading = ref(false)

async function logout() {
  await resetCsrf()
  await http.post('/auth/logout')
  location.href = '/login'
}
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
      <el-card v-loading="loading">
        <el-empty description="TODO：订单分页列表 + 手动派单" />
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
</style>
