const api = require('../../utils/api.js');

const STATUS_TEXT = { planning: '规划中', ongoing: '进行中', done: '已完成' };

Page({
  data: { trips: [] },

  onShow() {
    this.load();
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh());
  },

  async load() {
    const user = await getApp().refreshUser().catch(() => null);
    if (!user) {
      wx.navigateTo({ url: '/pages/login/login' });
      return;
    }
    const res = await api.trips().catch(() => null);
    const list = ((res && res.trips) || []).map((t) => ({
      id: t.id,
      title: t.title || '未命名行程',
      createTime: t.createTime || '',
      updateTime: t.updateTime || '',
      status: t.status || 'planning',
      statusText: STATUS_TEXT[t.status] || '规划中'
    }));
    this.setData({ trips: list });
  },

  open(e) {
    wx.navigateTo({ url: '/pages/trip-detail/trip-detail?id=' + e.currentTarget.dataset.id });
  },

  create() {
    wx.showModal({
      title: '新建行程',
      editable: true,
      placeholderText: '例如：国庆·潮汕 3 天',
      success: async (r) => {
        if (!r.confirm || !r.content) return;
        const res = await api.tripSave(r.content, '').catch(() => null);
        if (res && res.status === 'success') {
          api.toast('已创建');
          this.load();
        } else {
          api.toast('创建失败');
        }
      }
    });
  },

  remove(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除这条行程？',
      content: '节点与版本会一起删除，删掉找不回来。',
      confirmColor: '#ff3b30',
      success: async (r) => {
        if (!r.confirm) return;
        await api.tripDelete(id).catch(() => null);
        this.load();
      }
    });
  }
});
