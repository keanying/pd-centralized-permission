import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { getTokens, login } from '@/auth/oidc'
import { useSession } from '@/stores/session'
import AppLayout from '@/layout/AppLayout.vue'

/** meta.perms：满足其一即可访问（与后端 PDS 权限点一致） */
const routes: RouteRecordRaw[] = [
  { path: '/auth/callback', component: () => import('@/views/AuthCallback.vue'), meta: { public: true } },
  {
    path: '/',
    component: AppLayout,
    children: [
      { path: '', name: 'dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '工作台', perms: ['pds:console:access'] } },
      { path: 'orgs', name: 'orgs', component: () => import('@/views/Orgs.vue'), meta: { title: '组织架构', perms: ['pds:org:view'] } },
      { path: 'users', name: 'users', component: () => import('@/views/Users.vue'), meta: { title: '人员管理', perms: ['pds:user:view'] } },
      { path: 'groups', name: 'groups', component: () => import('@/views/Groups.vue'), meta: { title: '用户组', perms: ['pds:user:view'] } },
      { path: 'roles', name: 'roles', component: () => import('@/views/Roles.vue'), meta: { title: '角色管理', perms: ['pds:role:view'] } },
      { path: 'user-grants', name: 'userGrants', component: () => import('@/views/UserGrants.vue'), meta: { title: '人员明细权限', perms: ['pds:grant:manage', 'pds:authz:query'] } },
      { path: 'resources', name: 'resources', component: () => import('@/views/Resources.vue'), meta: { title: '资源授权', perms: ['pds:role:view'] } },
      { path: 'apps', name: 'apps', component: () => import('@/views/Apps.vue'), meta: { title: '应用管理', perms: ['pds:role:view'] } },
      { path: 'audit', name: 'audit', component: () => import('@/views/Audit.vue'), meta: { title: '审计中心', perms: ['pds:audit:view'] } },
      { path: 'me', name: 'me', component: () => import('@/views/Me.vue'), meta: { title: '个人中心' } },
      { path: 'forbidden', name: 'forbidden', component: () => import('@/views/Forbidden.vue'), meta: { title: '无权访问' } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export const router = createRouter({ history: createWebHistory(import.meta.env.BASE_URL), routes })

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  if (!getTokens()) {
    await login(import.meta.env.BASE_URL.replace(/\/$/, '') + to.fullPath)
    return false
  }
  const session = useSession()
  if (!session.me) await session.load()
  const perms = to.meta.perms as string[] | undefined
  if (perms && !session.has(...perms)) {
    return to.name === 'dashboard' ? { name: 'me' } : { name: 'forbidden' }
  }
  return true
})
