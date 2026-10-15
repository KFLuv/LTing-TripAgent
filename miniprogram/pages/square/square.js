const api = require('../../utils/api.js');
const media = require('../../utils/media.js');

const VIS_TEXT = { public: '公开', friends: '仅好友', private: '仅自己' };

Page({
  data: {
    tab: 'square',
    list: [],
    publishing: false,
    draft: { content: '', place: '', visibility: 'public', images: [] },
    coords: null
  },

  onShow() {
    this.load();
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh());
  },

  switchTab(e) {
    const t = e.currentTarget.dataset.t;
    if (t === 'friends') {
      wx.navigateTo({ url: '/pages/friends/friends' });
      return;
    }
    this.setData({ tab: t });
    this.load();
  },

  async load() {
    const user = await getApp().refreshUser().catch(() => null);
    if (!user) {
      wx.navigateTo({ url: '/pages/login/login' });
      return;
    }
    const res = this.data.tab === 'square'
      ? await api.momentSquare().catch(() => null)
      : await api.moments().catch(() => null);
    const list = ((res && res.moments) || []).map((m) => ({
      key: (m.username || '') + '-' + m.id,
      id: m.id,
      username: m.username,
      nickname: m.nickname,
      content: m.content,
      place: m.place,
      createTime: m.createTime,
      images: m.images || [],
      visibility: m.visibility || 'public',
      visText: VIS_TEXT[m.visibility] || ''
    }));
    this.setData({ list: list });
  },

  previewImage(e) {
    media.preview(e.currentTarget.dataset.urls, e.currentTarget.dataset.i);
  },

  openPublish() {
    this.setData({ publishing: true });
    if (!this.data.coords) {
      api.currentLocation().then((loc) => this.setData({ coords: loc }));
    }
  },
  closePublish() {
    this.setData({ publishing: false });
  },
  noop() {},

  onContent(e) {
    this.setData({ 'draft.content': e.detail.value });
  },
  onPlace(e) {
    this.setData({ 'draft.place': e.detail.value });
  },
  setVis(e) {
    this.setData({ 'draft.visibility': e.currentTarget.dataset.v });
  },

  async pick() {
    const urls = await media.pickImages(3 - this.data.draft.images.length);
    if (!urls.length) return;
    this.setData({ 'draft.images': this.data.draft.images.concat(urls) });
  },

  async publish() {
    const d = this.data.draft;
    if (!d.content.trim()) return;
    const loc = this.data.coords || {};
    const res = await api.momentSave({
      content: d.content.trim(),
      place: d.place || loc.name || '',
      lat: loc.real ? loc.lat : '',
      lng: loc.real ? loc.lng : '',
      images: d.images.length ? JSON.stringify(d.images) : '',
      visibility: d.visibility
    }).catch(() => null);
    if (res && res.status === 'success') {
      api.toast('已发布');
      this.setData({
        publishing: false,
        tab: 'mine',
        draft: { content: '', place: '', visibility: 'public', images: [] }
      });
      this.load();
    } else {
      api.toast((res && res.message) || '发布失败');
    }
  },

  removeMoment(e) {
    if (this.data.tab === 'square') return;
    const m = this.data.list[e.currentTarget.dataset.index];
    if (!m) return;
    wx.showModal({
      title: '删除这条动态？',
      content: '删掉就找不回来了。',
      confirmColor: '#ff3b30',
      success: async (r) => {
        if (!r.confirm) return;
        await api.momentDelete(m.id).catch(() => null);
        this.load();
      }
    });
  }
});
