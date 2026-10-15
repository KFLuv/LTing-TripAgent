const api = require('../../utils/api.js');
const media = require('../../utils/media.js');

Page({
  data: {
    logs: [],
    creating: false,
    keyword: '',
    pois: [],
    coords: null,
    draft: {
      place: '', note: '', rating: '', durationMin: '', images: [],
      poiId: '', poiName: '', lat: '', lng: ''
    }
  },

  onShow() {
    this.load();
  },

  /** 从商户详情页「在这里打卡」进来时，直接把店名/坐标预填好，打开表单 */
  onLoad(options) {
    if (!options || !options.poiId) return;
    this.setData({
      creating: true,
      draft: {
        place: decodeURIComponent(options.place || ''),
        note: '', rating: '', durationMin: '', images: [],
        poiId: options.poiId,
        poiName: decodeURIComponent(options.poiName || ''),
        lat: '', lng: ''
      }
    });
    if (!this.data.coords) {
      api.currentLocation().then((loc) => this.setData({ coords: loc }));
    }
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
    const res = await api.checkins().catch(() => null);
    const logs = ((res && res.logs) || []).map((l) => ({
      id: l.id,
      place: l.place,
      poiId: l.poiId,
      poiName: l.poiName,
      note: l.note,
      rating: l.rating,
      durationMin: l.durationMin,
      createTime: l.createTime,
      images: l.images || []
    }));
    this.setData({ logs: logs });
  },

  open() {
    this.setData({ creating: true, keyword: '', pois: [] });
    if (!this.data.coords) {
      api.currentLocation().then((loc) => this.setData({ coords: loc }));
    }
  },
  close() {
    this.setData({ creating: false });
  },
  noop() {},

  onPlace(e) { this.setData({ 'draft.place': e.detail.value }); },
  onNote(e) { this.setData({ 'draft.note': e.detail.value }); },
  onRating(e) { this.setData({ 'draft.rating': e.detail.value }); },
  onDuration(e) { this.setData({ 'draft.durationMin': e.detail.value }); },
  onKeyword(e) { this.setData({ keyword: e.detail.value }); },

  async searchPoi() {
    const loc = this.data.coords || (await api.currentLocation());
    this.setData({ coords: loc });
    const res = await api
      .nearbySearch(loc.lat, loc.lng, 5000, this.data.keyword)
      .catch(() => null);
    if (!res || res.status !== 'success') {
      api.toast((res && res.message) || '搜索失败');
      return;
    }
    this.setData({
      pois: (res.pois || []).map((p) => ({
        poiId: p.poiId,
        name: p.name,
        city: p.city,
        address: p.address,
        lat: p.lat,
        lng: p.lng,
        rating: p.sourceRating || '',
        distanceText: p.distance ? Math.round(p.distance) + 'm' : ''
      }))
    });
  },

  pickPoi(e) {
    const p = this.data.pois[e.currentTarget.dataset.index];
    if (!p) return;
    // 带上城市 + 详细地址，打卡记录里就不会只显示一个不知道是哪家的店名
    const label = (p.name || '') + (p.city || p.address ? '（' + [p.city, p.address].filter(Boolean).join(' ') + '）' : '');
    this.setData({
      'draft.poiId': p.poiId || '',
      'draft.poiName': p.name || '',
      'draft.place': label,
      'draft.lat': p.lat || '',
      'draft.lng': p.lng || '',
      pois: []
    });
  },

  async pick() {
    const urls = await media.pickImages(3 - this.data.draft.images.length);
    if (!urls.length) return;
    this.setData({ 'draft.images': this.data.draft.images.concat(urls) });
  },

  previewImage(e) {
    media.preview(e.currentTarget.dataset.urls, e.currentTarget.dataset.i);
  },

  async submit() {
    const d = this.data.draft;
    if (!d.place && !d.poiId) {
      api.toast('写个地点，或从附近店铺里选一家');
      return;
    }
    const loc = this.data.coords || {};
    const res = await api.checkinSave({
      tripId: 0,
      place: d.place,
      lat: d.lat || (loc.real ? loc.lat : ''),
      lng: d.lng || (loc.real ? loc.lng : ''),
      rating: d.rating,
      note: d.note,
      durationMin: d.durationMin,
      poiId: d.poiId,
      poiName: d.poiName,
      images: d.images.length ? JSON.stringify(d.images) : '',
      visibility: 'public'
    }).catch(() => null);
    if (res && res.status === 'success') {
      api.toast('已打卡');
      this.setData({
        creating: false,
        draft: {
          place: '', note: '', rating: '', durationMin: '', images: [],
          poiId: '', poiName: '', lat: '', lng: ''
        }
      });
      this.load();
    } else {
      api.toast((res && res.message) || '打卡失败');
    }
  },

  remove(e) {
    const l = this.data.logs[e.currentTarget.dataset.index];
    if (!l) return;
    wx.showModal({
      title: '删除这条打卡？',
      confirmColor: '#ff3b30',
      success: async (r) => {
        if (!r.confirm) return;
        await api.checkinDelete(l.id).catch(() => null);
        this.load();
      }
    });
  }
});
