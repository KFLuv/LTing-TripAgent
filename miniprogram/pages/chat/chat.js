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
      actions: []
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
        msgs: this.data.msgs.concat([{ role: 'ai', text: answer, actions: actions }]),
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
  }
});
