/*
 * 多页面共用的外壳逻辑：左侧导航 + 登录校验 + 统一的接口调用。
 * 不引入框架：这几个页面结构简单，省掉构建链与额外的 CDN 依赖。
 */
(function () {
    'use strict';

    var PAGES = [
        {href: 'home.html', icon: 'fa-compass', label: '出发'},
        {href: 'chat.html', icon: 'fa-comments', label: 'AI 管家'},
        {href: 'square.html', icon: 'fa-fire', label: '动态广场'},
        {href: 'trips.html', icon: 'fa-book', label: '行程本'},
        {href: 'checkin.html', icon: 'fa-location-dot', label: '打卡'},
        {href: 'profile.html', icon: 'fa-id-badge', label: '个人资料'},
        {href: 'preferences.html', icon: 'fa-sliders', label: '偏好档案'},
        {href: 'nearby.html', icon: 'fa-utensils', label: '附近吃喝玩乐'},
        {href: 'account.html', icon: 'fa-user-gear', label: '账号管理'},
        {href: 'settings.html', icon: 'fa-gear', label: '系统设置'}
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
        var html = '<div class="nav-brand"><i class="fas fa-route"></i> AI旅游助手</div>';
        PAGES.forEach(function (p) {
            var on = p.href === active ? ' on' : '';
            html += '<a class="nav-item' + on + '" href="' + p.href + '">'
                + '<i class="fas ' + p.icon + '"></i><span>' + p.label + '</span></a>';
        });
        // 左下角账号区：头像 + 昵称，点击进账号管理页
        html += '<div class="nav-foot">'
            + '<a class="nav-account" href="account.html">'
            + '<img class="avatar sm" id="nav-avatar" alt="头像" src="' + DEFAULT_AVATAR + '">'
            + '<span class="nav-account-text"><b id="nav-user">…</b><i>账号管理</i></span></a>'
            + '<a class="nav-logout" href="login.html">退出登录</a></div>';
        host.innerHTML = html;
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
        mapEmbedUrl: mapEmbedUrl,
        dianpingUrl: dianpingUrl,
        openExternal: openExternal,
        openEmbedded: openEmbedded,
        closeEmbedded: closeEmbedded,
        DEFAULT_AVATAR: DEFAULT_AVATAR
    };
})();
