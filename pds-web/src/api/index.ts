import { get, post, put, del, type PageResult } from './http'

// ---------------- 类型
export interface OrgNode {
  id: string; parentId?: string; type: string; code: string; name: string; scenicId?: string
  sort: number; status: number; userCount: number; inheritFromParent: boolean; children: OrgNode[]
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
  type: 'BUILTIN' | 'GLOBAL' | 'APP' | 'SCENIC'; appCode?: string; appName?: string; parentRoleId?: string; parentName?: string
  parentChain: string[]; status: number; ownerId?: string; ownerName?: string; version: number; memberCount: number
  mutexRoleIds: string[]; mutexNames: string[]; scenicName?: string; inheritedPerms: string[]
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
  kind: 'ROLE' | 'DIRECT_ALLOW' | 'DIRECT_DENY' | 'GRANT_ALLOW' | 'GRANT_DENY' | 'RESOURCE_ALLOW' | 'RESOURCE_DENY' | 'DISABLED'
  roleName?: string; subjectType?: string; subjectName?: string; includeChildren: boolean; reason?: string; validTo?: string; text: string
  viaNode?: string
}
export interface PermissionState {
  code: string; name: string; type: string; risk: string; state: 'GRANTED' | 'DENIED' | 'NONE'; allowSources: string[]; denySources: string[]
  dataScope?: any; allowRefs: SourceRef[]; denyRefs: SourceRef[]; depth: number; visible: boolean
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

// ---------------- V2 权限中心
export interface NodeType {
  code: string; name: string; icon?: string; color?: string; parentTypes: string[]; grantable: boolean; dataScope: boolean
  defaultRisk: string; description?: string; sort: number; usage: number
}
export interface TreeApp { code: string; name: string; category?: string; nodeCount: number; pendingCount: number }
export interface PermNode {
  id: string; code: string; appCode: string; parentId?: string; name: string; type: string; typeName: string; risk: string
  securityLevel?: number; grantable: boolean; needMfa: boolean; dataScopeEnabled: boolean; source: 'MANIFEST' | 'MANUAL'
  status: number; description?: string; sort: number; draft?: 'NEW' | 'MODIFIED' | 'DELETING'; codeLocked: boolean; updatedAt?: string
}
export interface PermNodeDetail { node: PermNode; path: { id: string; code: string; name: string }[]; children: PermNode[]; holderCount: number }
export interface TreeChange { nodeId: string; code: string; name: string; kind: 'NEW' | 'MODIFY' | 'DELETE'; fields: { field: string; label: string; before: string; after: string }[] }
export interface TreeVersion { id: string; appCode: string; version: string; summary: string; changeCount: number; publishedBy?: string; publishedAt: string }
export interface Holder { userId: string; name: string; empNo?: string; deptName?: string; sources: string[] }
export interface HistoryItem { action: string; userName?: string; ts: string; detail?: string }

export interface AssignmentRow {
  id: string; kind: 'PERM' | 'ROLE' | 'RESOURCE'; subjectType: string; subjectId: string; subjectName: string; subjectSub?: string
  includeChildren: boolean; objectType: 'PERM' | 'ACCESS' | 'ROLE' | 'RESOURCE'; objectCode: string; objectName: string
  appCode?: string; appName?: string; risk?: string; action?: string; effect: 'ALLOW' | 'DENY'; dataScope?: string
  validFrom?: string; validTo?: string; source: string; reason?: string; grantedBy?: string; createdAt?: string
}
export interface AssignmentStats { accessUsers: number; records: number; highRisk: number; expiring: number }
export interface SubjectRefDto { type: string; id: string; includeChildren?: boolean }
export interface ObjectRefDto { type: 'PERM' | 'ROLE' | 'RESOURCE'; code?: string; roleId?: string; resourceType?: string; refId?: string; action?: string }
export interface Impact {
  affectedUsers: number; sampled: number; addedPerms: number; highRiskCount: number; highRiskAdded: string[]; lostPerms: number
  conflicts: { userName: string; detail: string }[]; exceeding: string[]; outOfScope: number; outOfScopeNames: string[]
  users: { userId: string; name: string; empNo?: string; added: number; highRisk: string[]; lost: number }[]
}

export interface OrgInfo {
  id: string; name: string; type: string; code: string; scenicId?: string; leaderId?: string; leaderName?: string
  memberCount: number; directMemberCount: number; inheritFromParent: boolean; root: boolean; parentName?: string
}
export interface NodeGrant {
  id: string; objectType: 'PERM' | 'ACCESS' | 'ROLE'; code: string; name: string; appCode?: string; appName?: string; risk?: string
  inherited: boolean; fromOrgId: string; fromOrgName: string; includeChildren: boolean; effect: 'ALLOW' | 'DENY'; dataScope?: string; validTo?: string
}
export interface PreviewItem { code: string; name: string; appCode?: string; appName?: string; risk?: string; sources: string[] }
export interface MembersPreview {
  added: number; addedNames: string[]; highRiskCount: number; highRisk: string[]; lost: number; lostNames: string[]
  users: { userId: string; name: string; empNo?: string; added: number; lost: number; highRisk: string[]; alreadyMember: boolean }[]
}

export interface RoleMappingView { appCode: string; appName: string; externalCode: string }
export interface RoleVersionView { id: string; version: number; summary: string; permCount: number; createdBy?: string; createdAt: string }
export interface RoleMember { userId: string; name: string; empNo?: string; deptName?: string; via: string[] }
export interface DistributionRow {
  appCode: string; appName: string; externalCode?: string; lastEvent?: string; lastEventLabel?: string; deliveryId?: string
  status?: 'PENDING' | 'SUCCESS' | 'RETRYING' | 'FAILED'; attempts?: number; lastError?: string; latencyMs?: number; time?: string; webhook: boolean
}
export interface Subscription {
  appCode: string; appName: string; tokenClaim: boolean; snapshot: boolean; webhook: boolean; scim: boolean
  webhookUrl?: string; secretSet: boolean; events?: string; updatedAt?: string
}

export interface GraphNode { id: string; type: string; label: string; sub?: string; risk?: string; state?: string; center: boolean; meta: Record<string, any> }
export interface GraphEdge { id: string; source: string; target: string; kind: string; label: string }
export interface Graph { nodes: GraphNode[]; edges: GraphEdge[]; truncated: boolean; summary: Record<string, any> }
export interface GraphExplain {
  userId: string; code: string; name: string; app: string; risk: string; verdict: 'ALLOW' | 'DENY' | 'NONE'
  allow: { steps: string[]; validTo?: string; text: string }[]; deny: { steps: string[]; validTo?: string; text: string }[]
  lastUsed?: string; unusedDays?: number
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
  positionStats: () => get<{ id: string; code: string; name: string; scenicId?: string; status: number; members: number; grants: number }[]>('/positions/stats'),
  updatePosition: (id: string, body: object) => put(`/positions/${id}`, body),
  deletePosition: (id: string) => del(`/positions/${id}`),

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
  updateGroup: (id: string, body: object) => put(`/groups/${id}`, body),
  deleteGroup: (id: string) => del(`/groups/${id}`),
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

  // 权限树
  treeApps: () => get<TreeApp[]>('/perm-tree/apps'),
  permTree: (app: string) => get<PermNode[]>('/perm-tree', { app }),
  grantableNodes: (app?: string) => get<PermNode[]>('/perm-tree/grantable', { app }),
  permNode: (id: string) => get<PermNodeDetail>(`/perm-nodes/${id}`),
  createNode: (body: object) => post<PermNode>('/perm-nodes', body),
  updateNode: (id: string, body: object) => put<PermNode>(`/perm-nodes/${id}`, body),
  disableNode: (id: string) => post<PermNode>(`/perm-nodes/${id}/disable`),
  enableNode: (id: string) => post<PermNode>(`/perm-nodes/${id}/enable`),
  deleteNode: (id: string) => del(`/perm-nodes/${id}`),
  discardNode: (id: string) => post(`/perm-nodes/${id}/discard`),
  nodeHolders: (id: string) => get<{ total: number; items: Holder[] }>(`/perm-nodes/${id}/holders`, { limit: 200 }),
  nodeHistory: (id: string) => get<HistoryItem[]>(`/perm-nodes/${id}/history`),
  treeDiff: (app: string) => get<TreeChange[]>(`/perm-tree/${app}/diff`),
  nextVersion: (app: string) => get<{ version: string }>(`/perm-tree/${app}/next-version`),
  publishTree: (app: string, summary?: string) => post<TreeVersion>(`/perm-tree/${app}/publish`, { summary }),
  treeVersions: (app: string) => get<TreeVersion[]>(`/perm-tree/${app}/versions`),
  nodeTypes: () => get<NodeType[]>('/perm-node-types'),
  createNodeType: (body: object) => post<NodeType>('/perm-node-types', body),
  updateNodeType: (code: string, body: object) => put<NodeType>(`/perm-node-types/${code}`, body),
  deleteNodeType: (code: string) => del(`/perm-node-types/${code}`),
  createApp: (body: object) => post('/apps', body),
  updateApp: (code: string, body: object) => put(`/apps/${code}`, body),
  appDeleteImpact: (code: string) => get<Record<string, number>>(`/apps/${code}/delete-impact`),
  deleteApp: (code: string) => del(`/apps/${code}`),

  // 分配库
  assignments: (params: object) => get<PageResult<AssignmentRow>>('/assignments', params),
  assignmentStats: (app?: string) => get<AssignmentStats>('/assignments/stats', { app }),
  impact: (body: object) => post<Impact>('/assignments/impact', body),
  batchGrant: (body: object) => post<{ created: number }>('/assignments/batch', body),
  updateAssignment: (id: string, body: object) => put(`/assignments/${encodeURIComponent(id)}`, body),
  renewAssignments: (ids: string[], validTo?: string, clearValidTo?: boolean) => post('/assignments/renew', { ids, validTo, clearValidTo }),
  revokeAssignments: (ids: string[]) => post('/assignments/revoke', { ids }),
  copyAssignments: (body: object) => post<{ count: number }>('/assignments/copy', body),

  // 架构树节点
  orgInfo: (id: string) => get<OrgInfo>(`/orgs/${id}/info`),
  orgGrants: (id: string) => get<NodeGrant[]>(`/orgs/${id}/grants`),
  addOrgGrants: (id: string, body: object) => post(`/orgs/${id}/grants`, body),
  setOrgInherit: (id: string, inherit: boolean) => put<OrgInfo>(`/orgs/${id}/inherit`, { inherit }),
  orgPreview: (id: string) => get<PreviewItem[]>(`/orgs/${id}/effective-preview`),
  orgPositions: (id: string) => get<{ positionId: string; code: string; name: string; count: number }[]>(`/orgs/${id}/positions`),
  orgHistory: (id: string) => get<HistoryItem[]>(`/orgs/${id}/history`),
  previewMembers: (id: string, body: object) => post<MembersPreview>(`/orgs/${id}/members/preview`, body),
  addMembers: (id: string, body: object) => post(`/orgs/${id}/members`, body),

  // 系统角色
  role: (id: string) => get<RoleView>(`/roles/${id}`),
  updateRole: (id: string, body: object) => put<RoleView>(`/roles/${id}`, body),
  copyRole: (id: string, body: object) => post<{ id: string }>(`/roles/${id}/copy`, body),
  deleteRole: (id: string, force = false) => del(`/roles/${id}?force=${force}`),
  setRoleMutex: (id: string, roleIds: string[]) => put<RoleView>(`/roles/${id}/mutex`, { roleIds }),
  roleMappings: (id: string) => get<RoleMappingView[]>(`/roles/${id}/mappings`),
  setRoleMappings: (id: string, mappings: { appCode: string; externalCode: string }[]) => put<RoleMappingView[]>(`/roles/${id}/mappings`, { mappings }),
  roleVersions: (id: string) => get<RoleVersionView[]>(`/roles/${id}/versions`),
  restoreRole: (id: string, versionId: string) => post<RoleView>(`/roles/${id}/versions/${versionId}/restore`),
  roleMembers: (id: string) => get<RoleMember[]>(`/roles/${id}/members`),
  roleDistribution: (id: string) => get<DistributionRow[]>(`/roles/${id}/distribution`),
  reconcileRole: (id: string) => post<{ apps: number }>(`/roles/${id}/reconcile`),
  subscriptions: () => get<Subscription[]>('/dist/subscriptions'),
  saveSubscription: (app: string, body: object) => put<Subscription>(`/dist/subscriptions/${app}`, body),
  deliveryPayload: (id: string) => get<{ id: string; type: string; payload: string }>(`/dist/deliveries/${id}/payload`),
  retryDelivery: (id: string) => post(`/dist/deliveries/${id}/retry`),

  // 权限图谱
  graphUser: (userId: string, params: object = {}) => get<Graph>(`/graph/user/${userId}`, params),
  graphApp: (app: string, scenicId?: string) => get<Graph>(`/graph/app/${app}`, { scenicId }),
  graphPerm: (code: string, scenicId?: string) => get<Graph>('/graph/perm', { code, scenicId }),
  graphOrg: (orgId: string) => get<Graph>(`/graph/org/${orgId}`),
  graphOverview: (scenicId?: string) => get<Graph>('/graph/overview', { scenicId }),
  graphExplain: (userId: string, code: string) => get<GraphExplain>('/graph/explain', { userId, code }),
  graphSimulate: (body: object) => post<{ added: string[]; highRisk: string[]; lost: string[] }>('/graph/simulate', body),
}

// ---------------- 字典
export const SUBJECT_TYPES: Record<string, string> = { USER: '人员', POSITION: '岗位', ORG: '组织', GROUP: '用户组' }
export const ORG_TYPES: Record<string, string> = { GROUP: '集团', COMPANY: '公司', SCENIC: '景区', DEPT: '部门', TEAM: '班组' }
export const DATA_SCOPES: Record<string, string> = {
  SELF: '仅本人', DEPT: '本部门', DEPT_TREE: '本部门及下级', SCENIC: '本人景区', SCENIC_LIST: '指定景区', ALL: '全部', CUSTOM: '自定义组织', DOMAIN: '指定领域',
}
export const ACTIONS: Record<string, string> = { READ: '查看', WRITE: '编辑', ADMIN: '管理' }
export const SECURITY_LEVELS = ['公开', '内部', '秘密', '机密']
export const RISKS: Record<string, string> = { LOW: '低', MEDIUM: '中', HIGH: '高' }
export const ROLE_TYPES: Record<string, string> = { BUILTIN: '内置', GLOBAL: '全局', APP: '应用', SCENIC: '景区' }
export const SOURCES: Record<string, string> = { MANUAL: '手工', TEMPLATE: '模板', APPLY: '申请单', SYNC: '同步' }
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
