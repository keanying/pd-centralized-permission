# pd_centralized_permission
PDS 统一权限：统一登录 · 角色授权 · 景区隔离 · 字段脱敏 · 访问审计（Java）

按"组织架构 + 人员明细权限"模式，对 PDK 知识服务全部能力和 PDA（hermes-agent，对应客户架构中的 PAP 智能体平台）全部能力进行权限控制；人员通过 PDS 单点登录后在 PDA 中提问。

## 文档

| 文档 | 说明 |
|---|---|
| [00-总体架构与PDA接入方案](docs/00-总体架构与PDA接入方案.md) | PDK / PDS / PDA 总体架构、与客户架构的对应关系、身份透传、权限点目录 |
| [01-PDS产品功能文档](docs/01-PDS产品功能文档.md) | 功能清单、授权模型、对 PDK/PDA 的控制方案、接口清单、验收要点 |
| [02-PDS技术方案](docs/02-PDS技术方案.md) | 认证中心、授权引擎、景区隔离、脱敏、审计、事件、SDK、里程碑 |

相关仓库：[pd_knowledge_service](https://github.com/keanying/pd_knowledge_service)（PDK 知识服务）
