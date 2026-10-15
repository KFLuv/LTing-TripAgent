const api = require('../../utils/api.js');

const QUICK = [
  '帮我安排周末两天的广州行程',
  '我吃不了辣，也走不了太久',
  '附近有什么值得去的地方'
];

Page({
  data: {
    msgs: [],
    input: '',
    sending: false,
    sessionId: 0,
    title: '',
    intoView: 'm-end',
    agentOff: false,
    quick: QUICK,
    location: { status: '', text: '', lat: 0, lng: 0, name: '', error: '' },
    map: { show: false, lat: 0, lng: 0, name: '', mine: false, circles: [], polyline: [] }
  },

  onShow() {
    this.load();
  },

  async load() {
    const app = getApp();
    const user = await app.refreshUser().catch(() => null);
    if (!user) {
      wx.navigateTo({ url: '/pages/login/login' });
      return;
    }
    const av = await api.agentAvailable().catch(() => null);
    this.setData({ agentOff: !(av && av.available) });
    await this.loadHistory(this.data.sessionId);
  },

  async loadHistory(sessionId) {
    const res = await api.history(sessionId, 50).catch(() => null);
    if (!res || res.login === false) return;
    const msgs = (res.messages || []).map((m) => ({
      role: m.role === 'user' ? 'user' : 'ai',
      text: m.content || '',
      actions: [],
      nav: m.role === 'user' ? null : parseNav(m.content || '')
    }));
    this.setData({
      msgs: msgs,
      sessionId: res.sessionId || 0,
      title: res.title || ''
    });
    this.scrollToEnd();
  },

  onInput(e) {
    this.setData({ input: e.detail.value });
  },

  useQuick(e) {
    this.setData({ input: e.currentTarget.dataset.t });
    this.send();
  },

  async send() {
    const text = (this.data.input || '').trim();
    if (!text || this.data.sending) return;
    const msgs = this.data.msgs.concat([{ role: 'user', text: text, actions: [] }]);
    this.setData({ msgs: msgs, input: '', sending: true });
    this.scrollToEnd();

    try {
      const res = await api.chat(text, this.data.sessionId);
      const answer = (res && res.answer) || '（这次没有拿到回答）';
      const actions = (res && res.actions) || [];
      const nav = await resolveNav(answer);
      this.setData({
        msgs: this.data.msgs.concat([{
          role: 'ai', text: answer, actions: actions, nav: nav
        }]),
        sessionId: (res && res.sessionId) || this.data.sessionId
      });
      // 回答里带了导航信息 → 直接在对话里展开内嵌地图（和桌面端一样，点了才弹全屏）
      if (nav && !nav.link) {
        this.showMapAt(nav.lat, nav.lng, nav.name || '目的地');
      }
    } catch (e) {
      this.setData({
        msgs: this.data.msgs.concat([{
          role: 'ai',
          text: '请求失败：' + (e && e.message ? e.message : '网络问题'),
          actions: []
        }])
      });
    } finally {
      this.setData({ sending: false });
      this.scrollToEnd();
    }
  },

  scrollToEnd() {
    this.setData({ intoView: '' });
    setTimeout(() => this.setData({ intoView: 'm-end' }), 60);
  },

  async newSession() {
    const res = await api.sessionNew().catch(() => null);
    if (!res || res.status !== 'success') {
      api.toast('新建会话失败');
      return;
    }
    this.setData({ msgs: [], sessionId: res.id || 0, title: '新对话' });
  },

  async openSessions() {
    const res = await api.sessions().catch(() => null);
    const list = (res && res.sessions) || [];
    if (!list.length) {
      api.toast('还没有历史会话');
      return;
    }
    const names = list.slice(0, 6).map((s) => s.title || ('会话 ' + s.id));
    wx.showActionSheet({
      itemList: names,
      success: (r) => {
        const s = list[r.tapIndex];
        if (s) this.loadHistory(s.id);
      }
    });
  },

  renameSession() {
    if (!this.data.sessionId) return;
    wx.showModal({
      title: '重命名会话',
      editable: true,
      placeholderText: this.data.title || '新对话',
      success: async (r) => {
        if (!r.confirm || !r.content) return;
        await api.sessionRename(this.data.sessionId, r.content).catch(() => null);
        this.setData({ title: r.content });
      }
    });
  },

  /** 「我的位置」：先定位、再反查地名，然后把结果**显示**出来（顺带上报给管家） */
  async locate() {
    this.setData({ 'location.status': 'locating', 'location.error': '' });
    const loc = await api.currentLocation();
    if (!loc.real) {
      this.setData({
        'location.status': 'fail',
        'location.error': '可能是没授权，或被系统限制'
      });
      return;
    }
    // 扫描地名（高德逆地理），失败就退回"只有坐标"也说清楚
    const rev = await api.reverse(loc.lat, loc.lng);
    const name = (rev && rev.ok && rev.place) ? rev.place : '已定位（没解析出地名）';
    this.setData({
      location: {
        status: 'ok',
        name: name,
        lat: Number(loc.lat.toFixed(6)),
        lng: Number(loc.lng.toFixed(6)),
        error: ''
      }
    });
    api.reportLocation({
      lat: loc.lat, lng: loc.lng, place: '', accuracy: loc.accuracy || '', force: true
    });
    this.setData({ 'location.reported': true });
  },

  openSetting() {
    wx.openSetting({ complete: () => this.locate() });
  },

  /** 在对话里画出内嵌地图：目的地圆圈 + 我→目的地连线 */
  showMapAt(lat, lng, name) {
    const me = this.data.location;
    const polyline = [];
    if (me.status === 'ok' && me.lat && me.lng) {
      polyline.push({
        points: [
          { latitude: me.lat, longitude: me.lng },
          { latitude: lat, longitude: lng }
        ],
        color: '#0071e3',
        width: 4,
        arrowLine: true
      });
    }
    this.setData({
      map: {
        show: true,
        lat: lat,
        lng: lng,
        name: name,
        mine: false,
        circles: [{
          latitude: lat, longitude: lng, radius: 80,
          color: '#0071e3cc', fillColor: '#0071e333', strokeWidth: 2
        }],
        polyline: polyline
      }
    });
  },

  /** AI 回答里若带高德导航链接（position=经度,纬度），给一个「在地图里打开」按钮 */
  async showMap(e) {
    const m = this.data.msgs[e.currentTarget.dataset.i];
    const nav = m && m.nav;
    if (!nav) {
      api.toast('这条回答里没有可用的坐标');
      return;
    }
    this.showMapAt(nav.lat, nav.lng, nav.name || '目的地');
  },

  /** 点内嵌地图才弹全屏（系统地图） */
  openFullMap() {
    const m = this.data.map;
    if (!m || !m.lat || !m.lng) return;
    wx.openLocation({
      latitude: m.lat,
      longitude: m.lng,
      name: m.mine ? '我的位置' : (m.name || '目的地'),
      scale: 16
    });
  },

  closeMap() {
    this.setData({ 'map.show': false });
  },

  /** 没有坐标只有链接时：至少让用户能复制链接去浏览器/高德里打开 */
  copyLink(e) {
    const m = this.data.msgs[e.currentTarget.dataset.i];
    const link = m && m.nav && m.nav.link;
    if (link) wx.setClipboardData({ data: link });
  }
});

/** 从回答文本里抽「打开地图」要用的坐标：兼容 uri.amap.com/marker?position=lng,lat&name=xxx */
function parseNav(text) {
  if (!text) return null;
  const m = text.match(/position=([\d.]+),([\d.]+)/);
  if (!m) return null;
  const lng = Number(m[1]);
  const lat = Number(m[2]);
  if (!lat || !lng) return null;
  let name = '';
  const n = text.match(/[?&]name=([^&\s)]+)/);
  if (n) {
    try {
      name = decodeURIComponent(n[1]);
    } catch (err) {
      name = n[1];
    }
  }
  return { lat: lat, lng: lng, name: name };
}

/** 回答里的高德链接（与桌面端同一套判定） */
function findAmapLink(text) {
  const urls = String(text || '').match(/https?:\/\/[^\s<>"'|)】]+/g) || [];
  for (let i = 0; i < urls.length; i++) {
    const url = urls[i].replace(/[，。！？、；：)）\]】},;:.!?]+$/, '');
    if (/(uri\.amap\.com|ditu\.amap\.com|www\.amap\.com|\/\/amap\.com)/i.test(url)) return url;
  }
  return '';
}

/** 链接里带的终点名（name= / to= 等参数）*/
function nameFromLink(url) {
  const m = url.match(/[?&](?:name|to|to_name|keyword|q)=([^&\s]+)/i);
  if (!m) return '';
  try {
    return decodeURIComponent(m[1]).replace(/[（(].*?[)）]/g, '').trim();
  } catch (e) {
    return '';
  }
}

/**
 * 统一把「回答里的导航」变成地图能用的东西：
 * 1) 链接里直接带 position=lng,lat → 用它；
 * 2) 只有地名 → 调 /agent/geocode 换坐标；
 * 3) 都不行 → 至少留个链接给用户复制。
 */
async function resolveNav(text) {
  const withPos = parseNav(text);
  if (withPos) return withPos;
  const link = findAmapLink(text);
  if (!link) return null;
  const name = nameFromLink(link);
  if (name) {
    const g = await api.geocode(name);
    if (g && g.ok && g.lat && g.lng) {
      return { lat: Number(g.lat), lng: Number(g.lng), name: g.name || name };
    }
  }
  return { link: link };
}
