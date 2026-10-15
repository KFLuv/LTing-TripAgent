const api = require('../../utils/api.js');

Page({
  data: { id: 0, trip: null, nodes: [], versions: [] },

  onLoad(options) {
    this.setData({ id: Number(options.id || 0) });
    this.load();
  },

  async load() {
    const user = await getApp().refreshUser().catch(() => null);
    if (!user) {
      wx.navigateTo({ url: '/pages/login/login' });
      return;
    }
    const res = await api.tripGet(this.data.id).catch(() => null);
    if (!res || res.status !== 'success') {
      api.toast((res && res.message) || '行程读取失败');
      return;
    }
    const t = res.trip || {};
    this.setData({
      trip: t,
      nodes: t.nodes || [],
      versions: t.versions || []
    });
    wx.setNavigationBarTitle({ title: t.title || '行程详情' });
  },

  rollback(e) {
    const versionId = e.currentTarget.dataset.id;
    wx.showModal({
      title: '回退到这个版本？',
      content: '当前内容会被这个快照覆盖（旧内容仍会留成新版本，可再回退）。',
      success: async (r) => {
        if (!r.confirm) return;
        const res = await api.tripVersionRollback(this.data.id, versionId).catch(() => null);
        api.toast(res && res.status === 'success' ? '已回退' : '回退失败');
        if (res && res.status === 'success') this.load();
      }
    });
  }
});
