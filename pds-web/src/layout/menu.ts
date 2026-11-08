import type { Component } from 'vue'
import { IconHome, IconGridView, IconUserSetting, IconLock, IconCheckList } from '@kousum/semi-icons-vue'
import OrgChartIcon from '@/components/icons/OrgChartIcon.vue'

export interface MenuItem {
  path: string
  title: string
  icon: Component
  perms: string[]
  /** 子路径也视为选中 */
  match?: string[]
}

/** 与设计稿侧栏一致：工作台、组织架构与人员、应用、人员明细权限、角色与授权、审计中心（设置在底部） */
export const menus: MenuItem[] = [
  { path: '/', title: '工作台', icon: IconHome, perms: ['pds:console:access'] },
  { path: '/orgs', title: '组织架构与人员', icon: OrgChartIcon, perms: ['pds:org:view', 'pds:user:view'] },
  { path: '/apps', title: '应用管理', icon: IconGridView, perms: ['pds:role:view'] },
  { path: '/user-grants', title: '人员明细权限', icon: IconUserSetting, perms: ['pds:grant:manage', 'pds:authz:query'] },
  { path: '/roles', title: '角色与授权', icon: IconLock, perms: ['pds:role:view'], match: ['/resources'] },
  { path: '/audit', title: '审计中心', icon: IconCheckList, perms: ['pds:audit:view'] },
]
