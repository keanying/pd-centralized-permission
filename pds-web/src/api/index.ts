import { get, post, put, del, type PageResult } from './http'

// ---------------- 类型
export interface OrgNode {
  id: string; parentId?: string; type: string; code: string; name: string; scenicId?: string
  sort: number; status: number; userCount: number; children: OrgNode[]
}
export interface Position { id: string; code: string; name: string; scenicId?: string }
export interface UserView {
  id: string; username: string; empNo?: string; name: string; mobile?: string; email?: string; userType: string
  status: string; primaryOrgId?: string; primaryOrgName?: string; scenicId?: string; positions: string[]
  lastLoginAt?: string; locked: boolean
}
export interface Credential { userId: string; username: string; initialPassword?: string }
export interface GroupView { id: string; code: string; name: string; scenicId?: string; description?: string; memberCount: number }
export interface RolePermission { permCode: string; dataScope?: string; scopeValues?: string[] }
export interface RoleView {
  id: string; code: string; name: string; level: string; scenicId?: string; description?: string; builtin: boolean
  permissions: RolePermission[]; bindingCount: number
}
export interface BindingView {
  id: string; roleId: string; subjectType: string; subjectId: string; subjectName: string; includeChildren: boolean
  validFrom?: string; validTo?: string; createdBy: string; createdAt: string
}
export interface PermPoint { code: string; appCode: string; name: string; type: string; risk: string; description?: string; status: number }
export interface AppView {
  code: string; name: string; description?: string; category?: string; categoryName: string; accessControl: boolean
  manifestVersion?: string; permissionCount: number; resourceTypeCount: number; clientCount: number
}
export interface ClientView { clientId: string; name: string; app: string; grantTypes: string[]; redirectUris: string[]; scopes: string[]; publicClient: boolean; issuedAt: string }
export interface ResourceType { typeCode: string; appCode: string; name: string; actions: string }
export interface ResourceView { id: string; type: string; refId: string; name: string; scenicId: string; scenicName: string; ownerId?: string; grantCount: number; updatedAt: string }
export interface GrantView {
  id: string; subjectType: string; subjectId: string; subjectName: string; includeChildren: boolean; action: string; effect: string
  validFrom?: string; validTo?: string; reason?: string; grantedByName: string; createdAt: string
}
export interface SourceRef {
  kind: 'ROLE' | 'DIRECT_ALLOW' | 'DIRECT_DENY' | 'RESOURCE_ALLOW' | 'RESOURCE_DENY' | 'DISABLED'
  roleName?: string; subjectType?: string; subjectName?: string; includeChildren: boolean; reason?: string; validTo?: string; text: string
}
export interface PermissionState {
  code: string; name: string; type: string; risk: string; state: 'GRANTED' | 'DENIED' | 'NONE'; allowSources: string[]; denySources: string[]
  dataScope?: any; allowRefs: SourceRef[]; denyRefs: SourceRef[]
}
export interface ResourceState {
  type: string; refId: string; name: string; scenicId: string; actions: string[]; reason: string; denied: boolean; validTo?: string
  allowRefs: SourceRef[]; denyRefs: SourceRef[]
}
export interface UserEffective {
  userId: string; name: string; app: string; scenicScope: string[]; maxSecurityLevel: number; permissions: PermissionState[]
  resources: ResourceState[]; version: number; status: string
}
export interface UserPermView {
  id: string; permCode?: string; permName?: string; resourceType?: string; resourceId?: string; resourceName?: string; action?: string
  effect: string; dataScope?: string; validFrom?: string; validTo?: string; reason: string; grantedBy: string; createdAt: string
}
export interface AuditEvent {
  seq: number; id: string; ts: string; app: string; eventType: string; userId?: string; userName?: string; via?: string
  scenicId?: string; ip?: string; targetType?: string; targetIds?: string; action?: string; result: string; detail?: string; hash: string; prevHash: string
}
export interface Overview {
  onlineUsers: number; activeUsers: number; todayLogins: number; loginSparkline: number[]
  apps: number; healthy: boolean; healthText: string
  todayDenied: number; deniedChangePercent: number | null
  pendingApprovals: number | null; expiringGrants: number
  scenics: { id: string; name: string; users: number }[]
  loginWeek: { thisWeek: number[]; lastWeek: number[]; todayIndex: number }
  security: { activeUsers: number; activatedUsers: number; longInactive: number; locked: number; mustChangePassword: number }
  aiUsage: { scenicId: string; name: string; chats: number; retrievals: number; sensitive: number }[]
  recentAlerts: AuditEvent[]
}

// ---------------- 接口
export const api = {
  overview: () => get<Overview>('/dashboard/overview'),

  orgTree: () => get<OrgNode[]>('/orgs/tree'),
  createOrg: (body: object) => post('/orgs', body),
  updateOrg: (id: string, body: object) => put(`/orgs/${id}`, body),
  deleteOrg: (id: string) => del(`/orgs/${id}`),
  positions: () => get<Position[]>('/positions'),
  createPosition: (body: object) => post('/positions', body),

  users: (params: object) => get<PageResult<UserView>>('/users', params),
  user: (id: string) => get<UserView>(`/users/${id}`),
  importUsers: (rows: object[]) => post<{ row: number; ok: boolean; message: string; userId?: string; username: string; initialPassword?: string }[]>('/users/import', { rows }),
  createUser: (body: object) => post<Credential>('/users', body),
  updateUser: (id: string, body: object) => put(`/users/${id}`, body),
  assignOrgs: (id: string, assignments: object[]) => put(`/users/${id}/orgs`, { assignments }),
  disableUser: (id: string, reason: string) => post(`/users/${id}/disable`, { reason }),
  leaveUser: (id: string, reason: string) => post(`/users/${id}/leave`, { reason }),
  enableUser: (id: string) => post(`/users/${id}/enable`),
  resetPassword: (id: string) => post<Credential>(`/users/${id}/reset-password`),

  groups: () => get<GroupView[]>('/groups'),
  createGroup: (body: object) => post('/groups', body),
  groupMembers: (id: string) => get<{ id: string; name: string; username: string }[]>(`/groups/${id}/members`),
  setGroupMembers: (id: string, userIds: string[]) => put(`/groups/${id}/members`, { userIds }),

  roles: () => get<RoleView[]>('/roles'),
  createRole: (body: object) => post('/roles', body),
  setRolePermissions: (id: string, permissions: RolePermission[]) => put<RoleView>(`/roles/${id}/permissions`, { permissions }),
  roleBindings: (id: string) => get<BindingView[]>(`/roles/${id}/bindings`),
  bindRole: (id: string, body: object) => post(`/roles/${id}/bindings`, body),
  unbindRole: (bindingId: string) => del(`/role-bindings/${bindingId}`),
  bindingsOfSubject: (subjectType: string, subjectId: string) => get<BindingView[]>('/role-bindings', { subjectType, subjectId }),

  apps: () => get<AppView[]>('/apps'),
  appPermissions: (app: string) => get<PermPoint[]>(`/apps/${app}/permissions`),
  appClients: (app: string) => get<ClientView[]>(`/apps/${app}/clients`),
  createClient: (app: string, body: object) => post<{ clientId: string; clientSecret?: string }>(`/apps/${app}/clients`, body),
  resourceTypes: () => get<ResourceType[]>('/resource-types'),
  resources: (params: object) => get<PageResult<ResourceView>>('/resources', params),
  resourceGrants: (type: string, refId: string) => get<GrantView[]>(`/resources/${type}/${encodeURIComponent(refId)}/grants`),
  addResourceGrant: (type: string, refId: string, body: object) => post(`/resources/${type}/${encodeURIComponent(refId)}/grants`, body),
  removeResourceGrant: (type: string, refId: string, grantId: string) => del(`/resources/${type}/${encodeURIComponent(refId)}/grants/${grantId}`),

  userEffective: (id: string, app: string) => get<UserEffective>(`/users/${id}/effective`, { app }),
  userPerms: (id: string) => get<UserPermView[]>(`/users/${id}/perms`),
  addUserPerm: (id: string, body: object) => post(`/users/${id}/perms`, body),
  deleteUserPerm: (id: string, permId: string) => del(`/users/${id}/perms/${permId}`),
  updateUserPerm: (id: string, permId: string, body: object) => put(`/users/${id}/perms/${permId}`, body),
  setScenicScope: (id: string, scenicIds: string[]) => put(`/users/${id}/scenic-scope`, { scenicIds }),
  setSecurityLevel: (body: object) => put('/security-levels', body),
  explain: (params: object) => get<{ allowed: boolean; reason: string }>('/authz/explain', params),

  audit: (params: object) => get<PageResult<AuditEvent>>('/audit/events', params),
  auditVerify: () => get<{ intact: boolean; checked: number; message: string }>('/audit/verify'),
  auditVerifyOne: (id: string) => get<{ intact: boolean; message: string }>(`/audit/events/${id}/verify`),

  changePassword: (oldPassword: string, newPassword: string) => post('/me/password', { oldPassword, newPassword }),
}

// ---------------- 字典
export const SUBJECT_TYPES: Record<string, string> = { USER: '人员', POSITION: '岗位', ORG: '组织', GROUP: '用户组' }
export const ORG_TYPES: Record<string, string> = { GROUP: '集团', COMPANY: '公司', SCENIC: '景区', DEPT: '部门', TEAM: '班组' }
export const DATA_SCOPES: Record<string, string> = {
  SELF: '仅本人', DEPT: '本部门', DEPT_TREE: '本部门及下级', SCENIC: '本人景区', SCENIC_LIST: '指定景区', ALL: '全部', CUSTOM: '自定义组织', DOMAIN: '指定领域',
}
export const ACTIONS: Record<string, string> = { READ: '查看', WRITE: '编辑', ADMIN: '管理' }
export const SECURITY_LEVELS = ['公开', '内部', '秘密', '机密']
export const USER_STATUS: Record<string, { label: string; color: string }> = {
  ACTIVE: { label: '在职', color: 'success' }, DISABLED: { label: '停用', color: 'default' }, LEFT: { label: '离职', color: 'error' },
}
export const EVENT_TYPES: Record<string, string> = {
  LOGIN: '登录', LOGIN_FAIL: '登录失败', LOGOUT: '退出', ADMIN_CHANGE: '管理变更', ACCESS_DENIED: '越权拦截', TOKEN_EXCHANGE: '令牌交换',
  PASSWORD_CHANGE: '修改密码', SYSTEM_INIT: '系统初始化', DATA_ACCESS: '数据访问', DATA_DOWNLOAD: '下载', DATA_EXPORT: '导出',
  AI_CHAT: 'AI 问答', AI_TOOL_CALL: 'AI 工具调用', MASK_REVEAL: '查看明文', SCENIC_SWITCH: '切换景区',
}

/** 组织树扁平化（用于下拉选择）。 */
export function flattenOrgs(nodes: OrgNode[], depth = 0, out: (OrgNode & { depth: number })[] = []) {
  for (const n of nodes) {
    out.push({ ...n, depth })
    flattenOrgs(n.children, depth + 1, out)
  }
  return out
}
