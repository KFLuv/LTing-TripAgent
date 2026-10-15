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
    quick: QUICK
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
      this.setData({
        msgs: this.data.msgs.concat([{
          role: 'ai', text: answer, actions: actions, nav: parseNav(answer)
        }]),
        sessionId: (res && res.sessionId) || this.data.sessionId
      });
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

  /** 「我的位置」：定位并上报给后端，管家排行程时就会以这里为起点 */
  async locate() {
    const loc = await api.currentLocation();
    if (!loc.real) {
      wx.showModal({
        title: '没拿到定位',
        content: '可能是没授权，或被系统限制了。去「设置」里允许位置信息后重试。',
        confirmText: '去设置',
        success: (r) => {
          if (r.confirm) wx.openSetting();
        }
      });
      return;
    }
    const res = await api.reportLocation({
      lat: loc.lat, lng: loc.lng, place: '', accuracy: loc.accuracy || '', force: true
    });
    api.toast(res && res.status === 'success' ? '已把当前位置告诉管家' : '位置上报失败');
  },

  /** AI 回答里若带高德导航链接（position=经度,纬度），给一个「在地图里打开」按钮 */
  openMap(e) {
    const m = this.data.msgs[e.currentTarget.dataset.i];
    const nav = m && m.nav;
    if (!nav) return;
    wx.openLocation({
      latitude: nav.lat,
      longitude: nav.lng,
      name: nav.name || '目的地',
      scale: 16
    });
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
