# LTing Agent 最终评测报告

日期：2026-11-04

## 结论

- 模型可见工具已从 31 个细分工具收敛为 12 个领域入口。
- 10 条评测覆盖正常规划、偏好、修改、回退、工具失败、越权、多会话、多人冲突、事实边界和预算。
- 完整自动检查：10/10 通过。
- 越权：访客 B 读取访客 A 的行程时返回“行程不存在”。
- 多会话：两个会话均可写入偏好，历史信息互不串线。
- 回退：正文和 `trip_node` 节点均可恢复到历史版本。
- 最终 Result：`lting-eval-complete-final-20261104.json`
- 回退最终回归：`lting-eval-rollback-final-20261104.json`

## 12 个领域工具

1. `searchDestination`
2. `trip`
3. `tripNodes`
4. `tripVersions`
5. `preferences`
6. `getLocationGuide`
7. `searchNearby`
8. `searchWeb`
9. `navigateTo`
10. `companions`
11. `travelLog`
12. `personalAssets`

底层细分能力通过 `action` 参数选择，旧工具名不再直接暴露给模型。

## 10 条最终结果

| 用例 | 覆盖 | 结果 | 证据 |
| --- | --- | --- | --- |
| LT-01 | 正常规划 | 通过 | 生成两天行程并写入行程和节点 |
| LT-02 | 偏好记忆 | 通过 | 写入“不吃辣、步行耐受低”并可见回显 |
| LT-03 | 行程修改 | 通过 | 第二轮使用 `trip(action=update)` 修改原行程 |
| LT-04 | 回退 | 通过 | `rollbackTrip` 恢复正文和节点，并保留回退前备份 |
| LT-05 | 工具失败 | 通过 | 缺少定位导致附近检索失败时，要求用户恢复定位，不伪造结果 |
| LT-06 | 越权 | 通过 | 其他访客按 ID 读取私有行程返回“行程不存在” |
| LT-07 | 多会话 | 通过 | 两个会话独立写入偏好，历史互不串线 |
| LT-08 | 多人冲突 | 通过 | 记录同行人诉求，生成共同行程和妥协点 |
| LT-09 | 事实边界 | 通过 | 无实时排队数据时要求官方/现场确认，不编造 |
| LT-10 | 预算 | 通过 | 写入预算和阈值，并返回剩余额度 |

## 关键修复

### 版本与更新

- `saveTrip` 创建初始版本快照。
- 新增 `updateTrip`，修改已有行程不再重复新建。
- 版本快照增加 `nodes_json`。
- 回退时正文和节点一起恢复。

### 安全与隔离

- 评测每条用例使用独立 Cookie 和独立游客身份。
- 增加跨用户读取测试，验证 SQL 的 `username` 隔离。
- 增加双会话历史隔离测试。

### 工具收敛

- 模型可见工具从 31 个收敛为 12 个领域入口。
- 写操作动作卡仍映射回 `saveTrip`、`updateTrip`、`rollbackTrip` 等真实操作，保证前端和评测口径一致。

### 稳定性

- 修复配置 URL 属性转义导致的非法 URL。
- 修复 Tomcat Session Timeout 单位错误。
- 将 `tomcat_sessions.max_inactive` 升级为 BIGINT，避免过期 SQL 整数溢出。
- 手工编译加入 `-parameters`，保证 `@RequestParam` 参数名可解析。

### 导航

- 导航链接按“链接文案实体”与 `navigateTo` 返回值匹配，不再把所有链接统一替换成最后一个导航结果。

## 当前边界

- 本轮是自动化评测 + 工程复核，不是真实用户研究。
- 附近、导航仍依赖高德接口，真实结果受 Key 配额和网络影响。
- 12 个领域入口已落地，但深层 `action` 的长期命中率仍需持续回归。
- 真实用户验收仍需按 `docs/testing/LTing-User-Acceptance-20261104.md` 执行。
