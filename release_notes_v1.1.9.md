# LTing v1.1.9：移除后端控制台窗口

## 问题

Windows 免安装版启动时会看到额外的命令行窗口。Electron 主程序和便携外壳均为 GUI 子系统，黑框来自随包启动的 `java.exe`；它是 Windows 控制台程序。

## 修复

- 后端启动器由 `java.exe` 改为 `javaw.exe`。
- `javaw.exe` 是 Windows GUI 子系统，不会创建控制台窗口。
- 保留现有的 `windowsHide: true`、本机 Key 注入、健康检查和进程退出清理。

## 验证

- 打包资源中的 `AITravelGuide.exe`：`WINDOWS_GUI`。
- 便携安装包：`WINDOWS_GUI`。
- 内置 `java.exe`：`WINDOWS_CUI`。
- 内置 `javaw.exe`：`WINDOWS_GUI`。
- 实测后端进程为 `javaw`，`MainWindowHandle=0`。
- `/chat/agent/available` 返回 `available=true / configured=true`。
- 免安装包：`LTing-1.1.9-免安装版.exe`，147,659,611 bytes。
- 免安装包 SHA-256：`C488BB653F0907CCF5A8B967C6F66C0BA8E25E7145A67F63AF87F99DA87154C2`。
- 可执行 jar SHA-256：`02ACB431DFC41A3B0BE62D3246661C8AFAFEDABC36CB680F5044B19B21AFFD43`。
