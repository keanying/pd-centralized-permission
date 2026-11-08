import type { Component } from 'vue'
import { IconHome, IconGridView, IconShield, IconCheckList } from '@kousum/semi-icons-vue'
import OrgChartIcon from '@/components/icons/OrgChartIcon.vue'

export interface MenuLeaf {
  path: string
  title: string
  /** 满足其一即可见（与后端 PDS 权限点一致） */
  perms: string[]
  /** 这些路径也视为选中 */
  match?: string[]
}

export interface MenuGroup {
  key: string
  title: string
  icon: Component
  children: MenuLeaf[]
}

/**
 * 侧栏：一级为图标，悬停时在右侧浮出二级菜单（设计稿 V2）。只有一个子项的分组直接跳转。
 * 未实现的规划模块（数据与策略、申请与复核）不显示。
 */
export const menuGroups: MenuGroup[] = [
  { key: 'home', title: '工作台', icon: IconHome, children: [{ path: '/', title: '工作台', perms: ['pds:console:access'] }] },
  {
    key: 'perm', title: '权限中心', icon: IconShield, children: [
      { path: '/perm-tree', title: '权限树', perms: ['pds:role:view'] },
      { path: '/roles', title: '系统角色', perms: ['pds:role:view'] },
      { path: '/assignments', title: '分配库', perms: ['pds:authz:query'], match: ['/resources'] },
      { path: '/graph', title: '权限图谱', perms: ['pds:authz:query'] },
    ],
  },
  {
    key: 'org', title: '组织与人员', icon: OrgChartIcon, children: [
      { path: '/orgs', title: '架构树', perms: ['pds:org:view', 'pds:user:view'] },
      { path: '/user-grants', title: '人员明细权限', perms: ['pds:grant:manage', 'pds:authz:query'] },
    ],
  },
  { key: 'app', title: '应用中心', icon: IconGridView, children: [{ path: '/apps', title: '应用管理', perms: ['pds:role:view'] }] },
  { key: 'audit', title: '审计与报表', icon: IconCheckList, children: [{ path: '/audit', title: '审计中心', perms: ['pds:audit:view'] }] },
]
