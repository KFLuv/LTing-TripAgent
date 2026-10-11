const { contextBridge, ipcRenderer } = require('electron');

// 只暴露最小能力给页面：告诉前端"我在桌面端"，以及"用系统浏览器打开某链接"。
// 页面通过它判断能否使用 <webview> 真正把地图嵌进来。
contextBridge.exposeInMainWorld('desktopBridge', {
    isDesktop: true,
    openExternal: (url) => ipcRenderer.invoke('open-external', url)
});
