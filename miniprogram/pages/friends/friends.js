const api = require('../../utils/api.js');

Page({
  data: { inviteCode: '', target: '', friends: [] },

  onShow() {
    this.load();
  },

  async load() {
    const user = await getApp().refreshUser().catch(() => null);
    if (!user) {
      wx.navigateTo({ url: '/pages/login/login' });
      return;
    }
    const res = await api.friends().catch(() => null);
    if (!res || res.status !== 'success') return;
    this.setData({
      inviteCode: res.inviteCode || '',
      friends: (res.friends || []).map((f) => {
        const username = typeof f === 'string' ? f : (f.username || '');
        return {
          username: username,
          nickname: typeof f === 'string' ? '' : (f.nickname || ''),
          initial: (username || '友').slice(0, 1).toUpperCase()
        };
      })
    });
  },

  onTarget(e) {
    this.setData({ target: e.detail.value });
  },

  copyCode() {
    if (!this.data.inviteCode) return;
    wx.setClipboardData({ data: this.data.inviteCode });
  },

  async add() {
    const t = (this.data.target || '').trim();
    if (!t) {
      api.toast('填对方的用户名或邀请码');
      return;
    }
    const res = await api.friendAdd(t).catch(() => null);
    api.toast((res && res.message) || '添加失败');
    if (res && res.status === 'success') {
      this.setData({ target: '' });
      this.load();
    }
  },

  openHome(e) {
    wx.navigateTo({ url: '/pages/user-home/user-home?u=' + e.currentTarget.dataset.u });
  },

  remove(e) {
    const u = e.currentTarget.dataset.u;
    wx.showModal({
      title: '删掉好友 ' + u + '？',
      content: '删掉后对方设为「仅好友」的内容你就看不到了。',
      confirmColor: '#ff3b30',
      success: async (r) => {
        if (!r.confirm) return;
        await api.friendDelete(u).catch(() => null);
        this.load();
      }
    });
  }
});
