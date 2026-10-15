const api = require('../../utils/api.js');

Page({
  data: { username: '', profile: null, initial: '友', isSelf: false, isFriend: false, moments: [], logs: [], trips: [], reviews: [] },

  onLoad(options) {
    this.setData({ username: options.u || '' });
  },

  onShow() {
    this.load();
  },

  async load() {
    const user = await getApp().refreshUser().catch(() => null);
    if (!user) {
      wx.navigateTo({ url: '/pages/login/login' });
      return;
    }
    const res = await api.userHome(this.data.username).catch(() => null);
    if (!res || res.status !== 'success') {
      api.toast((res && res.message) || '读不到这个人的主页');
      return;
    }
    const p = res.profile || {};
    this.setData({
      profile: p,
      initial: ((p.nickname || p.username || '友')[0] || '友').toUpperCase(),
      isSelf: !!res.isSelf,
      isFriend: !!res.isFriend,
      moments: res.moments || [],
      logs: res.logs || [],
      trips: res.trips || [],
      reviews: res.reviews || []
    });
    wx.setNavigationBarTitle({ title: (p.nickname || p.username || '') + ' 的主页' });
  },

  async addFriend() {
    const res = await api.friendAdd(this.data.username).catch(() => null);
    api.toast((res && res.message) || '添加失败');
    if (res && res.status === 'success') this.load();
  }
});
