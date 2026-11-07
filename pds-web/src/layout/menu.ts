import type { Component } from 'vue'
import {
  IconHome, IconBranch, IconUser, IconUserGroup, IconShield, IconKey, IconLayers, IconAppCenter, IconHistory,
} from '@kousum/semi-icons-vue'

export interface MenuItem {
  path: string
  title: string
  short: string
  icon: Component
  perms: string[]
}

export const menus: MenuItem[] = [
  { path: '/', title: '工作台', short: '工作台', icon: IconHome, perms: ['pds:console:access'] },
  { path: '/orgs', title: '组织架构', short: '组织', icon: IconBranch, perms: ['pds:org:view'] },
  { path: '/users', title: '人员管理', short: '人员', icon: IconUser, perms: ['pds:user:view'] },
  { path: '/groups', title: '用户组', short: '用户组', icon: IconUserGroup, perms: ['pds:user:view'] },
  { path: '/roles', title: '角色管理', short: '角色', icon: IconShield, perms: ['pds:role:view'] },
  { path: '/user-grants', title: '人员明细权限', short: '明细权限', icon: IconKey, perms: ['pds:grant:manage', 'pds:authz:query'] },
  { path: '/resources', title: '资源授权', short: '资源', icon: IconLayers, perms: ['pds:role:view'] },
  { path: '/apps', title: '应用管理', short: '应用', icon: IconAppCenter, perms: ['pds:role:view'] },
  { path: '/audit', title: '审计中心', short: '审计', icon: IconHistory, perms: ['pds:audit:view'] },
]
