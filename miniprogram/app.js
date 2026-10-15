const api = require('./utils/api.js');

App({
  globalData: {
    user: null
  },

  onLaunch() {
    // 只做一次「我是谁」的探测；具体页面在 onShow 里再确认一次，
    // 因为开发者工具重启后会话可能刚好过期。
    this.refreshUser().catch(() => {});
  },

  /** 刷新登录态：拿到 {name,email} 或 null */
  refreshUser() {
    return api.myInfo().then((info) => {
      this.globalData.user = info && info.login === 'true' ? info : null;
      return this.globalData.user;
    });
  },

  requireLogin() {
    if (this.globalData.user) return true;
    wx.navigateTo({ url: '/pages/login/login' });
    return false;
  }
});
