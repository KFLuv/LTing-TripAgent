const api = require('../../utils/api.js');

Page({
  data: {
    user: {},
    initial: '旅',
    form: { nickname: '', tags: '', bio: '', interests: '' },
    prefs: [],
    companions: [],
    documents: [],
    sheet: '',
    cf: { name: '', preferences: '', vote: '' },
    df: { docType: '', title: '', docNo: '', expireTime: '' }
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

    const comp = await api.companions().catch(() => null);
    this.setData({ companions: (comp && comp.companions) || [] });

    const docs = await api.documents().catch(() => null);
    this.setData({ documents: (docs && docs.documents) || [] });
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
    // 先让服务端把这个令牌作废，再清本地
    api.tokenLogout().catch(() => null);
    api.setToken('');
    getApp().globalData.user = null;
    wx.showToast({ title: '已退出登录', icon: 'none' });
    setTimeout(() => wx.navigateTo({ url: '/pages/login/login' }), 600);
  },

  goAccount() {
    wx.navigateTo({ url: '/pages/account/account' });
  },

  /* ---------- 同行人 ---------- */
  openCompanion() {
    this.setData({ sheet: 'companion', cf: { name: '', preferences: '', vote: '' } });
  },
  onCf(e) {
    this.setData({ ['cf.' + e.currentTarget.dataset.k]: e.detail.value });
  },
  async saveCompanion() {
    if (!this.data.cf.name.trim()) {
      api.toast('先写个称呼');
      return;
    }
    const res = await api.companionSave(this.data.cf).catch(() => null);
    api.toast((res && res.message) || (res && res.status === 'success' ? '已保存' : '保存失败'));
    this.setData({ sheet: '' });
    this.load();
  },
  removeCompanion(e) {
    const c = this.data.companions[e.currentTarget.dataset.index];
    if (!c) return;
    wx.showModal({
      title: '删掉同行人 ' + c.name + '？',
      confirmColor: '#ff3b30',
      success: async (r) => {
        if (!r.confirm) return;
        await api.companionDelete(c.id).catch(() => null);
        this.load();
      }
    });
  },

  /* ---------- 证件票据 ---------- */
  openDocument() {
    this.setData({ sheet: 'doc', df: { docType: '', title: '', docNo: '', expireTime: '' } });
  },
  onDf(e) {
    this.setData({ ['df.' + e.currentTarget.dataset.k]: e.detail.value });
  },
  async saveDocument() {
    const d = this.data.df;
    if (!d.title.trim() && !d.docType.trim()) {
      api.toast('类型或标题至少写一个');
      return;
    }
    const res = await api.documentSave(d).catch(() => null);
    api.toast((res && res.message) || (res && res.status === 'success' ? '已保存' : '保存失败'));
    this.setData({ sheet: '' });
    this.load();
  },
  removeDocument(e) {
    const d = this.data.documents[e.currentTarget.dataset.index];
    if (!d) return;
    wx.showModal({
      title: '删掉这份证件？',
      confirmColor: '#ff3b30',
      success: async (r) => {
        if (!r.confirm) return;
        await api.documentDelete(d.id).catch(() => null);
        this.load();
      }
    });
  },

  closeSheet() {
    this.setData({ sheet: '' });
  },
  noop() {}
});
