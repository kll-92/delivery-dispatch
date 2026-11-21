import axios, { type AxiosError } from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一请求封装。做好三件后端要求的事：
 * 1. 带 cookie（session 认证）
 * 2. 写操作带 CSRF token，登录后 token 会轮换，需要刷新
 * 3. 401 / 403 / 429 分别给出不同提示
 */
const http = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
  withCredentials: true
})

let csrfHeaderName = 'X-CSRF-TOKEN'
let csrfToken = ''

/** 登录/登出后会话会重建，CSRF token 失效，必须重新取一次。 */
export async function resetCsrf(): Promise<void> {
  const { data } = await axios.get('/api/v1/auth/csrf', { withCredentials: true })
  csrfHeaderName = data.headerName
  csrfToken = data.token
}

http.interceptors.request.use(async (config) => {
  const method = (config.method ?? 'get').toLowerCase()
  const isWrite = method !== 'get' && method !== 'head' && method !== 'options'
  if (isWrite) {
    if (!csrfToken) await resetCsrf()
    config.headers[csrfHeaderName] = csrfToken
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<{ status?: number; message?: string }>) => {
    const status = error.response?.status
    const message = error.response?.data?.message
    const retryAfter = error.response?.headers?.['retry-after']

    switch (status) {
      case 401:
        ElMessage.error('登录已过期，请重新登录')
        if (!location.pathname.startsWith('/login')) {
          location.href = '/login'
        }
        break
      case 403:
        ElMessage.error(message ?? '没有权限执行此操作')
        break
      case 429:
        // 后端限流：必须按 Retry-After 等待，立刻重试只会继续被拒
        ElMessage.warning(message ?? `请求过于频繁，请 ${retryAfter ?? 1} 秒后重试`)
        break
      case 409:
        ElMessage.warning(message ?? '状态已变化，请刷新后重试')
        break
      default:
        ElMessage.error(message ?? '请求失败，请稍后再试')
    }
    return Promise.reject(error)
  }
)

export default http
