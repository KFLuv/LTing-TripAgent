/*
 * 多页面共用的外壳逻辑：左侧导航 + 登录校验 + 统一的接口调用。
 * 不引入框架：这几个页面结构简单，省掉构建链与额外的 CDN 依赖。
 */
(function () {
    'use strict';

    var PAGES = [
        {href: 'home.html', icon: 'fa-compass', label: '出发', group: '规划'},
        {href: 'workbench.html', icon: 'fa-compass-drafting', label: '出行工作台', group: '规划'},
        {href: 'trips.html', icon: 'fa-book', label: '行程本', group: '旅行资产'},
        {href: 'square.html', icon: 'fa-fire', label: '动态广场', group: '旅行资产'},
        {href: 'checkin.html', icon: 'fa-location-dot', label: '打卡', group: '旅行资产'},
        {href: 'nearby.html', icon: 'fa-utensils', label: '附近吃喝玩乐', group: '旅行资产'},
        {href: 'profile.html', icon: 'fa-id-badge', label: '个人资料', group: '个人设置'},
        {href: 'preferences.html', icon: 'fa-sliders', label: '偏好档案', group: '个人设置'},
        {href: 'account.html', icon: 'fa-user-gear', label: '账号管理', group: '个人设置'},
        {href: 'settings.html', icon: 'fa-gear', label: '系统设置', group: '个人设置'}
    ];

    /** 可见范围三档的下拉（各页面复用同一套选项，避免文案不一致） */
    var VIS_OPTIONS = [
        {value: 'public', label: '公开'},
        {value: 'friends', label: '仅好友'},
        {value: 'private', label: '仅自己'}
    ];

    function visSelect(id, value) {
        return '<select id="' + id + '">' + VIS_OPTIONS.map(o =>
            '<option value="' + o.value + '"' + (o.value === (value || 'public') ? ' selected' : '') + '>'
            + o.label + '</option>').join('') + '</select>';
    }

    /** 没设头像时的占位图（内联 SVG，避免多一次请求） */
    var DEFAULT_AVATAR = 'data:image/svg+xml;utf8,' + encodeURIComponent(
        '<svg xmlns="http://www.w3.org/2000/svg" width="72" height="72">'
        + '<rect width="72" height="72" fill="#eef2f7"/>'
        + '<circle cx="36" cy="29" r="12" fill="#c8d4e0"/>'
        + '<ellipse cx="36" cy="58" rx="20" ry="13" fill="#c8d4e0"/></svg>');

    /** 转义，所有插入 innerHTML 的文本都要过一遍 */
    function esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    /** 头像标签：src 为空时用占位图 */
    function avatar(src, sizeClass) {
        return '<img class="avatar ' + (sizeClass || 'sm') + '" alt="头像" src="'
            + esc(src && src.length ? src : DEFAULT_AVATAR) + '">';
    }

    /** 可见权限的中文标签 */
    function visLabel(v) {
        return v === 'friends' ? '仅好友' : (v === 'private' ? '仅自己' : '公开');
    }

    /** 读地址栏参数 */
    function qs(name) {
        return new URLSearchParams(location.search).get(name) || '';
    }

    /**
     * 一键导航链接：高德 URI API。
     * 商户坐标本来就来自高德（GCJ-02），所以直接 coordinate=gaode；
     * 起点不写，交给高德用「我的位置」。没有坐标时退回关键词搜索。
     */
    function navUrl(poi) {
        const name = encodeURIComponent(poi.name || '目的地');
        if (poi.lng && poi.lat) {
            return 'https://uri.amap.com/navigation?to=' + poi.lng + ',' + poi.lat + ',' + name
                + '&mode=car&coordinate=gaode&callnative=1';
        }
        return 'https://uri.amap.com/search?keyword=' + name + '&callnative=1';
    }

    /** 应用内导航页：保留目的地、名称和出行方式，由 map.html 负责定位和路线。 */
    function navigationUrl(poi, mode) {
        const params = new URLSearchParams({
            name: poi.name || '目的地',
            mode: mode || poi.mode || 'driving',
            nav: '1'
        });
        if (poi.lng && poi.lat) params.set('dest', poi.lng + ',' + poi.lat);
        else params.set('keyword', poi.name || '');
        return 'map.html?' + params.toString();
    }

    /**
     * 内嵌面板里要加载的地图页。
     * <p>
     * 刻意用高德 PC 地图本体（和对话页内嵌的是同一个页面），而不是 navUrl 那个
     * 唤起客户端的深链落地页——后者是简版页面，嵌进来会跟对话页长得完全不一样。
     * 真正要"开始导航"时，面板右上角的「在浏览器打开」再交给 navUrl。
     */
    function mapEmbedUrl(poi) {
        return 'https://www.amap.com/search?query=' + encodeURIComponent(poi.name || '');
    }

    /**
     * 大众点评：没有 shopId 拼不出精确详情页链接，所以走"按店名搜"。
     * 拿到真实 shopId（授权通道/你自己粘贴）后换成 /shop/<id> 即可直达。
     */
    function dianpingUrl(poi) {
        // 用「店铺名 + 地址」搜：点评上同名店很多（例如"肯德基（绿地中央餐厅）"），
        // 只给店名容易搜到别家，带上地址/分店名命中率更高。
        const name = (poi.name || '').trim();
        // 店名里已经带分店（如「肯德基(绿地中央餐厅)」「太平馆(北京路店)」）就直接用；
        // 只有光秃秃一个品牌名时才补地址第一段，避免关键词太长反而搜不到。
        let key = name;
        if (name.indexOf('(') < 0 && name.indexOf('（') < 0 && poi.address) {
            key = (name + ' ' + poi.address.split(/[，,]/)[0]).trim();
        }
        // 点评的搜索路径必须带城市编号，缺省用 0 会直接落到首页——这就是"跳过去没在搜"的原因。
        const cityId = CITY_IDS[(poi.city || '').replace(/[市省]$/, '')] || '';
        if (!cityId) {
            return {url: 'https://www.dianping.com/', keyword: key, searched: false};
        }
        return {url: 'https://www.dianping.com/search/keyword/' + cityId + '/0_' + encodeURIComponent(key),
                keyword: key, searched: true};
    }

    /**
     * 大众点评的城市编号（用于拼搜索路径）。
     * 覆盖常用城市；没有编号时退回点评首页，并把关键词显示出来让用户粘一次。
     */
    var CITY_IDS = {
        '上海': 1, '北京': 2, '杭州': 3, '广州': 4, '南京': 5, '苏州': 6, '深圳': 7,
        '成都': 8, '重庆': 9, '天津': 10, '武汉': 11, '沈阳': 12, '青岛': 13, '大连': 14,
        '厦门': 15, '长沙': 16, '西安': 17, '郑州': 18, '济南': 19, '哈尔滨': 20,
        '昆明': 21, '合肥': 22, '福州': 23, '宁波': 24, '无锡': 25, '南昌': 26,
        '贵阳': 27, '南宁': 28, '太原': 29, '石家庄': 30, '兰州': 31, '海口': 32,
        '乌鲁木齐': 33, '呼和浩特': 34, '银川': 35, '西宁': 36, '拉萨': 37
    };

    /** 打开外链：桌面端交给系统浏览器，网页端新开标签 */
    function openExternal(url) {
        if (window.desktopBridge && window.desktopBridge.openExternal) {
            window.desktopBridge.openExternal(url);
        } else {
            window.open(url, '_blank', 'noopener');
        }
    }

    var embedEl = null;
    var embedFrame = null;

    /**
     * 内嵌打开一个页面（默认给一键导航用，不再跳外部浏览器）。
     * 桌面端（Electron）用 webview，普通浏览器退化为 iframe——与对话页里那套一致。
     * 面板可拖左边缘调宽，ESC 或点关闭收起。
     */
    function openEmbedded(url, title, externalUrl) {
        if (!url) {
            return;
        }
        if (!embedEl) {
            embedEl = document.createElement('div');
            embedEl.className = 'shell-embed';
            embedEl.innerHTML = ''
                + '<div class="shell-embed-resizer" title="拖动调整宽度"></div>'
                + '<div class="shell-embed-head"><i class="fas fa-map-location-dot"></i>'
                + '<span class="shell-embed-title"></span>'
                + '<span class="shell-embed-actions">'
                + '<button data-act="external"><i class="fas fa-arrow-up-right-from-square"></i> 在浏览器打开</button>'
                + '<button data-act="close"><i class="fas fa-times"></i> 关闭</button>'
                + '</span></div>'
                + '<div class="shell-embed-body"></div>';
            document.body.appendChild(embedEl);
            embedEl.querySelector('[data-act="close"]').addEventListener('click', closeEmbedded);
            embedEl.querySelector('[data-act="external"]').addEventListener('click', () => {
                openExternal(embedEl.dataset.external || embedEl.dataset.url || url);
            });
            embedEl.querySelector('.shell-embed-resizer').addEventListener('mousedown', startEmbedResize);
            document.addEventListener('keydown', e => {
                if (e.key === 'Escape') {
                    closeEmbedded();
                }
            });
        }
        embedEl.dataset.url = url;
        embedEl.dataset.external = externalUrl || '';
        embedEl.querySelector('.shell-embed-title').textContent = title || '地图';
        if (!embedEl.dataset.customWidth) {
            applyEmbedWidth(defaultEmbedWidth());
        }
        document.body.classList.add('shell-embed-open');

        const body = embedEl.querySelector('.shell-embed-body');
        const isElectron = !!(window.desktopBridge && window.desktopBridge.isDesktop) || /Electron/i.test(navigator.userAgent);
        body.innerHTML = '';
        if (isElectron) {
            embedFrame = document.createElement('webview');
            embedFrame.setAttribute('allowpopups', '');
            body.appendChild(embedFrame);
            embedFrame.style.width = '100%';
            embedFrame.style.height = '100%';
            embedFrame.setAttribute('src', new URL(url, location.href).href);
        } else {
            embedFrame = document.createElement('iframe');
            embedFrame.setAttribute('allow', 'geolocation');
            embedFrame.setAttribute('referrerpolicy', 'no-referrer');
            embedFrame.style.transformOrigin = '0 0';
            body.appendChild(embedFrame);
            embedFrame.setAttribute('src', url);
            // 高德 PC 页最小宽度近千像素，窄面板会被裁掉右边；
            // 先按桌面宽度渲染再等比缩小——与对话页内嵌地图同一套做法，保证两边长得一样。
            embedFrame.style.width = '1000px';
            requestAnimationFrame(() => fitFrame());
        }
        embedEl.classList.add('show');
    }

    /** 面板宽度与内容区让宽共用一个变量，拖宽度时两边同步 */
    function applyEmbedWidth(w) {
        const px = Math.round(w);
        embedEl.style.setProperty('--shell-embed-w', px + 'px');
        document.documentElement.style.setProperty('--shell-embed-w', px + 'px');
    }

    /** 把跨域地图页等比缩放进面板 */
    function fitFrame() {
        if (!embedEl || !embedFrame || embedFrame.tagName !== 'IFRAME') {
            return;
        }
        const body = embedEl.querySelector('.shell-embed-body');
        const w = body.clientWidth;
        const h = body.clientHeight;
        if (!w || !h) {
            return;
        }
        const base = 1000;
        const scale = w >= base ? 1 : w / base;
        embedFrame.style.width = base + 'px';
        embedFrame.style.transform = scale === 1 ? '' : 'scale(' + scale + ')';
        embedFrame.style.height = Math.round(h / scale) + 'px';
    }

    function closeEmbedded() {
        if (embedEl) {
            embedEl.classList.remove('show');
        }
        document.body.classList.remove('shell-embed-open');
    }

    function defaultEmbedWidth() {
        // 面板默认占窗口 46%（上限 680）：内容区还要留得下正文，不能把页面挤没
        return Math.max(360, Math.min(680, Math.round(window.innerWidth * 0.46)));
    }

    function startEmbedResize(e) {
        e.preventDefault();
        const startX = e.clientX;
        const startW = embedEl.getBoundingClientRect().width;
        const min = 380;
        const max = Math.max(min + 40, Math.min(1400, window.innerWidth - 320));
        const resizer = embedEl.querySelector('.shell-embed-resizer');
        resizer.classList.add('dragging');
        const onMove = ev => {
            const w = Math.min(max, Math.max(min, startW + (startX - ev.clientX)));
            applyEmbedWidth(w);
        };
        const onUp = () => {
            embedEl.dataset.customWidth = '1';
            resizer.classList.remove('dragging');
            window.removeEventListener('mousemove', onMove);
            window.removeEventListener('mouseup', onUp);
            document.body.style.userSelect = '';
            fitFrame();
        };
        document.body.style.userSelect = 'none';
        window.addEventListener('mousemove', onMove);
        window.addEventListener('mouseup', onUp);
    }

    // 窗口拉伸时：没手动调过宽度就跟着窗口走，并让内嵌页重新等比缩放
    window.addEventListener('resize', () => {
        if (!embedEl || !embedEl.classList.contains('show')) {
            return;
        }
        if (!embedEl.dataset.customWidth) {
            applyEmbedWidth(defaultEmbedWidth());
        }
        fitFrame();
    });

    /** 统一的 POST：表单编码 + 带 Cookie，返回 JSON（失败抛错） */
    async function api(path, params) {
        var body = new URLSearchParams();
        Object.keys(params || {}).forEach(function (k) {
            if (params[k] !== undefined && params[k] !== null) {
                body.append(k, params[k]);
            }
        });
        var res = await fetch(path, {
            method: 'POST',
            credentials: 'include',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: body.toString()
        });
        if (!res.ok) {
            // 后端出错时也会带 JSON 说明（例如「当前密码不正确」），尽量读出来给用户看
            const text = await res.text();
            try {
                const parsed = JSON.parse(text);
                if (parsed && parsed.status) {
                    return parsed;
                }
            } catch (e) { /* 不是 JSON 就按网络错误处理 */ }
            throw new Error('HTTP ' + res.status);
        }
        return res.json();
    }

    /** 渲染左侧导航；active 传当前页文件名 */
    function renderNav(active) {
        var host = document.getElementById('nav');
        if (!host) {
            return;
        }
        var html = '<div class="nav-brand"><i class="fas fa-route"></i><span>AI旅游助手</span></div>';
        var groups = [];
        PAGES.forEach(function (p) {
            if (groups.indexOf(p.group) < 0) groups.push(p.group);
        });
        groups.forEach(function (group) {
            html += '<div class="nav-section-label">' + group + '</div>';
            PAGES.filter(function (p) { return p.group === group; }).forEach(function (p) {
                var on = p.href === active ? ' on' : '';
                html += '<a class="nav-item' + on + '" href="' + p.href + '">'
                    + '<i class="fas ' + p.icon + '"></i><span>' + p.label + '</span></a>';
            });
        });
        // 左下角账号区：头像 + 昵称，点击进账号管理页
        html += '<div class="nav-foot">'
            + '<button type="button" class="nav-theme" data-theme-toggle>'
            + '<span class="theme-glyph" data-theme-glyph>◐</span>'
            + '<span data-theme-label>主题</span></button>'
            + '<a class="nav-account" href="account.html">'
            + '<img class="avatar sm" id="nav-avatar" alt="头像" src="' + DEFAULT_AVATAR + '">'
            + '<span class="nav-account-text"><b id="nav-user">…</b><i>账号管理</i></span></a>'
            + '<a class="nav-logout" href="login.html">退出登录</a></div>';
        host.innerHTML = html;
        var themeButton = host.querySelector('[data-theme-toggle]');
        if (themeButton && window.ShellTheme) {
            themeButton.addEventListener('click', function () { ShellTheme.cycle(); });
            ShellTheme.setMode(ShellTheme.getMode());
        }
    }

    /** 读当前登录用户；未登录直接跳登录页 */
    async function currentUser() {
        try {
            var res = await fetch('myinfo', {method: 'POST', credentials: 'include'});
            var data = await res.json();
            if (data.login !== 'true') {
                location.href = 'login.html';
                return null;
            }
            var host = document.getElementById('nav-user');
            if (host) {
                host.textContent = data.name || '';
            }
            // 顺带把头像也填上（拿不到就用占位图）
            fetch('profile/get', {method: 'POST', credentials: 'include'})
                .then(r => r.json())
                .then(p => {
                    const img = document.getElementById('nav-avatar');
                    if (img && p && p.profile && p.profile.avatar) {
                        img.src = p.profile.avatar;
                    }
                })
                .catch(() => { /* 头像拿不到不影响导航 */ });
            return data;
        } catch (e) {
            location.href = 'login.html';
            return null;
        }
    }

    /** 浏览器定位，返回 {lat, lng, accuracy}；失败抛错 */
    function locate() {
        return new Promise(function (resolve, reject) {
            if (!navigator.geolocation) {
                reject(new Error('当前浏览器不支持定位'));
                return;
            }
            if (window.isSecureContext === false) {
                reject(new Error('当前页面不是 HTTPS/localhost，浏览器禁止定位'));
                return;
            }
            navigator.geolocation.getCurrentPosition(
                function (pos) {
                    resolve({
                        lat: pos.coords.latitude,
                        lng: pos.coords.longitude,
                        accuracy: pos.coords.accuracy
                    });
                },
                function (err) {
                    reject(new Error(err.code === 1 ? '定位权限被拒绝，请允许后重试'
                        : (err.code === 3 ? '定位超时，请重试' : '定位失败')));
                },
                {enableHighAccuracy: true, maximumAge: 5000, timeout: 20000}
            );
        });
    }

    /** 图片压缩成 data URL：长边不超过 maxSide，避免把库写爆 */
    function fileToDataUrl(file, maxSide) {
        return new Promise(function (resolve, reject) {
            var reader = new FileReader();
            reader.onerror = function () {
                reject(new Error('图片读取失败'));
            };
            reader.onload = function () {
                var img = new Image();
                img.onerror = function () {
                    reject(new Error('图片解析失败'));
                };
                img.onload = function () {
                    var scale = Math.min(1, maxSide / Math.max(img.width, img.height));
                    var canvas = document.createElement('canvas');
                    canvas.width = Math.round(img.width * scale);
                    canvas.height = Math.round(img.height * scale);
                    canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
                    resolve(canvas.toDataURL('image/jpeg', 0.82));
                };
                img.src = reader.result;
            };
            reader.readAsDataURL(file);
        });
    }

    var aiContext = {};
    function setAiContext(context) {
        aiContext = context || {};
        var hint = document.getElementById('ai-context-hint');
        if (hint) {
            var label = aiContext.label || aiContext.page || '';
            hint.textContent = label ? '当前上下文：' + label : '';
            hint.style.display = label ? 'block' : 'none';
        }
    }

    window.Shell = {
        esc: esc,
        api: api,
        renderNav: renderNav,
        currentUser: currentUser,
        locate: locate,
        fileToDataUrl: fileToDataUrl,
        avatar: avatar,
        visLabel: visLabel,
        qs: qs,
        visSelect: visSelect,
        VIS_OPTIONS: VIS_OPTIONS,
        navUrl: navUrl,
        navigationUrl: navigationUrl,
        mapEmbedUrl: mapEmbedUrl,
        dianpingUrl: dianpingUrl,
        openExternal: openExternal,
        openEmbedded: openEmbedded,
        closeEmbedded: closeEmbedded,
        setAiContext: setAiContext,
        getAiContext: function () { return Object.assign({}, aiContext); },
        DEFAULT_AVATAR: DEFAULT_AVATAR
    };

    /* ============ 全站共享地图层（sessionStorage 保存，换页不丢） ============ */
    (function mountSharedMap() {
        if (document.getElementById('map-bubble')) return;
        var STORE = 'ltingMapMarkersV1';
        var VIEW_STORE = 'ltingMapViewV2:';
        var LAYER_STORE = 'ltingMapLayersV2';
        var LAYERS = {candidate: '候选', chosen: '已选', stay: '住宿区域'};
        var markers = [];
        try { markers = JSON.parse(sessionStorage.getItem(STORE) || '[]'); } catch (e) { markers = []; }
        var map = null, loading = false, refreshHandler = null;
        var focusMarkers = [], focusTitle = '', routeLine = [];

        var css = document.createElement('style');
        css.textContent = '#map-bubble{position:fixed;right:22px;bottom:132px;z-index:60;background:#0f766e;color:#fff;border:0;border-radius:999px;padding:10px 16px;font:600 12.5px/1 inherit;cursor:pointer;box-shadow:0 8px 24px rgba(15,118,110,.3)}'
            + '#map-drawer{position:fixed;right:0;top:0;height:100vh;width:420px;max-width:92vw;background:#fff;z-index:62;transform:translateX(105%);transition:transform .28s cubic-bezier(.16,1,.3,1);display:flex;flex-direction:column;box-shadow:-18px 0 48px rgba(16,36,48,.16)}'
            + '#map-drawer.open{transform:none}'
            + '#map-drawer .hd{display:flex;justify-content:space-between;align-items:center;padding:14px 16px;border-bottom:1px solid var(--line)}'
            + '#map-drawer .ly{padding:9px 16px;border-bottom:1px solid var(--line);font-size:12px;color:#536471;display:flex;gap:12px;flex-wrap:wrap}'
            + '#map-canvas{flex:1;min-height:180px;background:#eef2f4}'
            + '#map-fallback{padding:12px 16px;font-size:12px;color:#6e6e73;overflow:auto;max-height:45%}'
            + '#map-mk{padding:10px 16px;border-top:1px solid var(--line);font-size:11.5px;color:#86868b}';
        document.head.appendChild(css);

        var btn = document.createElement('button');
        btn.id = 'map-bubble';
        btn.textContent = '地图';
        document.body.appendChild(btn);

        var drawer = document.createElement('div');
        drawer.id = 'map-drawer';
        drawer.innerHTML = '<div class="hd"><b id="map-title" style="font-size:13px">行程地图（全站共享）</b>'
            + '<span><button class="btn ghost small" id="map-reset" style="padding:3px 9px;font-size:11px;margin-right:6px">全部点位</button>'
            + '<button class="btn ghost small" id="map-close" style="padding:3px 9px;font-size:11px">收起</button></span></div>'
            + '<div class="ly">' + Object.keys(LAYERS).map(function (k) {
                return '<label><input type="checkbox" data-layer="' + k + '" checked> ' + LAYERS[k] + '</label>';
            }).join('') + '</div>'
            + '<div id="map-canvas"></div><div id="map-fallback"></div><div id="map-mk"></div>';
        document.body.appendChild(drawer);

        function esc(t) {
            return String(t == null ? '' : t).replace(/[&<>]/g, function (c) {
                return {'&': '&amp;', '<': '&lt;', '>': '&gt;'}[c];
            });
        }
        function save() { try { sessionStorage.setItem(STORE, JSON.stringify(markers.slice(0, 200))); } catch (e) {} }
        function visible(layer) {
            var b = drawer.querySelector('input[data-layer="' + layer + '"]');
            return !b || b.checked;
        }
        function clustered(items) {
            if (items.length <= 50) return items;
            var groups = {};
            items.forEach(function (item) {
                var key = Number(item.lat).toFixed(2) + ',' + Number(item.lng).toFixed(2);
                var g = groups[key];
                if (!g) {
                    g = {lat: 0, lng: 0, count: 0, names: [], layer: item.layer || 'chosen', cluster: true};
                    groups[key] = g;
                }
                g.lat += Number(item.lat);
                g.lng += Number(item.lng);
                g.count++;
                if (g.names.length < 6) g.names.push(item.name);
            });
            return Object.keys(groups).map(function (key) {
                var g = groups[key];
                g.lat /= g.count;
                g.lng /= g.count;
                g.name = g.count + ' 个点位';
                return g;
            });
        }
        function viewKey() {
            return VIEW_STORE + location.pathname + ':' + (focusTitle || 'all');
        }
        function saveView() {
            if (!map || focusMarkers.length) return;
            try {
                var center = map.getCenter();
                localStorage.setItem(viewKey(), JSON.stringify({
                    lng: center.lng, lat: center.lat, zoom: map.getZoom()
                }));
            } catch (e) {}
        }
        function restoreView() {
            try {
                var view = JSON.parse(localStorage.getItem(viewKey()) || 'null');
                if (view && view.lat && view.lng && view.zoom) {
                    map.setZoomAndCenter(view.zoom, [view.lng, view.lat]);
                    return true;
                }
            } catch (e) {}
            return false;
        }
        function saveLayers() {
            var value = {};
            drawer.querySelectorAll('input[data-layer]').forEach(function (input) {
                value[input.dataset.layer] = input.checked;
            });
            try { localStorage.setItem(LAYER_STORE, JSON.stringify(value)); } catch (e) {}
        }
        function restoreLayers() {
            try {
                var value = JSON.parse(localStorage.getItem(LAYER_STORE) || '{}');
                drawer.querySelectorAll('input[data-layer]').forEach(function (input) {
                    if (typeof value[input.dataset.layer] === 'boolean') {
                        input.checked = value[input.dataset.layer];
                    }
                });
            } catch (e) {}
        }
        function render() {
            var source = focusMarkers.length ? focusMarkers : markers;
            var shown = source.filter(function (m) { return m.lat && m.lng && visible(m.layer || 'chosen'); });
            var renderMarkers = clustered(shown);
            document.getElementById('map-title').textContent = focusTitle || '行程地图（全站共享）';
            document.getElementById('map-mk').textContent = markers.length
                ? (focusMarkers.length ? '当前聚焦 ' + shown.length + ' 个点位，候选与住宿层仍保存在方案中'
                    : '已保存 ' + markers.length + ' 个点位（换页不消失），当前显示 ' + shown.length
                        + ' 个' + (renderMarkers.length !== shown.length
                            ? '，聚合为 ' + renderMarkers.length + ' 个视图标记' : ''))
                : '还没有点位：加入候选后会出现在这里';
            document.getElementById('map-fallback').innerHTML = map ? '' :
                '<b>地图暂不可用</b>，点位仍保留：<br>' + (markers.length
                    ? markers.map(function (m) { return '· ' + esc(m.name); }).join('<br>') : '（暂无）');
            if (!map) return;
            map.clearMap();
            renderMarkers.forEach(function (m) {
                var color = m.layer === 'stay' ? '#f97316' : (m.layer === 'candidate' ? '#64748b' : '#0071e3');
                var marker = new AMap.Marker({position: [Number(m.lng), Number(m.lat)],
                    content: '<div style="background:' + color + ';color:#fff;padding:3px 8px;border-radius:999px;'
                        + 'font:11px/1.4 sans-serif;white-space:nowrap">' + esc(m.name) + '</div>'});
                marker.on('click', function () {
                    if (m.cluster) {
                        map.setZoomAndCenter(Math.min(16, map.getZoom() + 2), [Number(m.lng), Number(m.lat)]);
                    } else {
                        window.dispatchEvent(new CustomEvent('lting:map-point-click', {
                            detail: {name: m.name, lat: m.lat, lng: m.lng, layer: m.layer}
                        }));
                    }
                });
                map.add(marker);
            });
            if (routeLine.length > 1) {
                map.add(new AMap.Polyline({
                    path: routeLine,
                    strokeColor: '#0071e3',
                    strokeWeight: 5,
                    strokeOpacity: .72,
                    lineJoin: 'round',
                    showDir: true
                }));
            }
            if (shown.length) {
                if (!focusMarkers.length && restoreView()) return;
                map.setFitView();
            }
        }
        async function init() {
            if (loading || map) return;
            loading = true;
            try {
                var ctx = await (await fetch('agent/map-context', {method: 'POST', credentials: 'include'})).json();
                var key = ctx.key || ctx.jsKey || '';
                if (!ctx.configured || !key) { loading = false; render(); return; }
                if (ctx.securityCode) window._AMapSecurityConfig = {securityJsCode: ctx.securityCode};
                await new Promise(function (resolve, reject) {
                    if (window.AMap) return resolve();
                    var s = document.createElement('script');
                    s.src = 'https://webapi.amap.com/maps?v=2.0&key=' + encodeURIComponent(key);
                    s.onload = resolve; s.onerror = reject; document.head.appendChild(s);
                });
                map = new AMap.Map('map-canvas', {zoom: 11, resizeEnable: true});
                if (ctx.lat && ctx.lng) map.setCenter([Number(ctx.lng), Number(ctx.lat)]);
                map.on('moveend', saveView);
                map.on('zoomend', saveView);
            } catch (e) { map = null; }
            loading = false;
            render();
        }
        btn.addEventListener('click', async function () {
            if (!drawer.classList.contains('open') && refreshHandler) {
                try { await refreshHandler(); } catch (e) { /* 刷新失败时保留现有点位 */ }
            }
            drawer.classList.toggle('open');
            if (drawer.classList.contains('open')) { init(); render(); }
        });
        document.getElementById('map-close').addEventListener('click', function () { drawer.classList.remove('open'); });
        document.getElementById('map-reset').addEventListener('click', function () {
            focusMarkers = [];
            focusTitle = '';
            render();
        });
        restoreLayers();
        drawer.querySelectorAll('input[data-layer]').forEach(function (b) {
            b.addEventListener('change', function () { saveLayers(); render(); });
        });

        Shell.map = {
            show: function () { drawer.classList.add('open'); init(); render(); },
            focus: async function (items, title, mode) {
                focusMarkers = (items || []).filter(function (x) { return x && x.lat && x.lng; })
                    .map(function (x) {
                        return {name: x.name || '点位', lat: x.lat, lng: x.lng, layer: x.layer || 'chosen'};
                    });
                focusTitle = title || '当前方案地图';
                routeLine = [];
                drawer.classList.add('open');
                await init();
                render();
                if (!map || focusMarkers.length < 2) return;
                var path = [];
                for (var i = 1; i < focusMarkers.length; i++) {
                    try {
                        var a = focusMarkers[i - 1], b = focusMarkers[i];
                        var route = await api('agent/route', {
                            fromLat: a.lat, fromLng: a.lng, toLat: b.lat, toLng: b.lng,
                            mode: mode || 'driving', coordinate: 'gcj'
                        });
                        if (route && route.ok && route.polyline) {
                            route.polyline.split(';').forEach(function (pair) {
                                var xy = pair.split(',');
                                if (xy.length === 2) path.push([Number(xy[0]), Number(xy[1])]);
                            });
                        }
                    } catch (e) { /* 单段失败不影响其它点位 */ }
                }
                routeLine = path;
                render();
            },
            showAll: function () {
                focusMarkers = [];
                focusTitle = '';
                routeLine = [];
                drawer.classList.add('open');
                init();
                render();
            },
            replace: function (items, title) {
                markers = (items || []).filter(function (x) { return x && x.name; })
                    .map(function (x) {
                        return {name: x.name, lat: x.lat, lng: x.lng, layer: x.layer || 'chosen'};
                    });
                focusMarkers = [];
                focusTitle = '';
                routeLine = [];
                save();
                render();
                var titleEl = document.getElementById('map-title');
                if (titleEl) titleEl.textContent = title || '当前行程地图';
            },
            onOpen: function (handler) {
                refreshHandler = typeof handler === 'function' ? handler : null;
            },
            add: function (item, layer) {
                if (!item || !item.name) return;
                markers = markers.filter(function (m) { return m.name !== item.name; });
                markers.push({name: item.name, lat: item.lat, lng: item.lng, layer: layer || 'chosen'});
                save(); render();
            },
            remove: function (name) {
                markers = markers.filter(function (m) { return m.name !== name; });
                save(); render();
            },
            clear: function () { markers = []; save(); render(); },
            all: function () { return markers.slice(); }
        };
    })();

    /* ================= 右下角 AI 气泡：点开才是对话框 ================= */
    (function mountAiBubble() {
        if (document.getElementById('ai-bubble')) return;
        var style = document.createElement('style');
        style.textContent = [
            '#ai-bubble{position:fixed;right:22px;bottom:22px;z-index:60;background:#111827;color:#fff;border:0;border-radius:999px;padding:12px 18px;font:600 13px/1 inherit;cursor:pointer;box-shadow:0 10px 30px rgba(16,36,48,.28)}',
            '#ai-bubble:hover{background:#000}',
            '#ai-panel{position:fixed;right:22px;bottom:78px;width:360px;height:480px;background:#fff;z-index:61;border-radius:18px;box-shadow:0 20px 60px rgba(16,36,48,.22);display:none;flex-direction:column;overflow:hidden;border:1px solid var(--line)}',
            '#ai-panel.open{display:flex}',
            '#ai-panel .hd{display:flex;align-items:center;justify-content:space-between;padding:12px 14px;border-bottom:1px solid var(--line)}',
            '#ai-panel .bd{flex:1;overflow:auto;padding:12px;display:flex;flex-direction:column;gap:10px;background:#fafbfc}',
            '#ai-panel .m{max-width:88%;padding:9px 12px;border-radius:12px;font-size:12.5px;line-height:1.6;white-space:pre-wrap}',
            '#ai-panel .m.me{align-self:flex-end;background:var(--brand);color:#fff}',
            '#ai-panel .m.ai{align-self:flex-start;background:#fff;border:1px solid var(--line)}',
            '#ai-panel .act{border:1px dashed rgba(0,113,227,.4);border-radius:10px;padding:9px 10px;font-size:11.5px;color:#536471}',
            '#ai-context-hint{display:none}',
            '#ai-panel .receipt{border:1px solid rgba(52,199,89,.3);background:#f5fcf7;border-radius:10px;padding:9px 10px;font-size:11.5px;color:#28783b}',
            '#ai-panel .receipt b{display:block;margin-bottom:4px;color:#1f5f2d}',
            '#ai-panel .operation-preview{border:1px solid rgba(0,113,227,.28);background:#f7fbff;border-radius:12px;padding:10px;font-size:11.5px;color:#284b63}',
            '#ai-panel .operation-preview b{display:block;color:#0b4f7a;margin-bottom:4px}',
            '#ai-panel .operation-preview .warn{color:#9a5b00;margin-top:5px}',
            '#ai-panel .operation-preview button{margin-top:8px}',
            '#ai-panel .ft{display:flex;gap:8px;padding:10px;border-top:1px solid var(--line)}',
            '#ai-panel input{flex:1;padding:9px 12px;border-radius:999px;border:1px solid var(--line);font:inherit;font-size:12.5px}'
        ].join('');
        document.head.appendChild(style);

        var btn = document.createElement('button');
        btn.id = 'ai-bubble';
        btn.textContent = 'AI 助手';
        document.body.appendChild(btn);

        var panel = document.createElement('div');
        panel.id = 'ai-panel';
        panel.innerHTML = '<div class="hd"><b style="font-size:13px">AI 助手（兜底）</b>'
            + '<span><a href="chat.html" style="font-size:11.5px;color:var(--brand);margin-right:10px;text-decoration:none">完整对话</a>'
            + '<button class="btn ghost small" id="ai-close" style="padding:3px 9px;font-size:11px">收起</button></span></div>'
            + '<div class="bd" id="ai-msgs"><div class="m ai">我是兜底入口：自由表达、复杂修改、异常处理找我；常规操作在工作台上点就行。</div>'
            + '<div class="act" id="ai-context-hint"></div></div>'
            + '<div class="ft"><input id="ai-input" placeholder="例如：第 2 天下午换室内…">'
            + '<button class="btn small" id="ai-send">发送</button></div>';
        document.body.appendChild(panel);

        btn.addEventListener('click', function () {
            panel.classList.toggle('open');
            if (panel.classList.contains('open')) document.getElementById('ai-input').focus();
        });
        document.getElementById('ai-close').addEventListener('click', function () { panel.classList.remove('open'); });

        function esc2(t) {
            return String(t == null ? '' : t).replace(/[&<>]/g, function (c) {
                return {'&': '&amp;', '<': '&lt;', '>': '&gt;'}[c];
            });
        }

        var pendingRiskText = '';
        var pendingPlanOperations = [];

        function operationTitle(op) {
            var action = op && op.diff ? op.diff.action : (op && op.type) || 'PLAN_OPERATION';
            return {
                MOVE_NODE: '移动行程节点',
                REMOVE_NODE: '移出行程节点',
                REPLACE_NODE: '替换行程节点',
                REORDER_NODE: '调整节点顺序',
                LOCK_NODE: '锁定行程节点',
                UNLOCK_NODE: '解锁行程节点',
                MARK_NODE_CLOSED: '标记临时闭馆',
                CLEAR_NODE_CLOSED: '取消临时闭馆',
                ADD_NODE: '加入行程节点',
                ADD_CANDIDATE: '加入候选',
                REMOVE_CANDIDATE: '移除候选',
                UPDATE_CONSTRAINT: '更新出行约束',
                UPDATE_BUDGET: '更新预算',
                UPDATE_STAY: '更新住宿区域',
                UPDATE_PLAN_META: '更新规划条件',
                UPDATE_TRANSPORT: '更新城际交通',
                UPDATE_BUDGET_PLAN: '更新预算分项',
                REBUILD_ITINERARY: '重建行程骨架',
                ROLLBACK_PLAN_VERSION: '回退规划版本'
            }[action] || '行程变更预览';
        }

        function operationSummary(op) {
            var d = op && op.diff ? op.diff : {};
            if (d.action === 'MOVE_NODE') {
                return (d.node || '节点') + '：第' + d.fromDay + '天 ' + d.fromSlot
                    + ' → 第' + d.toDay + '天 ' + d.toSlot;
            }
            if (d.action === 'REMOVE_NODE') return (d.node || '节点') + ' · 第' + d.day + '天';
            if (d.action === 'REPLACE_NODE') return (d.before || '原节点') + ' → ' + (d.after || '新节点');
            if (d.action === 'REORDER_NODE') return (d.node || '节点') + ' · ' + (d.direction === 'up' ? '上移' : '下移');
            if (d.action === 'LOCK_NODE' || d.action === 'UNLOCK_NODE') return d.nodeKey || '节点';
            if (d.action === 'MARK_NODE_CLOSED' || d.action === 'CLEAR_NODE_CLOSED') return d.nodeKey || '节点';
            if (d.action === 'ADD_NODE') return (d.node || '节点') + '：第' + d.day + '天 ' + d.slot;
            if (d.action === 'ADD_CANDIDATE') return d.candidate || '候选地点';
            if (d.action === 'REMOVE_CANDIDATE') return '移除 ' + (d.removed || 0) + ' 个关联项';
            if (d.action === 'UPDATE_CONSTRAINT') return JSON.stringify(d.after || {});
            if (d.action === 'UPDATE_BUDGET') return (d.before || '未设置') + ' → ' + (d.after || '不限');
            if (d.action === 'UPDATE_STAY') return (d.before || '未选择') + ' → ' + (d.after || '清除');
            if (d.action === 'UPDATE_PLAN_META') return JSON.stringify(d.after || {});
            if (d.action === 'UPDATE_TRANSPORT') return (d.outbound || '') + ' / ' + (d.inbound || '');
            if (d.action === 'UPDATE_BUDGET_PLAN') return '总预算 ' + (d.total || 0);
            if (d.action === 'REBUILD_ITINERARY') {
                return (d.variant || 'classic') + ' · ' + (d.days || 0) + ' 天 · ' + (d.nodes || 0) + ' 个节点';
            }
            if (d.action === 'ROLLBACK_PLAN_VERSION') {
                return '恢复到版本 v' + (d.targetVersionNo || '') + ' · 修订 ' + (d.toRevision || '-');
            }
            return '等待确认';
        }

        function renderPlanOperations(items) {
            pendingPlanOperations = (items || []).filter(function (op) {
                return op && op.operationId && op.approvalToken;
            });
            return pendingPlanOperations.map(function (op, index) {
                var conflicts = op.validation && op.validation.conflicts ? op.validation.conflicts : [];
                return '<div class="operation-preview" data-plan-preview="' + index + '">'
                    + '<b>' + esc2(operationTitle(op)) + '</b>'
                    + '<div>' + esc2(operationSummary(op)) + '</div>'
                    + '<div>' + esc2(op.riskLevel || 'L2') + ' · 需确认后才会写入</div>'
                    + (conflicts.length ? '<div class="warn">校验提醒：' + esc2(conflicts.slice(0, 2).map(function (x) {
                        return x.message || x.type || '存在冲突';
                    }).join('；')) + '</div>' : '<div class="warn">规则校验通过</div>')
                    + '<button class="btn small" data-plan-op-apply="' + index + '">确认应用</button>'
                    + '</div>';
            }).join('');
        }

        async function send() {
            var input = document.getElementById('ai-input');
            var text = (input.value || '').trim();
            if (!text) return;
            input.value = '';
            var msgs = document.getElementById('ai-msgs');
            msgs.insertAdjacentHTML('beforeend', '<div class="m me">' + esc2(text) + '</div>');
            msgs.scrollTop = msgs.scrollHeight;
            var thinking = document.createElement('div');
            thinking.className = 'm ai';
            thinking.textContent = '正在处理…';
            msgs.appendChild(thinking);
            msgs.scrollTop = msgs.scrollHeight;
            try {
                var context = Shell.getAiContext();
                var contextLine = Object.keys(context).length
                    ? '[界面上下文，不需要逐字复述：' + Object.keys(context)
                        .filter(function (k) { return k !== 'label' && context[k] !== '' && context[k] != null; })
                        .map(function (k) { return k + '=' + context[k]; }).join('；') + ']\n'
                    : '';
                var confirmed = /^(确认执行|确认修改|执行吧|确认)$/.test(text);
                var safetyPrefix = '';
                if (confirmed && pendingRiskText) {
                    safetyPrefix = '[用户已确认执行上一轮变更预览，可以调用写入工具]\n';
                    text = '确认执行上一轮高风险变更：' + pendingRiskText;
                    pendingRiskText = '';
                } else if (/(删除全部|全部删除|清空|整份|批量|换住宿|重排全部)/.test(text)) {
                    pendingRiskText = text;
                    safetyPrefix = '[高风险操作安全约束：本轮只展示拟变更内容和影响范围，'
                        + '禁止调用任何写入工具；等待用户下一条回复“确认执行”]\n';
                    msgs.insertAdjacentHTML('beforeend',
                        '<div class="act">高风险操作会先展示变更预览。确认后回复“确认执行”。</div>');
                }
                var res = await fetch('agent/chat', {
                    method: 'POST', credentials: 'include',
                    headers: {'Content-Type': 'text/plain;charset=UTF-8', 'Accept': 'application/json'},
                    body: contextLine + safetyPrefix + text
                });
                var data = await res.json();
                var html = '<div class="m ai">' + esc2(data.answer || '（没有拿到回答）') + '</div>';
                if (data.actions && data.actions.length) {
                    html += '<div class="receipt"><b>执行回执 · ' + data.actions.length + ' 项</b>'
                        + data.actions.map(function (a) {
                            return '<div>' + esc2(a.summary || a.tool || '已完成') + '</div>';
                        }).join('')
                        + '</div>';
                    window.dispatchEvent(new CustomEvent('lting:data-changed', {
                        detail: {actions: data.actions, context: context}
                    }));
                }
                html += renderPlanOperations(data.pendingOperations);
                thinking.outerHTML = html;
            } catch (e) {
                thinking.textContent = '暂时不可用：' + e.message;
            }
            msgs.scrollTop = msgs.scrollHeight;
        }

        document.getElementById('ai-send').addEventListener('click', send);
        document.getElementById('ai-input').addEventListener('keydown', function (e) {
            if (e.key === 'Enter') send();
        });
        document.getElementById('ai-msgs').addEventListener('click', async function (e) {
            var button = e.target.closest ? e.target.closest('[data-plan-op-apply]') : null;
            if (!button) return;
            var operation = pendingPlanOperations[parseInt(button.dataset.planOpApply, 10)];
            if (!operation) return;
            button.disabled = true;
            button.textContent = '正在应用…';
            try {
                var response = await fetch('plan/ops/apply', {
                    method: 'POST',
                    credentials: 'include',
                    headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
                    body: new URLSearchParams({
                        operationId: operation.operationId,
                        approvalToken: operation.approvalToken,
                        expectedRevision: operation.expectedRevision
                    })
                });
                var applied = await response.json();
                if (!applied || applied.status !== 'success') {
                    throw new Error((applied && applied.message) || '操作应用失败');
                }
                var card = button.closest('.operation-preview');
                card.innerHTML = '<b>已应用</b><div>' + esc2(operationSummary(operation))
                    + '</div><div>规划版本已更新为 ' + esc2(applied.planRevision) + '</div>';
                window.dispatchEvent(new CustomEvent('lting:plan-operation-applied', {detail: applied}));
                window.dispatchEvent(new CustomEvent('lting:data-changed', {
                    detail: {actions: [{tool: 'planOps', summary: operationSummary(operation)}]}
                }));
            } catch (error) {
                button.disabled = false;
                button.textContent = '重新确认应用';
                button.title = error.message || '操作应用失败';
                var message = button.previousElementSibling;
                if (message) message.textContent = error.message || '操作应用失败';
            }
        });
    })();
})();
