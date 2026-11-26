import { createRouter, createWebHistory } from 'vue-router'
import http from '../api/http'

export type Role = 'ADMIN' | 'DISPATCHER' | 'MERCHANT' | 'RIDER'

/** 当前登录用户，路由守卫里填充，页面里可以直接读。 */
export const session = {
  username: '',
  role: '' as Role | ''
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/login' },
    {
      path: '/login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true }
    },
    {
      path: '/merchant',
      component: () => import('../views/MerchantOrdersView.vue'),
      meta: { roles: ['MERCHANT'] }
    },
    {
      path: '/rider',
      component: () => import('../views/RiderWorkbenchView.vue'),
      meta: { roles: ['RIDER'] }
    },
    {
      path: '/admin',
      component: () => import('../views/AdminOrdersView.vue'),
      meta: { roles: ['ADMIN', 'DISPATCHER'] }
    }
  ]
})

/**
 * 守卫做两件事：
 * 1. 没登录 → 去登录页
 * 2. 角色不匹配 → 回到自己该去的页面（防止商家手动输入 /admin 地址）
 *
 * 注意：这只是「体验层」的拦截，真正的权限在后端。前端路由永远不能当权限用。
 */
router.beforeEach(async (to) => {
  if (to.meta.public) return true

  try {
    const { data } = await http.get('/auth/me')
    session.username = data.username
    session.role = data.role as Role
  } catch {
    return '/login'
  }

  const allowedRoles = to.meta.roles as Role[] | undefined
  if (allowedRoles && !allowedRoles.includes(session.role)) {
    return homeOf(session.role)
  }
  return true
})

export function homeOf(role: Role | ''): string {
  switch (role) {
    case 'MERCHANT':
      return '/merchant'
    case 'RIDER':
      return '/rider'
    case 'ADMIN':
    case 'DISPATCHER':
      return '/admin'
    default:
      return '/login'
  }
}

export default router
