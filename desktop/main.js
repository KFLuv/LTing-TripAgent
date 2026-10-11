const { app, BrowserWindow, shell, ipcMain, session } = require('electron');
const { spawn } = require('child_process');
const path = require('path');
const http = require('http');

const PORT = 8078;
const CONTEXT = '/chat';
const HOME = `http://localhost:${PORT}${CONTEXT}/login.html`;

let mainWindow = null;
let backend = null;
let spawnedBackend = false;

/** 后端 jar 路径：开发态用 backend/target，打包后从 resources 里取 */
function jarPath() {
    return app.isPackaged
        ? path.join(process.resourcesPath, 'backend', 'chat-gpt.jar')
        : path.join(__dirname, '..', 'backend', 'target', 'chat-gpt.jar');
}

/** 探测后端是否已经起来（HTTP 200 即视为就绪） */
function ping() {
    return new Promise(resolve => {
        const req = http.get(`http://localhost:${PORT}${CONTEXT}/login.html`, res => {
            res.resume();
            resolve(res.statusCode === 200);
        });
        req.on('error', () => resolve(false));
        req.setTimeout(1500, () => {
            req.destroy();
            resolve(false);
        });
    });
}

async function waitForBackend(timeoutMs) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
        if (await ping()) return true;
        await new Promise(r => setTimeout(r, 700));
    }
    return false;
}

/** 把 Java 后端作为本地服务拉起来（不依赖外部 Web 服务器） */
function startBackend() {
    const jar = jarPath();
    backend = spawn('java', ['-jar', jar], {
        cwd: path.dirname(jar),
        windowsHide: true,
        stdio: ['ignore', 'pipe', 'pipe']
    });
    spawnedBackend = true;
    backend.stdout.on('data', d => process.stdout.write('[backend] ' + d));
    backend.stderr.on('data', d => process.stderr.write('[backend] ' + d));
    backend.on('exit', code => {
        console.log('[backend] exited with code', code);
        backend = null;
    });
}

/** 只关掉由本进程拉起的后端，避免误杀用户自己开的实例 */
function stopBackend() {
    if (spawnedBackend && backend && !backend.killed) {
        try {
            backend.kill();
        } catch (e) {
            console.error('关闭后端失败:', e);
        }
    }
    backend = null;
}

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 1440,
        height: 920,
        minWidth: 1024,
        minHeight: 700,
        title: 'AI旅游助手',
        autoHideMenuBar: true,
        backgroundColor: '#f2f4f7',
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
            // 关键：开启 webview，前端才能把高德地图真正嵌进页面（而不是跳外部浏览器）
            webviewTag: true
        }
    });

    mainWindow.loadURL(HOME);
    mainWindow.on('closed', () => {
        mainWindow = null;
    });
}

// 站内链接留在本窗口，站外链接交给系统默认浏览器
app.on('web-contents-created', (_event, contents) => {
    contents.setWindowOpenHandler(({ url }) => {
        if (/^https?:\/\/localhost:/.test(url)) {
            return { action: 'allow' };
        }
        shell.openExternal(url);
        return { action: 'deny' };
    });
});

app.whenReady().then(async () => {
    // 内嵌高德地图的 <webview> 需要定位权限，否则画不出「我的位置」（普通 iframe 用 allow 属性委托）
    session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => {
        callback(permission === 'geolocation');
    });

    ipcMain.handle('open-external', (_event, url) => {
        if (typeof url === 'string' && /^https?:\/\//i.test(url)) {
            shell.openExternal(url);
        }
    });

    if (!(await ping())) {
        startBackend();
    }
    const ready = await waitForBackend(120000);
    if (!ready) {
        console.error('后端未能在规定时间内启动，请检查是否已构建 backend/target/chat-gpt.jar');
    }

    createWindow();

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) {
            createWindow();
        }
    });
});

app.on('window-all-closed', () => {
    stopBackend();
    app.quit();
});

app.on('before-quit', stopBackend);
process.on('exit', stopBackend);
