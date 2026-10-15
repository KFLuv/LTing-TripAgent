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
2. **开发者工具里勾上「不校验合法域名」**：右侧「详情 → 本地设置 → 不校验合法域名、web-view、TLS 版本以及 HTTPS 证书」。
   本地调试用的是 `http://127.0.0.1:8078`，不开这个开关请求会被直接拦掉。
   （`project.config.json` 里已经写成 `"urlCheck": false`，但工具里那个勾选框优先级更高。）
3. **后端地址**：改 `config.js` 里的 `baseUrl`（默认 `http://127.0.0.1:8078/chat/`）。

---

## 三、真机 / 上线才需要的东西（现在就别管）

| 事项 | 说明 |
|---|---|
| **HTTPS + 域名** | 小程序不允许请求 `http://`，也不允许 IP 直连；真机必须 `https://你的域名`，且域名要完成 ICP 备案 |
| **配置请求域名** | mp.weixin.qq.com →「开发管理 → 开发设置 → 服务器域名」把域名加进 request 合法域名 |
| **定位接口** | `app.json` 已声明 `requiredPrivateInfos: ["getLocation", "chooseLocation"]`；`wx.getLocation` 还需在小程序后台申请开通。没开通时会自动退回兜底坐标（`config.js` 里的 `fallbackLocation`），页面会如实说明 |
| **AppID** | 本地开发用你自己的 AppID（`LTing/project.config.json` 里已有 `wx9844d0922ad34ce6`）；换电脑或换人调试时，也可以在工具里点「测试号」 |

---

## 四、已经搬过来的页面

| 页面 | 对应后端接口 |
|---|---|
| 出发（首页） | `myinfo` · `trips/list` · `moment/list` · `travel-log/list` |
| AI 管家（对话） | `agent/available` · `agent/history` · `agent/chat` · `agent/session/*` |
| 行程本 / 行程详情 | `trips/list` · `trips/get` · `trips/save` · `trips/delete` · `trips/version/rollback` |
| 附近吃喝玩乐 | `nearby/search` · `nearby/detail` · `nearby/menu/list` · `nearby/review/list` + `wx.openLocation` 一键导航 |
| 我的（资料 / 偏好档案） | `profile/get` · `profile/save` · `preference/list` · `preference/item/save-current` |
| 登录 / 注册 / 游客 | `login` · `reg` · `guest` · `myinfo` |

**还没搬的**（后端接口都已就绪，按同样套路加页面即可）：
动态广场与好友（`moment/*`、`friend/*`、`user/home`）、打卡（`travel-log/*`）、
同行人与证件（`companion/*`）、预算与行李（`toolbox/*`）、偏好模板（`preference/template/*`）。

---

## 五、几个已知限制

- 小程序端不做 `web-view` 套 H5：个人主体小程序不支持 `web-view`，且那条路要 HTTPS + 备案 + 业务域名，等于把桌面端的问题原样搬过来。
- 打卡图片、头像还没接上传：后端目前没有上传接口，桌面端走的是图片本身的字段，小程序端需要先加一个上传接口再补。
- 主题色沿用桌面端令牌（`#f5f5f7` 底 · `#0071e3` 主色 · 圆角最小 16rpx），没有另起一套。
