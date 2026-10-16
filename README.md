# LTing 出行Agent

> 一个**多页面分层的 AI 旅行工作台**：对话式规划行程，并把行程、动态、打卡、偏好这些旅行资产落到库里、在界面上看得见。

`Spring Boot 2.7.18` · `Java 11` · `MySQL 8 / 内嵌 H2` · `原生 HTML/CSS/JS` · `Electron 桌面端`

**规模**：后端 33 个类 9,214 行 · 桌面端前端 17 个页面 8,338 行 · **微信小程序端 13 个页面 62 个文件** · 23 张表 · 82 个接口（另有 4 个小程序令牌接口）· 30 个 Agent 工具

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

左侧导航常驻固定（宽度不随内容伸缩），11 个业务页面各管一件事：

| 页面 | 干什么 |
|---|---|
| **出发** | 工作台首页：数据概览 + 模块入口 |
| **AI 管家** | 对话式规划，带工具调用的 Agent |
| **动态广场** | 发动态（公开 / 仅好友 / 仅自己）、刷广场、好友系统、他人主页 |
| **行程本** | 行程列表 / 时间线 / 版本回退 / **预算绑定对应行程** / 行李清单 |
| **打卡** | 独立于动态的记录：手填地点，或从**全屏选店大页**里挑（按店名搜、5km 内不设上限），支持传图，写入对应商户页 |
| **附近吃喝玩乐** | 5–10km **不设上限**（实测 5km 197 家），评分 / 菜单 / 真实评价 / **一键导航（内嵌）**，点评入口也内嵌 |
| **个人资料** | 头像、昵称、标签、简介、兴趣偏好；同行人；证件票据 |
| **偏好档案** | 饮食 / 体力 / 环境 / 出行四类硬约束 + 忌口食材 + 偏好模板 |
| **账号管理** | 改密码、绑定 / 换绑 / 解绑邮箱、注销账号 |
| 登录 / 注册 / 地图 | 登录注册、Electron 内嵌地图页 |

### Agent：30 个工具 + 12 轮循环

`LLM（大脑）+ Tools（30 个手脚）+ Loop（最多 12 轮）+ Memory（会话记忆，存库）`

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
| 出发 / AI 管家 / 行程本 + 详情 | home / chat / trips | 管家带内嵌地图（点它才弹全屏）；行程里带**预算**与**行李** |
| 动态广场（可发布） / 打卡 | square / checkin | 动态可选公开 · 仅好友 · 仅自己；打卡可搜附近店铺、独立于动态 |
| 附近吃喝玩乐 / 商户详情 | nearby / poi | 评分 · 菜单 · **打卡记录** · 点评 + 内嵌地图 + 一键导航 + 手填起点 |
| 好友 / TA 的主页 | friends / user | 用邀请码加好友，主页按权限过滤 |
| 我的（资料 · 同行人 · 证件 · 偏好档案） / 账号管理 | profile / preferences / account | 改密码 · 绑定解绑邮箱 · 注销账号 |

身份走**令牌**（`X-Auth-Token`）而不是 Cookie：服务端会话 cookie 是 `SameSite=Lax`，小程序发的是跨站请求，
Chromium 内核会把 `Set-Cookie` 丢掉，表现成"登录成功、下一个请求又说没登录"。后端的 `TokenAuthFilter`
把这个头翻译成会话身份，**既有 82 个接口一行都没改**，桌面端仍走 Cookie。

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

**下载地址**：https://github.com/KFLuv/ai-travel-assistant/releases/latest
（`AITravelGuide-1.1.1-portable.exe`，约 141 MB）

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

> **克隆下来就能直接跑**：仓库里已经内置可用的 Key（大模型对话 / 高德定位与地图 / 联网检索），
> 数据库默认走内嵌 H2，不装 MySQL 也能启动；下载 Release 里的免安装版更是双击即用。
> Key 只是「默认值」，换成自己的更稳妥：改 `backend/src/main/resources/application.properties`
> 里的 `ai.*` / `geo.*` / `search.*` / `map.*`，或用同名环境变量覆盖。
> 把 Key 放进仓库是有意为之：这个项目的目的是让面试官/朋友下载后**立刻看到真实效果**，而不是先花半天填配置。

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
│   ├── home.html  chat.html  square.html  trips.html  checkin.html
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
| 2 | **大众点评没有公开接口** | `nearby.source` 做成可插拔：配了 `dianping.endpoint/api-key` 走授权通道，否则用高德兜底；页面会如实显示当前数据源，不编造点评数据 |
| 3 | **点评跳转是"按店名搜"** | 点评详情页需要它自己的 shopId，我们只有店名+坐标，拼不出合法 id |
| 4 | **30 个工具偏多** | 超出模型有效选择范围（经验值 10–15），深层工具命中率不稳 |
| 5 | **`chat.html` 未做设计令牌化** | 它自带一套硬编码色值，与其它页面共用令牌是下一步 |
| 6 | **无单元测试** | 全靠手工与脚本验证 |

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

---

## 说明

- 本项目基础版本来自课程作业；**多页面重构、附近模块、动态广场与好友、偏好档案与 AI 约束、内嵌地图与导航、桌面端一体化交付均为后续迭代**
- **配置开箱即用**：仓库直接带着可用的 Key（对话 / 定位 / 检索 / 地图），克隆或下载即可看到真实效果；
  想换成自己的额度，改 `application.properties` 或用环境变量覆盖即可。仓库内不含数据库凭据、个人姓名与学号。
