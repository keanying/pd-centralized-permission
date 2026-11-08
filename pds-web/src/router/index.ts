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
      { path: 'orgs', name: 'orgs', component: () => import('@/views/Orgs.vue'), meta: { title: '架构树', subtitle: '组织即权限：节点权限向下继承，可阻断', perms: ['pds:org:view', 'pds:user:view'] } },
      { path: 'perm-tree', name: 'permTree', component: () => import('@/views/PermTree.vue'), meta: { title: '权限树', subtitle: '维护全部应用的权限节点，ID 自动生成', perms: ['pds:role:view'] } },
      { path: 'assignments', name: 'assignments', component: () => import('@/views/Assignments.vue'), meta: { title: '分配库', subtitle: '全部授权记录', perms: ['pds:authz:query'] } },
      { path: 'graph', name: 'graph', component: () => import('@/views/Graph.vue'), meta: { title: '权限图谱', subtitle: '人 — 组织 — 角色 — 权限 — 应用 关系', perms: ['pds:authz:query'] } },
      { path: 'users', redirect: '/orgs' },
      { path: 'groups', redirect: { path: '/orgs', query: { mode: 'group' } } },
      { path: 'roles', name: 'roles', component: () => import('@/views/SystemRoles.vue'), meta: { title: '系统角色', subtitle: '角色定义、继承与下发', perms: ['pds:role:view'] } },
      { path: 'resources', redirect: { path: '/assignments', query: { objectType: 'RESOURCE' } } },
      { path: 'user-grants', name: 'userGrants', component: () => import('@/views/UserGrants.vue'), meta: { title: '人员明细权限', perms: ['pds:grant:manage', 'pds:authz:query'] } },
      { path: 'apps', name: 'apps', component: () => import('@/views/Apps.vue'), meta: { title: '应用中心', subtitle: '接入 PDS 的全部系统：新增、编辑、删除应用，管理客户端', perms: ['pds:role:view'] } },
      { path: 'audit', name: 'audit', component: () => import('@/views/Audit.vue'), meta: { title: '审计中心', perms: ['pds:audit:view'] } },
      { path: 'me', name: 'me', component: () => import('@/views/Me.vue'), meta: { title: '个人设置' } },
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
