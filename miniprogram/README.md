# AI 旅游助手 · 微信小程序端

原生小程序（零构建），直接调桌面端那套后端接口（`http://<host>:8078/chat/`），
身份沿用后端的会话（JSESSIONID，微信网络层会自动保存 `Set-Cookie`）。

---

## 一、为什么开发者工具报 `app.json 未找到`

微信开发者工具的「项目目录」必须**正好是小程序根目录**（有 `app.json` 的那一层）。
如果目录指到了仓库根目录、`frontend/`，或者上层文件夹（例如项目根 `LTing/`），
就会报 `模拟器启动失败 error:app.json: 在项目目录未找到 app.json`。

两种情况都能跑，选一种：

| 方式 | 项目目录填什么 | 说明 |
|---|---|---|
| **A. 直接导入小程序目录**（推荐） | `AITravelGuide/miniprogram` | 简单直接 |
| **B. 保留现有项目**（目录是 `LTing/`） | 保持 `LTing/` 不变 | 需要在 `LTing/project.config.json` 里加一行 `"miniprogramRoot": "AITravelGuide/miniprogram/"` |

方式 B 我已经在你本机配置好了：`E:\PersonalProduct\LTing\project.config.json`

---

## 二、跑起来的三件事

1. **先起后端**（小程序的每个页面都要调接口）：
   ```bash
   cd AITravelGuide/backend
   mvn -DskipTests package
   java -jar target/chat-gpt.jar       # → http://localhost:8078/chat/
   ```
   没装 Java / Maven 的话，用工程自带运行时也行：
   ```powershell
   cd AITravelGuide\backend\target
   ..\..\desktop\build\jre\bin\java.exe -jar chat-gpt.jar --server.port=8078
   ```
2. **开发者工具里勾上「不校验合法域名」**：右侧「详情 → 本地设置 → 不校验合法域名、web-view、TLS 版本以及 HTTPS 证书」。
   本地调试用的是 `http://127.0.0.1:8078`，不开这个开关请求会被直接拦掉。
   （`project.config.json` 里已经写成 `"urlCheck": false`，但工具里那个勾选框优先级更高。）
3. **后端地址**：改 `config.js` 里的 `baseUrl`（默认 `http://127.0.0.1:8078/chat/`）。

---

## 二之二、登录为什么不走 Cookie（重要）

小程序端的身份走**令牌**，不是 Cookie：

| | 桌面端 / 网页端 | 小程序端 |
|---|---|---|
| 身份载体 | `JSESSIONID`（服务端会话 + Cookie） | `X-Auth-Token` 请求头（缓存在客户端 storage） |
| 登录 | `POST /login`（302 → home.html） | `POST /api/auth/login` → 返回 `{token}` |
| 游客 | `POST /guest` | `POST /api/auth/guest` → 返回 `{token}` |
| 退出 | 清 Cookie | `POST /api/auth/logout` + 清本地令牌 |

原因是服务端会话 Cookie 带的是 `SameSite=Lax`，而小程序发出去的是跨站请求，
Chromium 系内核（开发者工具就是）会直接丢掉这条 `Set-Cookie`——表现就是
「登录接口返回成功，下一个请求又说没登录」，界面上看着像「密码错误 / 游客进不去」。
后端的 `config/TokenAuthFilter` 会把 `X-Auth-Token` 翻译成会话里的登录身份，
所以**既有 82 个接口一行都不用改**，桌面端那套 Cookie 机制也完全不受影响。

> 需要后端是**带令牌接口的新版本**（`/api/auth/login`、`/api/auth/guest`、`/api/auth/me`、`/api/auth/logout`）。
> 老的 jar / 老 exe 里没有这几个接口，小程序登录会提示「登录失败，请重试」。

---

## 三、真机 / 上线才需要的东西（现在就别管）

| 事项 | 说明 |
|---|---|
| **HTTPS + 域名** | 小程序不允许请求 `http://`，也不允许 IP 直连；真机必须 `https://你的域名`，且域名要完成 ICP 备案 |
| **配置请求域名** | mp.weixin.qq.com →「开发管理 → 开发设置 → 服务器域名」把域名加进 request 合法域名 |
| **定位接口** | `app.json` 已声明 `requiredPrivateInfos: ["getLocation", "chooseLocation"]`；`wx.getLocation` 还需在小程序后台申请开通。没开通时会自动退回兜底坐标（`config.js` 里的 `fallbackLocation`），页面会如实说明 |
| **AppID** | 本地开发用你自己的 AppID（`LTing/project.config.json` 里已有 `wx9844d0922ad34ce6`）；换电脑或换人调试时，也可以在工具里点「测试号」 |

---

## 四、已经搬过来的页面（对齐桌面端 9 个模块）

桌面端导航：出发 · AI 管家 · 动态广场 · 行程本 · 打卡 · 个人资料 · 偏好档案 · 附近吃喝玩乐 · 账号管理，
小程序端已全部对上（tabBar 放 5 个常用入口，其余从「出发」进）：

| 小程序页面 | 对应桌面端 | 用到的接口 |
|---|---|---|
| 出发（首页） | home | `myinfo` · `trips/list` · `moment/list` · `travel-log/list` |
| AI 管家 | chat | `agent/available` · `agent/history` · `agent/chat` · `agent/session/*` |
| 动态广场（+我的动态+发布+好友入口） | square / moments | `moment/square` · `moment/list` · `moment/save` · `moment/delete` |
| 好友 / 他人主页 | friends / user | `friend/list` · `friend/add` · `friend/delete` · `user/home` |
| 行程本 / 行程详情（时间线 · 版本回退 · **预算 · 行李**） | trips | `trips/*` · `toolbox/budget/*` · `toolbox/packing/*` |
| 打卡（可选附近店铺、带图、独立于动态） | checkin | `travel-log/list` · `travel-log/save` · `travel-log/delete` · `nearby/search` |
| 附近吃喝玩乐（评分/菜单/评价 + 一键导航） | nearby | `nearby/search` + 「我的位置」+ `wx.openLocation` |
| 商户详情页（店铺图 · 评级 · 地址 · 电话 · 菜单 · **打卡记录** · 点评 + 写点评） | poi | `nearby/detail` · `nearby/menu/list` · `nearby/review/list\|save` · `travel-log/by-poi` |
| 我的（资料 · **同行人** · **证件票据** · 偏好档案） | profile / preferences | `profile/*` · `companion/*` · `toolbox/document/*` · `preference/list` · `preference/item/save-current` |
| 账号管理（改密码 · 邮箱 · 注销） | account | `updateUserInfo` · `account/unbind-email` · `account/delete` |
| 登录 / 注册 / 游客 | login / reg | `api/auth/*`（令牌）· `reg` |

**有意没搬的两处**：桌面端的「内嵌地图」页在小程序里换成 `wx.openLocation`（直接调起系统地图，
导航体验更好）；图片走 base64（`images` 字段本来就是 JSON 数组），不依赖任何上传接口。

---

## 四之二、定位与导航

| 位置 | 行为 |
|---|---|
| 附近吃喝玩乐 · 进页面 | 自动 `wx.getLocation`（gcj02）→ 上报 `/agent/location` → 按真实坐标搜店；拿不到就如实显示「没拿到定位」，不再悄悄用兜底坐标 |
| 附近吃喝玩乐 · 右上「我的位置」 | 重新定位；如果之前被拒过，弹窗引导去系统设置开权限，回来再自动刷新列表 |
| 商户详情 · 「我的位置」 | 同上，并显示当前位置状态 |
| 商户详情 / 列表 · 一键导航 | `wx.openLocation(纬度, 经度, 店名, 地址)` 调起系统地图，带目的地名称与详细地址 |
| AI 管家 · 顶部「我的位置」 | 定位并 `force=true` 上报，管家后续排行程会以当前位置为起点 |
| AI 管家 · 回答里的「在地图里打开」 | 回答里若带高德导航链接（`position=经度,纬度`），自动解析出坐标并给出按钮 |

### 高德链接的三种真实形态（解析都覆盖）

```text
https://www.amap.com/dir?type=car&to%5Blnglat%5D=113.323464,23.106483&to%5Bname%5D=广州塔
https://www.amap.com/dir?type=car&to[lnglat]=113.323464,23.106483&to[name]=广州塔
https://uri.amap.com/marker?position=113.323464,23.106483&name=广州塔
```

⚠️ 注意 `to[lnglat]` 的方括号在真实回答里是 **URL 编码**的（`%5Blnglat%5D`），
解析前必须先 `decodeURIComponent` 一次——这正是「只给复制链接、画不出地图」的原因。
顺序固定是 **经度,纬度**，画图时要换成 `openLocation(latitude=纬度, longitude=经度)`。

管家里除了回答内的地图，「地图」按钮任何时候都能一键展开内嵌地图看自己的位置。

### 定位失败怎么定位问题

页面会把 `getLocation` 的**原始 errMsg** 直接显示出来。开发者工具里常见两种：

| 显示 | 含义 | 怎么办 |
|---|---|---|
| `getLocation:fail auth deny` | 之前点过"拒绝" | 「去设置」里打开位置权限 |
| `getLocation:fail 系统定位未授权` | 工具里没开位置模拟 | 模拟器右上角「…」→ 位置模拟 → 选一个地点 |

> 导航要能"从哪出发"，必须小程序**自身**有定位权限；开发者工具里如果没开位置模拟，
> 系统地图会提示定位失败——这不是导航地址错了，是"我在哪"没拿到。

---

## 四之三、大众点评为什么不能像桌面端那样内嵌

桌面端是把点评页面**嵌在窗口里**（同源 iframe / Electron 内嵌页）。小程序没有这个东西：

| 手段 | 小程序能不能用 |
|---|---|
| `iframe` / 内联网页 | ❌ 小程序没有 DOM，也没有 iframe |
| `web-view` 组件 | ⚠️ 仅**企业主体**小程序可用，且要求 HTTPS + ICP 备案 + 在后台配置「业务域名」；个人主体用不了 |
| 抓取点评网页 | ⚠️ 点评没有公开 API，抓取属于绕过反爬，随时失效，也不适合放进要过审的小程序 |

所以小程序端给的是**能落地的那部分**，都放在商户详情页里：

1. **真实点评数据**：`nearby/review/list`（自己写的评价）+ `nearby/menu/list`（菜单）+ 店铺图 / 评级 / 人均 / 地址 / 电话；
2. **「复制店名+地址去点评搜」**：一键复制 `店名 + 城市 + 详细地址`，用户粘到大众点评 App 里就能搜到——桌面端当初也是靠"店名+地址"去搜的；
3. 真要做内嵌，只有一条合法路径：企业主体小程序 + HTTPS 备案域名 + `web-view` + 业务域名白名单。

---

## 五、几个已知限制

- 小程序端不做 `web-view` 套 H5：个人主体小程序不支持 `web-view`，且那条路要 HTTPS + 备案 + 业务域名，等于把桌面端的问题原样搬过来。
- 打卡图片、头像还没接上传：后端目前没有上传接口，桌面端走的是图片本身的字段，小程序端需要先加一个上传接口再补。
- 主题色沿用桌面端令牌（`#f5f5f7` 底 · `#0071e3` 主色 · 圆角最小 16rpx），没有另起一套。

---

## 六、`request:fail` 怎么排查（登录最常见的报错）

`request:fail` 不是小程序的问题，是**它连不上后端**。按顺序看三件事：

1. **后端在不在跑**：浏览器打开 <http://127.0.0.1:8078/chat/login.html>，能出登录页就说明在跑。
2. **没在跑就这样起**（用项目自带 JRE，不用装 Java）：
   ```powershell
   cd AITravelGuide\backend\target
   ..\..\desktop\build\jre\bin\java.exe -jar chat-gpt.jar --server.port=8078
   ```
   或者直接双击运行桌面端（Electron 会把同一个后端拉起来）。
3. **看控制台里的 `errMsg`**（`utils/request.js` 会把原文打进 console）：
   | errMsg 关键字 | 原因 |
   |---|---|
   | `不在以下 request 合法域名列表中` | 没勾「不校验合法域名」 |
   | `fail 失败` / 无关键字 | 后端没起 / 端口不是 8078 / 防火墙拦了 |
   | `timeout` | 请求发出去了，Agent 跑得久，去「历史」里看会话 |

登录接口本身是好的（实测：错口令返回「用户名或密码错误」，游客登录后 `myinfo` 返回 `login:true`），
所以只要后端在 8078 上跑着，登录页点「登录 / 游客进入」都能进。
