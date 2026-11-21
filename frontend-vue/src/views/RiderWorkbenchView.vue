<script setup lang="ts">
import { ref } from 'vue'
import http, { resetCsrf } from '../api/http'
import { session } from '../router'

/**
 * ============================================
 * 你的任务：把下面 4 个动作接上后端
 * ============================================
 *
 * 接口（都在 /api/v1 下，http 已经带了 baseURL）：
 *
 *   GET   /rider/me/profile          我的资料
 *   GET   /rider/me/orders           我的订单（参数 page, size, status）
 *   PATCH /rider/me/orders/{id}/accept    接单
 *   PATCH /rider/me/orders/{id}/start     开始配送
 *   PATCH /rider/me/orders/{id}/complete  送达
 *   POST  /rider/me/location         上报位置（body: { longitude, latitude }）
 *
 * 注意：所有接口都不传 riderId —— 后端从登录态解析。
 *
 * 完成后删掉下面这个 TODO 占位组件。
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
      <span class="brand">骑手端 · 工作台</span>
      <span class="right">
        {{ session.username }}
        <el-button link type="primary" @click="logout">退出</el-button>
      </span>
    </el-header>

    <el-main>
      <el-card v-loading="loading">
        <el-empty description="TODO：这里要实现——我的资料 / 我的订单 / 接单 / 开始配送 / 送达 / 上报位置" />
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
