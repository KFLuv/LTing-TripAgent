const api = require('../../utils/api.js');

const STATUS_TEXT = {
  planning: '规划中',
  ongoing: '进行中',
  done: '已完成'
};

Page({
  data: {
    user: {},
    initial: '旅',
    stats: { trips: 0, moments: 0, logs: 0 },
    recentTrips: []
  },

  onShow() {
    this.load();
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh());
  },

  async load() {
    const app = getApp();
    const user = await app.refreshUser().catch(() => null);
    if (!user) {
      wx.navigateTo({ url: '/pages/login/login' });
      return;
    }
    const name = user.name || '';
    this.setData({
      user: user,
      initial: (name || '旅').slice(0, 1).toUpperCase()
    });

    // 三个计数各自独立失败不影响页面
    const trips = await api.trips().catch(() => null);
    const list = (trips && trips.trips) || [];
    this.setData({
      recentTrips: list.slice(0, 3).map((t) => ({
        id: t.id,
        title: t.title || '未命名行程',
        statusText: STATUS_TEXT[t.status] || (t.status || '规划中'),
        updateTime: t.updateTime || t.createTime || ''
      })),
      'stats.trips': list.length
    });

    const moments = await api.moments().catch(() => null);
    this.setData({ 'stats.moments': ((moments && moments.moments) || []).length });

    const logs = await api.checkins().catch(() => null);
    this.setData({ 'stats.logs': ((logs && logs.logs) || []).length });
  },

  goChat() {
    wx.switchTab({ url: '/pages/chat/chat' });
  },
  goTrips() {
    wx.switchTab({ url: '/pages/trips/trips' });
  },
  goNearby() {
    wx.switchTab({ url: '/pages/nearby/nearby' });
  },
  goProfile() {
    wx.switchTab({ url: '/pages/profile/profile' });
  },
  goSquare() {
    wx.navigateTo({ url: '/pages/square/square' });
  },
  goCheckin() {
    wx.navigateTo({ url: '/pages/checkin/checkin' });
  },
  goFriends() {
    wx.navigateTo({ url: '/pages/friends/friends' });
  },
  goAccount() {
    wx.navigateTo({ url: '/pages/account/account' });
  },
  openTrip(e) {
    wx.navigateTo({ url: '/pages/trip-detail/trip-detail?id=' + e.currentTarget.dataset.id });
  }
});
