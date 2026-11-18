# LTing · Click-first Agent 旅行工作台

> 从“输入一句话等 AI 生成”升级为“用户点击做选择，规则引擎保证可行性，AI 负责自由表达和结构化执行”。

**2.0 主线**：目的地与时间 → 点击候选地点 → 规则校验与行程生成 → 点击或 AI 生成 Operation → Preview → 用户确认 Apply → 任一版本可回退。

`Spring Boot 2.7.18` · `Java 11` · `MySQL 8 / 内嵌 H2` · `原生 HTML/CSS/JS` · `Electron 桌面端`

**规模**：后端 50 个 Java 文件 · 桌面端 21 个页面 · 31 个建表语句 · **微信小程序端 13 个页面 62 个文件** · 12 个受控 Agent 工具入口

---

## 当前验证状态

### 已验证

- 后端构建成功，隔离 H2 可以完成建表与迁移。
- 10 条 Agent 任务集自动检查 **10/10 通过**。
- 覆盖正常规划、偏好记忆、行程修改、版本回退、工具失败、跨用户越权、多会话隔离、多人偏好冲突、事实边界和预算。
- 修改已有行程会写回原记录，回退会同时恢复正文和行程节点。
- 2026-11-08 实际回归：城市/POI、候选持久化、区域筛选保选、店名搜索、AI 润色、带坐标节点、按天路线地图、短途三方案和行程节点编辑均通过独立 H2 接口与浏览器点击测试。
- 2026-11-18 LTing 2.0.2 回归：16 条 PlanOps/Today/POI 集成测试通过。
- 真实模型完成 18/18 个 PlanOps 操作预览回归。
- Headless Edge 验证候选自动补充、筛选页不暴露外部源按钮、文旅数据导入和候选加入。
- 免安装包可以启动内置 JRE、读取仓库外 Key，并通过数据源健康检查。
- 后端使用 `javaw.exe` 启动，不创建控制台窗口。

### 尚未验证

- 没有第二个人独立完成真实用户研究。
- 没有跨 Windows 版本、跨网络环境和长期使用数据。
- 没有对大众点评、地图供应商和外部搜索服务做稳定性承诺。
- 百度、腾讯 Provider 已完成代码和缺 Key 降级验证，但还未在真实商用 Key 下完成字段验收。

因此对外应表述为：

> 一个完成自动化评测、本人实机验收和桌面端交付的 AI 旅行 Agent 项目。

不能表述为：

> 已服务大量真实用户，或已验证商业价值。

---

## 2.0.2 核心变化

| 能力 | 说明 |
|---|---|
| **Click-first** | 选目的地、时间、候选点和区域，不再要求先面对空白聊天框 |
| **PlanOps** | 点击和 AI 共用 Context → Preview → Apply → 版本回退 |
| **候选池** | 支持类型、美食、区域、连续选择、总数可见、分批展开和一键显示全部 |
| **地点锚定** | 可搜索县、镇、村、景区周边，过滤住宿、停车、厕所等非游玩设施 |
| **行程约束** | Day Optimize、Plan Critic、营业时间、交通、预算、天气和同行人偏好 |
| **空间与路线** | 候选聚类、住宿通勤评分、路线矩阵、路线耗时写回和地图聚合 |
| **多人协作** | 邀请码、must/can/skip 投票、冲突仲裁和操作者版本 |
| **Today Mode** | 当前/下一站、今日预算、行程内打卡、临时重排和离线行程卡 |
| **多源 POI** | 高德主源 + 百度/腾讯可选 Provider + OSM/维基/地方文旅自动补充 |
| **数据健康** | 高德、模型、地图、检索、天气、UGC、外部 POI 和商用 Provider 可观测 |

产品重点从“会聊天”变成“每次修改可见、可执行、可验证、可撤销”。

---

## 这个项目解决什么

常见的「AI 出行助手」有两个通病：

| 通病 | 结果 |
|---|---|
| **聊完就丢** | 问完行程关掉页面什么都不剩，下次得重新问一遍 |
| **说了不做** | AI 说「已帮你记下不吃辣」，但没有任何地方能看到它记住了什么 |

这个项目围绕这两点做闭环：**AI 的每一次写入都落到数据库，并且用户能在界面上看见**；偏好一旦记下，后续所有行程方案与商户推荐都必须受它约束。

---

## 功能一览

### 多页面分层（不做单页堆砌）

左侧导航常驻固定（宽度不随内容伸缩），业务页面各管一件事：

| 页面 | 干什么 |
|---|---|
| **出行工作台** | 功能边界、使用引导、长途 / 短途 / 我的行程三个入口与最近行程 |
| **长途规划** | 日期与目的地；候选池、区域/类型筛选、地点锚定搜索、住宿区域、内嵌路线地图、三套行程骨架和 PlanOps |
| **周边短途** | 按时间、交通、目的、距离生成 2-3 个附近空间组合，选择后可改顺序并保存 |
| **AI 助手** | 收在右下角气泡，携带当前页面 / 行程 / 版本上下文；只生成结构化 Preview，确认后才 Apply |
| **动态广场** | 发动态（公开 / 仅好友 / 仅自己）、刷广场、好友系统、他人主页 |
| **行程本** | 按天时间线、节点编辑、路线地图、Day Optimize、Plan Critic、Today Mode、版本回退、预算与行李 |
| **打卡** | 独立于动态的记录：手填地点，或从**全屏选店大页**里挑（按店名搜、5km 内不设上限），支持传图，写入对应商户页 |
| **附近吃喝玩乐** | 5–10km 不设上限，评分 / 菜单 / 真实评价 / 内嵌地图与导航 |
| **多人协作** | 邀请码加入、投票、冲突仲裁、活动记录和共享行程版本 |
| **个人资料** | 头像、昵称、标签、简介、兴趣偏好；同行人；证件票据 |
| **偏好档案** | 饮食 / 体力 / 环境 / 出行四类硬约束 + 忌口食材 + 偏好模板 |
| **账号管理** | 改密码、绑定 / 换绑 / 解绑邮箱、注销账号 |
| 登录 / 注册 / 地图 | 登录注册、Electron 内嵌地图页 |

### Agent：12 个受控工具入口 + 12 轮循环

`LLM（大脑）+ Tools（12 个领域入口）+ Loop（最多 12 轮）+ Memory（会话记忆，存库）`

- **写操作可见**：`/agent/chat` 除了 `answer` 还返回 `actions`，前端渲染成「本次为你做了 N 件事」
- **偏好是硬约束**：任何行程方案、点位与商户推荐都必须先满足画像；冲突要点明并给折中方案
- **对话自动收录**：用户提到「不吃辣 / 走不了太久 / 怕吵 / 想坐地铁」，AI 立即写入偏好档案，不必等用户说「记一下」
- **上下文可续**：对话与多会话都存在库里，换设备、重开浏览器都能接上

### 可见权限模型

动态、行程、打卡、评价四类内容各自带 `public / friends / private`；广场只聚合 `public`，好友主页按是否为好友过滤，仅自己的内容只有本人可见。过滤逻辑统一走一个 SQL 片段生成器，避免每个查询各写一遍写歪成越权。

---

### 微信小程序端（13 个页面，对齐桌面端 9 个模块）

小程序端是同一套后端接口的第二个客户端（微信不认 HTML，页面必须用 WXML/WXSS 重写一遍）：

| 小程序页面 | 对应桌面端 | 说明 |
|---|---|---|
| 出发 / AI 管家 / 行程本 + 详情 | workbench / chat / trips | 管家带内嵌地图（点它才弹全屏）；行程里带**预算**与**行李** |
| 动态广场（可发布） / 打卡 | square / checkin | 动态可选公开 · 仅好友 · 仅自己；打卡可搜附近店铺、独立于动态 |
| 附近吃喝玩乐 / 商户详情 | nearby / poi | 评分 · 菜单 · **打卡记录** · 点评 + 内嵌地图 + 一键导航 + 手填起点 |
| 好友 / TA 的主页 | friends / user | 用邀请码加好友，主页按权限过滤 |
| 我的（资料 · 同行人 · 证件 · 偏好档案） / 账号管理 | profile / preferences / account | 改密码 · 绑定解绑邮箱 · 注销账号 |

身份走**令牌**（`X-Auth-Token`）而不是 Cookie：服务端会话 cookie 是 `SameSite=Lax`，小程序发的是跨站请求，
Chromium 内核会把 `Set-Cookie` 丢掉，表现成"登录成功、下一个请求又说没登录"。后端的 `TokenAuthFilter`
把这个头翻译成会话身份，**既有业务接口无需为小程序复制一套**，桌面端仍走 Cookie。

导入、真机调试、上线体验版的三关、常见报错，都在 [miniprogram/README.md](miniprogram/README.md)。

---

## 技术决定（以及代价）

| 决定 | 为什么 | 代价 |
|---|---|---|
| **多页面分层**，不做单页堆砌 | 功能变多后单页会让信息架构崩塌 | 每个页面要自己拉数据、自己渲染 |
| **默认内嵌 H2（MySQL 兼容模式）** | 交付给面试官/朋友时对方不必先装 MySQL 8 | 需要把 SQL 写成两边都兼容的方言 |
| **打包自带 jlink 裁剪的 JRE（约 50MB）** | 对方机器没装 Java 也能双击运行 | 安装包从 111MB 涨到 141MB |
| **写操作返回 actions** | 「看不见的记忆等于没有记忆」 | 每个写工具要补一句中文摘要 |
| **不用 ORM、不引前端框架** | 原生 JDBC + PreparedStatement 让 SQL 可控；单页刷新即生效 | 手写 SQL 与 DOM |
| **会话持久化用 Tomcat JDBCStore** | 关掉应用再打开，登录态与身份原样恢复 | 与数据库强绑定 |

---

## 快速开始

### 方式一：直接跑桌面端（不需要装 Java / MySQL）

**下载地址**：https://github.com/KFLuv/LTing-TripAgent/releases/latest
（`LTing-2.0.2-portable.exe`）

内置 JRE 与内嵌 H2 数据库（落在 `%USERPROFILE%\.ai-travel-assistant\data`），双击即用。首次运行会自解压到临时目录，稍慢。

### 方式二：从源码跑

```bash
# 1. 后端（默认走内嵌 H2，无需 MySQL）
cd backend
mvn -DskipTests package
java -jar target/chat-gpt.jar
# → http://localhost:8078/chat/login.html

# 2. 桌面端（自动拉起后端 + 内嵌地图）
cd desktop
npm install
npm run jre     # 用 JDK 11 的 jlink 生成自带 JRE
npm start
npm run dist    # 打包成单文件免安装版
```

要接自己的 MySQL / 大模型 / 地图，改 `backend/src/main/resources/application.properties`（`db.*`、`ai.*`、`geo.*`、`map.*`），或设同名环境变量覆盖。

> **仓库不包含任何 Key**（曾经包含过，结果被盗刷了 90 多元，已全部作废）：`application.properties` 里一律留空。
> 要用真实能力，用**环境变量**注入（Spring 会把 `AI_API_KEY` 自动映射到 `ai.api-key`）：
> `AI_API_KEY` / `GEO_AMAP_KEY` / `GEO_BAIDU_KEY` / `GEO_TENCENT_KEY` / `SEARCH_API_KEY` / `MAP_JS_KEY` / `MAP_JS_SECURITY_CODE`。
> 不填也能启动：数据库走内嵌 H2，行程 / 动态 / 打卡 / 附近列表照常可用，只有大模型对话与地图能力降级。

> **密钥核对（2026-11-06）**：已检查当前工作区、全部 Git 历史及拉取后的远端引用，未包含当前实际使用的 DeepSeek Key。真实 Key 只保存在仓库外的 `%USERPROFILE%\.ai-travel-assistant\application.properties`。历史中仍可检索到已经作废的旧 Key 记录，因此不要把旧提交里的值重新启用。

---

### 方式三：跑微信小程序端

1. 下载打包好的工程 [`release/ai-travel-assistant-miniprogram-v1.0.0.zip`](release/ai-travel-assistant-miniprogram-v1.0.0.zip) 解压（也可以直接用仓库里的 `miniprogram/`）；
2. 开发者工具「导入项目」选那个目录（里面有 `app.json`），AppID 用自己的或点「测试号」；
3. **先起后端**（小程序每个页面都要调接口）：双击桌面端，或
   ```powershell
   cd backend\target
   ..\..\desktop\build\jre\bin\java.exe -jar chat-gpt.jar --server.port=8078
   ```
4. 「详情 → 本地设置」勾上**不校验合法域名**；后端地址默认写的是电脑的局域网地址，
   换环境就在登录页点「后端：…（点这里改）」改一下（存在本地缓存，不用重新上传代码）。

> **手机能不能用**：能，但要过三关——后端得能被手机访问（小程序不允许 http / IP 直连，真机调试除外）、
> request 合法域名、位置接口与隐私指引。完整清单见 [miniprogram/README.md](miniprogram/README.md) 第七节。

---

## 目录结构

```
ai-travel-assistant/
├── backend/                    Spring Boot 后端
│   └── src/main/java/com/iflytek/chatgpt/
│       ├── agent/              Agent 引擎（循环 / 工具 / 记忆 / 检索 / 导航）
│       ├── nearby/             附近模块的数据源适配（本模块内部使用，不对外暴露）
│       ├── social/             好友关系与可见权限
│       ├── controller/         11 个控制器
│       └── ...
├── frontend/                   原生多页面（零构建，改完刷新即生效）
│   ├── assets/                 共享外壳：导航、样式令牌、内嵌面板、接口封装
│   ├── workbench.html  plan.html  shorttrip.html  chat.html
│   ├── home.html  square.html  trips.html  checkin.html
│   ├── nearby.html  poi.html  profile.html  preferences.html  account.html
│   └── login.html  reg.html  map.html  style.css
├── desktop/                    Electron 桌面端
│   ├── main.js                 自动拉起后端（优先用自带 JRE）
│   └── scripts/make-jre.js     用 jlink 裁出自带 JRE
├── miniprogram/                微信小程序端（原生、零构建，导入开发者工具即用）
├── release/                    打包好的下载件（小程序工程 zip）
└── docs/                       产品 / 设计 / 技术文档与开发过程记录
```

---

## 已知限制

诚实标注，没有为了显得完整而藏起来：

| # | 限制 | 说明 |
|---|---|---|
| 1 | **密码明文存储** | 历史遗留，应改为 BCrypt；改密码已有当前口令校验 |
| 2 | **点评、美团、抖音、小红书无通用公开 POI API** | 不使用抓取作为稳定底层能力；只有取得正式合作接口才接入 |
| 3 | **百度、腾讯需商用 Key** | 代码和缺 Key 降级已完成，真实账号下仍需字段和配额验收 |
| 4 | **OSM / 维基公共源无 SLA** | 端点超时会自动降级，不阻塞高德与候选流程 |
| 5 | **住宿仍只到区域级** | 具体酒店、实时房价和库存暂缓 |
| 6 | **尚未完成消费级发现页** | `discover.html`、`/plan/discover/feed` 和稳定 `place_master` 仍在后续计划 |
| 7 | **跨平台和真人研究不足** | 尚未完成多人长期体验研究和多 Windows 版本验证 |

---

## 文档导航

| 想了解 | 看这份 |
|---|---|
| **需求怎么定义（PRD）** | [docs/PRD.md](docs/PRD.md) |
| **这个项目做什么** | [docs/产品文档.md](docs/产品文档.md) |
| **怎么实现** | [docs/设计文档.md](docs/设计文档.md) |
| **为什么这么设计**（含代价） | [docs/产品决策记录.md](docs/产品决策记录.md) |
| **Agent 怎么接、坑在哪** | [docs/技术提炼.md](docs/技术提炼.md) |
| **LLM / Agent / Harness / MCP 的关系** | [docs/LLM-Agent-Harness-MCP-教学.md](docs/LLM-Agent-Harness-MCP-教学.md) |
| **开发过程中发现的问题与改法** | [docs/analysis/](docs/analysis/) |
| **Agent 任务怎么评测** | [docs/evaluation/LTing-Agent-Eval.md](docs/evaluation/LTing-Agent-Eval.md) |
| **10 条评测用例** | [docs/evaluation/lting-eval-cases.jsonl](docs/evaluation/lting-eval-cases.jsonl) |
| **最终评测报告** | [docs/evaluation/LTing-Agent-Eval-Final-20261105.md](docs/evaluation/LTing-Agent-Eval-Final-20261105.md) |
| **Badcase 与修复记录** | [docs/evaluation/lting-badcases-20261104.json](docs/evaluation/lting-badcases-20261104.json) |
| **12 工具完整评测结果** | [docs/evaluation/lting-eval-complete-final-20261105.json](docs/evaluation/lting-eval-complete-final-20261105.json) |
| **回退最终回归** | [docs/evaluation/lting-eval-rollback-final-20261104.json](docs/evaluation/lting-eval-rollback-final-20261104.json) |
| **问题—修复—验证发版记录** | [docs/release-notes/2026-11-05-agent-eval-hardening.md](docs/release-notes/2026-11-05-agent-eval-hardening.md) |
| **v1.1.9 → 2.0.2 完整发布说明** | [docs/release-notes/v2.0.2.md](docs/release-notes/v2.0.2.md) |
| **真人用户验收脚本** | [docs/testing/LTing-User-Acceptance-20261104.md](docs/testing/LTing-User-Acceptance-20261104.md) |
| **本人 self-use 实机验收** | [docs/testing/LTing-Self-Use-Validation-20261105.md](docs/testing/LTing-Self-Use-Validation-20261105.md) |

---

## 说明

- 本项目基础版本来自课程作业；**多页面重构、Click-first 工作台、PlanOps、候选池、Day Optimize、Plan Critic、多人协作、Today Mode、多来源 POI、内嵌地图与导航、桌面端一体化交付均为后续二次开发**
- **仓库不含任何 Key**（曾经内置过，被盗刷后全部作废）：需要真实能力时用环境变量注入；
  不填也能跑，只是对应能力降级。仓库内也不含数据库凭据、个人姓名与学号。
