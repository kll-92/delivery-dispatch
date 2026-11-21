<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http, { resetCsrf } from '../api/http'
import { homeOf, session, type Role } from '../router'

const router = useRouter()
const username = ref('shop_blacktea')
const password = ref('Passw0rd!')
const loading = ref(false)

async function submit() {
  if (!username.value || !password.value) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    // ① 登录前取 CSRF（POST 请求必须带）
    await resetCsrf()
    const { data } = await http.post('/auth/login', {
      username: username.value,
      password: password.value
    })
    session.username = data.username
    session.role = data.role
    // ② 登录后会话重建，CSRF token 已轮换，必须重新取一次
    await resetCsrf()
    ElMessage.success(`欢迎，${data.username}`)
    router.replace(homeOf(data.role as Role))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <template #header>
        <div class="title">同城配送调度平台</div>
      </template>

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="username" placeholder="shop_blacktea / rider_zhang / admin" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password @keyup.enter="submit" />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width: 100%" @click="submit">
          登录
        </el-button>
      </el-form>

      <div class="hint">
        商家：shop_blacktea / Passw0rd!<br />
        骑手：rider_zhang / Passw0rd!<br />
        平台：admin / admin12345
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-card {
  width: 380px;
}
.title {
  font-size: 18px;
  font-weight: 600;
}
.hint {
  margin-top: 16px;
  font-size: 12px;
  color: #909399;
  line-height: 1.8;
}
</style>
