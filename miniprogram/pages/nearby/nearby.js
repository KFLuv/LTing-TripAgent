const api = require('../../utils/api.js');

const OPTIONS = [
  { label: '1km', v: 1000 },
  { label: '3km', v: 3000 },
  { label: '5km', v: 5000 },
  { label: '10km', v: 10000 }
];

Page({
  data: {
    radiusOptions: OPTIONS,
    radius: 5000,
    keyword: '',
    pois: [],
    loading: false,
    located: false,
    coords: null,
    detail: null,
    menu: [],
    reviews: []
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
    if (!this.data.coords) {
      const loc = await api.currentLocation();
      this.setData({
        coords: { lat: loc.lat, lng: loc.lng },
        located: !!loc.real
      });
    }
    this.search();
  },

  setRadius(e) {
    this.setData({ radius: Number(e.currentTarget.dataset.v) });
    this.search();
  },

  onKeyword(e) {
    this.setData({ keyword: e.detail.value });
  },

  async search() {
    const { coords, radius, keyword } = this.data;
    if (!coords) return;
    this.setData({ loading: true });
    const res = await api
      .nearbySearch(coords.lat, coords.lng, radius, keyword)
      .catch(() => null);
    this.setData({ loading: false });
    if (!res || res.status !== 'success') {
      this.setData({ pois: [] });
      if (res && res.message) api.toast(res.message);
      return;
    }
    const pois = (res.pois || []).map((p) => ({
      poiId: p.poiId,
      name: p.name || '未命名',
      city: p.city || '',
      address: p.address || '',
      tel: p.tel || '',
      cost: p.cost || '',
      lat: p.lat,
      lng: p.lng,
      rating: p.avgRating || p.sourceRating || '',
      cover: (p.photos && p.photos[0]) || '',
      distanceText: p.distance ? Math.round(p.distance) + 'm' : ''
    }));
    this.setData({ pois: pois });
  },

  async openDetail(e) {
    const poiId = e.currentTarget.dataset.id;
    const base = this.data.pois[e.currentTarget.dataset.index] || {};
    this.setData({ detail: base, menu: [], reviews: [] });
    const d = await api.nearbyDetail(poiId).catch(() => null);
    if (d && d.poi) {
      this.setData({
        detail: Object.assign({}, base, {
          address: d.poi.address || base.address,
          tel: d.poi.tel || base.tel,
          cost: d.poi.cost || base.cost,
          sourceRating: d.poi.sourceRating || base.rating,
          lat: d.poi.lat || base.lat,
          lng: d.poi.lng || base.lng
        })
      });
    }
    const m = await api.nearbyMenu(poiId).catch(() => null);
    if (m && m.items) this.setData({ menu: m.items });
    const r = await api.nearbyReviews(poiId).catch(() => null);
    if (r && r.reviews) this.setData({ reviews: r.reviews });
  },

  closeDetail() {
    this.setData({ detail: null });
  },

  noop() {},

  navigate() {
    const d = this.data.detail || {};
    if (!d.lat || !d.lng) {
      api.toast('这家店没有坐标，没法导航');
      return;
    }
    wx.openLocation({
      latitude: Number(d.lat),
      longitude: Number(d.lng),
      name: d.name,
      address: d.address || '',
      scale: 16
    });
  }
});
