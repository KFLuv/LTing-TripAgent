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
    locText: '正在定位…',
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
      await this.locate();
    }
    this.search();
  },

  /** 自动定位：拿到就把坐标报给后端，拿不到就说清原因（不再悄悄用兜底坐标） */
  async locate() {
    const loc = await api.currentLocation();
    this.setData({
      coords: { lat: loc.lat, lng: loc.lng },
      located: !!loc.real,
      locText: loc.real ? '已定位' : ('没拿到定位：' + (loc.error || '') + '（暂用兜底坐标）')
    });
    if (loc.real) {
      api.reportLocation({ lat: loc.lat, lng: loc.lng, place: '', accuracy: loc.accuracy || '' });
    }
    return loc;
  },

  /** 「我的位置」：被拒过就引导去设置页开权限，再重新定位并刷新列表 */
  relocate() {
    wx.getSetting({
      success: (s) => {
        if (s.authSetting['scope.userLocation'] === false) {
          wx.showModal({
            title: '定位被拒绝了',
            content: '去「设置」里允许位置信息，才能按真实位置找店、算距离和导航。',
            confirmText: '去设置',
            success: (r) => {
              if (r.confirm) {
                wx.openSetting({
                  complete: async () => {
                    await this.locate();
                    this.search();
                  }
                });
              }
            }
          });
          return;
        }
        this.locate().then(() => this.search());
      },
      fail: () => this.locate().then(() => this.search())
    });
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

  /** 点一家店 → 进完整详情页（店铺图/评级/地址/菜单/打卡记录/点评/导航） */
  openDetail(e) {
    wx.navigateTo({ url: '/pages/poi-detail/poi-detail?poiId=' + e.currentTarget.dataset.id });
  }
});
