const api = require('../../utils/api.js');

Page({
  data: {
    user: {},
    initial: '旅',
    form: { nickname: '', tags: '', bio: '', interests: '' },
    prefs: []
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
    this.setData({
      user: user,
      initial: ((user.name || '旅')[0] || '旅').toUpperCase()
    });

    const p = await api.profileGet().catch(() => null);
    const pf = (p && p.profile) || {};
    this.setData({
      form: {
        nickname: pf.nickname || '',
        tags: pf.tags || '',
        bio: pf.bio || '',
        interests: pf.interests || ''
      }
    });

    const prefs = await api.preferences().catch(() => null);
    this.setData({ prefs: (prefs && prefs.items) || [] });
  },

  onField(e) {
    const k = e.currentTarget.dataset.k;
    this.setData({ ['form.' + k]: e.detail.value });
  },

  async saveProfile() {
    const res = await api.profileSave(this.data.form).catch(() => null);
    api.toast(res && res.status === 'success' ? '已保存' : '保存失败');
  },

  editPref(e) {
    const { tag, value } = e.currentTarget.dataset;
    wx.showModal({
      title: tag,
      editable: true,
      placeholderText: value || '填这一条的取值',
      success: async (r) => {
        if (!r.confirm) return;
        const res = await api.preferenceSave({ tag: tag, value: r.content || '' }).catch(() => null);
        api.toast(res && res.status === 'success' ? '已更新画像' : '更新失败');
        this.load();
      }
    });
  },

  addPref() {
    wx.showModal({
      title: '新的偏好标签',
      editable: true,
      placeholderText: '例如：饮食忌口 / 步行耐受 / 预算区间',
      success: (r1) => {
        if (!r1.confirm || !r1.content) return;
        const tag = r1.content.trim();
        wx.showModal({
          title: tag,
          editable: true,
          placeholderText: '例如：不吃辣 / 走不了太久 / 人均 300 内',
          success: async (r2) => {
            if (!r2.confirm) return;
            const res = await api
              .preferenceSave({ tag: tag, value: r2.content || '' })
              .catch(() => null);
            api.toast(res && res.status === 'success' ? '已加入画像' : '保存失败');
            this.load();
          }
        });
      }
    });
  },

  logout() {
    getApp().globalData.user = null;
    wx.showToast({ title: '已退出本地登录态', icon: 'none' });
    setTimeout(() => wx.navigateTo({ url: '/pages/login/login' }), 600);
  }
});
