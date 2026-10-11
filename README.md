# AI 旅游助手

> 一个**能对话规划行程、并把每一件旅行资产沉淀下来**的 AI 管家
> Spring Boot 2.7.18 · Java 11 · MySQL 8 · 原生 HTML/JS · Electron 桌面端

**规模**：后端 24 个类 7,269 行 · 前端单页 4,513 行 · 19 张表 · 65 个接口 · 30 个 Agent 工具

---

## 这个项目解决什么

常见的「AI 旅行助手」有两个通病：

| 通病 | 结果 |
|---|---|
| **聊完就丢** | 用户问完行程，关掉页面什么都不剩，第二次要重新问一遍 |
| **说了不做** | AI 说「已帮你记下不吃辣」，但没有任何地方能看到它记住了什么 |

这个项目围绕这两点做了完整闭环：**AI 的每一次写入都落到数据库，并且用户能在界面上看见。**

---

## 功能一览

### 🤖 智能管家（Agent）

不是一问一答的聊天，而是**带工具调用循环的 Agent**：
`LLM（大脑）+ Tools（30 个手脚）+ Loop（最多 12 轮）+ Memory（会话记忆）`

| 能力 | 用户说什么 | Agent 做什么 |
|---|---|---|
| **动态行程编排** | 「起晚了」「加个地方」「下雨了」 | 读画像 → 读现有节点 → 查开放时间 → 算通勤 → 写回节点 → 输出风险清单 |
| **偏好记忆** | 「我不吃辣」「走不了太久」 | 写入画像，并在回答末尾回显「📌 已记住：…」 |
| **GPS 现场向导** | 「附近有什么好吃的」 | 基于真实坐标算距离，报出米数 |
| **多人协同** | 「带爸妈去，他们走不动」 | 登记同行人偏好，给出带取舍说明的方案 |

### 💬 多会话管理

**每次迭代新增，也是最能体现"产品思维"的一处。**

| 能力 | 接口 |
|---|---|
| 新建话题 | `POST /agent/session/new` |
| 会话列表 | `POST /agent/session/list` |
| 重命名 | `POST /agent/session/rename` |
| 删除 | `POST /agent/session/delete` |
| **自动起标题** | 首条消息自动生成会话标题 |

> 为什么重要：会话级记忆解决「关掉软件还能接上」，**多会话解决「想开个新话题」**。
> 只有一个长会话时，聊到第五个话题时上下文已经全是噪音。

### 📖 旅行资产（19 张表支撑）

| 资产 | 说明 |
|---|---|
| **行程本** | 行程 + **时间线节点** + **版本快照（可回退）** |
| **偏好档案** | 画像标签 + 多套可切换模板 |
| **打卡日志** | 地点、评分、耗时、照片 |
| **游记生成** | 基于打卡记录生成结构化游记 |
| **灵感收藏** | 收藏 / 避雷 / 待打卡 四类清单 |
| **工具箱** | 预算账本（含告警阈值）· 行李清单 · 证件归档 |
| **同行人** | 偏好标签 + 投票 |

---

## 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 后端 | Spring Boot 2.7.18 | 内嵌 Tomcat，打成可执行 jar |
| Java | 11 | 用了 `java.net.http.HttpClient` |
| 数据库 | MySQL 8 | 19 张表，原生 JDBC |
| 前端 | 原生 HTML + CSS + Vue 3（CDN） | **零构建工具**，改完刷新即生效 |
| AI | OpenAI 兼容协议 | 换厂商只改三行配置 |
| 桌面端 | Electron 44 | 内嵌 webview 承载地图 |
| 会话 | Tomcat `JDBCStore` | 会话写 MySQL，**重启后登录态不丢** |

---

## 快速开始

### 1 准备数据库

```sql
create database if not exists user-mannager default charset utf8mb4;
```

**19 张表全部自动创建**（`AgentSchemaInitializer` 启动时执行 `create table if not exists`）。

### 2 填配置

`backend/src/main/resources/application.properties`：

```properties
server.port=8078
server.servlet.context-path=/chat

# 大模型（留空则退回本地模拟回复，功能不会挂）
ai.base-url=https://api.deepseek.com
ai.api-key=
ai.model=deepseek-chat

# GPS 逆地理（留空则退回 OpenStreetMap）
geo.amap-key=

# 联网检索（留空则退回必应抓取）
search.api-key=

# 页面内嵌地图（高德 JS API，需单独申请，与上面 geo.amap-key 类型不同）
map.js-key=
map.js-security-code=
```

> ⚠️ **本仓库所有 Key 字段均为空**，运行前请填自己的。

### 3 填数据库连接（可选）

连接参数**不需要改代码** —— 按「环境变量 → JVM 系统属性 → 默认值」的顺序读取：

| 环境变量 | 系统属性 | 默认值 |
|---|---|---|
| `DB_URL` | `-Ddb.url=` | `jdbc:mysql://localhost:3306/user-mannager?...` |
| `DB_USER` | `-Ddb.username=` | `root` |
| **`DB_PASSWORD`** | `-Ddb.password=` | **空** |
| `DB_DRIVER` | `-Ddb.driver=` | `com.mysql.jdbc.Driver` |

```bash
# 例：用环境变量指定口令后启动
DB_PASSWORD=你的密码 java -jar chat-gpt.jar

# 或者用 JVM 参数
java -Ddb.password=你的密码 -jar chat-gpt.jar
```

> 默认口令为空，所以**多数本地 MySQL 需要显式提供 `DB_PASSWORD`**。
> 这样设计是因为公开仓库里不该出现字面口令。

### 3 运行

```bash
# 只跑后端（浏览器访问）
cd backend
mvn -DskipTests package
java -jar target/chat-gpt.jar
# → http://localhost:8078/chat/login.html

# 或者跑桌面端（自动拉起后端 + 内嵌地图）
cd desktop
npm install
npm start
# 或打包成 exe
npm run dist
```

**桌面端会自动 `spawn` 后端 jar**，不需要手动起服务。

---

## 目录结构

```
ai-travel-assistant/
├── backend/                    Spring Boot 后端
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/iflytek/chatgpt/
│       │   ├── agent/          Agent 引擎（核心）
│       │   │   ├── TravelAgentService.java    827 行 · 循环 + 30 个工具 + 提示词
│       │   │   ├── ChatMemoryRepository.java  290 行 · 多会话记忆
│       │   │   ├── AgentSchemaInitializer.java 345 行 · 19 张表
│       │   │   ├── NavigationService.java     544 行 · 路径规划 + 周边搜索
│       │   │   ├── ReverseGeocoder.java       289 行 · 坐标反查地名
│       │   │   ├── WebSearchService.java      296 行 · 联网检索（带缓存）
│       │   │   └── tool/TravelTools.java      845 行 · 工具真实实现
│       │   ├── config/SessionConfig.java       99 行 · 会话持久化
│       │   ├── controller/     10 个接口控制器
│       │   └── service/AiChatService.java      118 行 · 大模型调用
│       └── resources/application.properties
├── frontend/                   原生前端（零构建）
│   ├── chat.html               4,513 行 · 主界面
│   ├── map.html                内嵌地图
│   ├── login.html / reg.html
│   └── style.css
├── desktop/                    Electron 桌面端
│   ├── main.js                 自动拉起后端 + webview 承载地图
│   └── package.json
└── docs/
    ├── 产品文档.md              做什么 + 6 个核心产品决策
    ├── 设计文档.md              怎么实现 + 4 个必须记住的坑
    ├── 产品决策记录.md          为什么这么定 + 代价
    ├── 技术提炼.md              471 行 Agent 接入指南 + 8 条踩坑清单
    ├── LLM-Agent-Harness-MCP-教学.md  四层概念关系与正确用法
    ├── analysis/                开发过程记录
    │   ├── 01-诊断与决策.md      四条断链（含实测数据）
    │   ├── 02-改造前问题记录.md   改造前基线 + 四个 P0 问题的代码位置
    │   ├── 03-UI优化规范.md      设计令牌体系 + 8 批执行顺序
    │   ├── 04-UI分析与取舍.md    界面的真实问题是信息架构而非样式
    │   └── 05-修复任务指令.md    A/B/C/D 四组可执行改动
    └── sql/spot_guide_seed.sql
```

---

## 三个关键设计决定

### 1 会话记忆分两层：会话内 + 跨会话

```
chat_session      会话本体（标题、创建/更新时间）      ← 多话题隔离
chat_message      会话内的每条消息                    ← 单话题上下文
tomcat_sessions   HTTP 会话（登录态）                 ← 重启不丢登录
```

**为什么分三层**：把「用户是谁」（HTTP 会话）、「聊过什么」（chat_message）、
「有哪几个话题」（chat_session）拆开，任何一层失效都不影响另外两层。

### 2 会话持久化用 Tomcat `JDBCStore` 而不是文件

会话写进 MySQL 的 `tomcat_sessions` 表：

```java
JDBCStore store = new JDBCStore();
store.setConnectionURL(DB_URL);
store.setSessionTable("tomcat_sessions");
PersistentManager manager = new PersistentManager();
manager.setStore(store);
manager.setMaxIdleSwap(1);      // 闲置 1 分钟就换出到库
manager.setSaveOnRestart(true); // 关闭时存下来
```

**效果**：关掉桌面端再打开，只要浏览器还带着 `JSESSIONID`，登录态和身份原样恢复。
（已实测：重启后 `/myinfo` 返回同一个游客身份）

### 3 写操作必须可见

`/agent/chat` 返回的不只是文本：

```json
{
  "answer": "...📌 已记住：饮食忌口=不吃辣",
  "actions": [
    {"tool": "savePreference", "summary": "记住偏好：饮食忌口 = 不吃辣"}
  ],
  "sessionId": 12
}
```

前端把 `actions` 渲染成卡片「🔧 本次为你做了 N 件事」。

**只有 14 个写操作生成摘要**，读操作不生成 —— 否则一屏全是卡片，反而没信息量。

---

## 关于「不做什么」

这个项目里有一些**刻意不做**的决定，同样是产品判断：

| 不做 | 理由 |
|---|---|
| 不接完整 Agent 框架（LangChain 等） | 30 个工具用原生 Java 分发就够，引框架反而增加调试成本 |
| 不接 MCP | 工具全在自建库里，直接写 Java 方法更简单 |
| 不用 ORM | 原生 JDBC + PreparedStatement，SQL 可控且防注入 |
| 不引前端框架构建 | 单页 + Vue CDN，改完刷新即生效，省掉整套工具链 |

---

---

## 文档导航

| 想了解 | 看这份 |
|---|---|
| **这个项目做什么** | [docs/产品文档.md](docs/产品文档.md) |
| **怎么实现** | [docs/设计文档.md](docs/设计文档.md) |
| **为什么这么设计**（含代价） | [docs/产品决策记录.md](docs/产品决策记录.md) |
| **Agent 怎么接、坑在哪** | [docs/技术提炼.md](docs/技术提炼.md) |
| **LLM / Agent / Harness / MCP 的关系** | [docs/LLM-Agent-Harness-MCP-教学.md](docs/LLM-Agent-Harness-MCP-教学.md) |
| **开发过程中发现了什么问题、怎么改的** | [docs/analysis/](docs/analysis/) |

> `docs/analysis/` 里是改造过程的原始记录。**一个只展示成品的仓库看不出工程判断的过程**，
> 所以把「问题是怎么被定位的」也一并留下。

---

## 已知缺陷

诚实标注，没有为了显得完整而藏起来：

| # | 缺陷 | 影响 |
|---|---|---|
| 1 | **密码明文存储** | 应改为 BCrypt 哈希 |
| 2 | **30 个工具未合并** | 工具数超出模型有效选择范围（经验值 10–15），深层工具不易被触发 |
| 3 | **自动登录功能未实现** | 「记住密码」勾选逻辑读的是 `value` 而非 `checked`，且未实现自动登录 |
| 4 | **多处功能有后端无界面** | 游记生成、行程时间线、版本回退、同行人面板 |
| 5 | **无单元测试** | 全靠手工验证 |
| 6 | **数据库配置硬编码** | `MySQLUtil` / `SessionConfig` 里写死连接信息，应移到配置 |

---

## 说明

- 本项目基础版本来自课程作业，**AI 接入、多会话、资产闭环、会话持久化、桌面端均为后续迭代**
- 已脱敏：**无真实 API Key、无数据库凭据、无个人姓名与学号**
- 运行前请自行填写 `application.properties` 与 `MySQLUtil.java`
