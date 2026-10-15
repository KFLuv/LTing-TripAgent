const api = require('../../utils/api.js');
const media = require('../../utils/media.js');

Page({
  data: {
    poiId: '',
    poi: null,
    menu: [],
    reviews: [],
    logs: [],
    myRating: '',
    myContent: '',
    locText: '',
    coords: null
  },

  onLoad(options) {
    this.setData({ poiId: options.poiId || '' });
  },

  onShow() {
    this.load();
    this.locate();
  },

  async load() {
    const user = await getApp().refreshUser().catch(() => null);
    if (!user) {
      wx.navigateTo({ url: '/pages/login/login' });
      return;
    }
    const d = await api.nearbyDetail(this.data.poiId).catch(() => null);
    if (!d || d.status !== 'success' || !d.poi) {
      api.toast((d && d.message) || '读不到这家店');
      return;
    }
    const p = d.poi;
    const poi = {
      poiId: p.poiId,
      name: p.name,
      city: p.city,
      address: p.address,
      tel: p.tel,
      cost: p.cost,
      tag: p.tag,
      sourceRating: p.sourceRating,
      photos: p.photos || [],
      lat: Number(p.lat) || 0,
      lng: Number(p.lng) || 0,
      distanceText: p.distance ? Math.round(p.distance) + 'm' : ''
    };
    this.setData({
      poi: poi,
      menu: d.poi.menu || [],
      circles: [{
        latitude: poi.lat,
        longitude: poi.lng,
        radius: 60,
        color: '#0071e3cc',
        fillColor: '#0071e333',
        strokeWidth: 2
      }]
    });
    wx.setNavigationBarTitle({ title: poi.name || '商户详情' });

    const m = await api.nearbyMenu(this.data.poiId).catch(() => null);
    if (m && m.menu) this.setData({ menu: m.menu });
    const r = await api.nearbyReviews(this.data.poiId).catch(() => null);
    if (r && r.reviews) this.setData({ reviews: r.reviews });
    const l = await api.checkinsByPoi(this.data.poiId).catch(() => null);
    if (l && l.logs) this.setData({ logs: l.logs });
  },

  /** 自动定位 + 把位置报给后端（管家与附近搜索都以它为准） */
  async locate() {
    const loc = await api.currentLocation();
    this.setData({
      coords: loc,
      locText: loc.real ? '已定位' : '没拿到定位（' + (loc.name || '') + '）；可在下方重新定位'
    });
    this.drawLine(loc);
    if (loc.real) {
      api.reportLocation({ lat: loc.lat, lng: loc.lng, place: '', accuracy: loc.accuracy || '' });
    }
  },

  /** 我 → 店 的一条线，让"内嵌地图"不只是个点 */
  drawLine(loc) {
    const p = this.data.poi;
    if (!p || !p.lat || !p.lng || !loc || !loc.real) {
      this.setData({ polyline: [] });
      return;
    }
    this.setData({
      polyline: [{
        points: [
          { latitude: loc.lat, longitude: loc.lng },
          { latitude: p.lat, longitude: p.lng }
        ],
        color: '#0071e3',
        width: 4,
        arrowLine: true
      }]
    });
  },

  relocate() {
    wx.getSetting({
      success: (s) => {
        if (s.authSetting['scope.userLocation'] === false) {
          wx.showModal({
            title: '定位被拒绝了',
            content: '去「设置」里允许位置信息，才能算距离、导航和自动锚定位置。',
            confirmText: '去设置',
            success: (r) => {
              if (r.confirm) wx.openSetting({ complete: () => this.locate() });
            }
          });
          return;
        }
        this.locate();
      },
      fail: () => this.locate()
    });
  },

  preview(e) {
    media.preview(e.currentTarget.dataset.urls, e.currentTarget.dataset.i);
  },

  copyAddress() {
    const p = this.data.poi || {};
    wx.setClipboardData({ data: (p.city || '') + (p.address || '') });
  },

  copyForDianping() {
    const p = this.data.poi || {};
    wx.setClipboardData({
      data: p.name + ' ' + (p.city || '') + (p.address || ''),
      success: () => api.toast('已复制，去大众点评 App 里粘贴搜索')
    });
  },

  call() {
    const tel = (this.data.poi || {}).tel;
    if (tel) wx.makePhoneCall({ phoneNumber: tel });
  },

  navigate() {
    const p = this.data.poi || {};
    if (!p.lat || !p.lng) {
      api.toast('这家店没有坐标，没法导航');
      return;
    }
    wx.openLocation({
      latitude: p.lat,
      longitude: p.lng,
      name: p.name,
      address: (p.city || '') + (p.address || ''),
      scale: 17
    });
  },

  goCheckin() {
    const p = this.data.poi || {};
    wx.navigateTo({
      url: '/pages/checkin/checkin?poiId=' + p.poiId +
        '&poiName=' + encodeURIComponent(p.name || '') +
        '&place=' + encodeURIComponent((p.name || '') + '（' + [p.city, p.address].filter(Boolean).join(' ') + '）')
    });
  },

  onRating(e) { this.setData({ myRating: e.detail.value }); },
  onContent(e) { this.setData({ myContent: e.detail.value }); },

  async submitReview() {
    if (!this.data.myContent.trim()) {
      api.toast('写两句再发布');
      return;
    }
    const res = await api.nearbyReviewSave({
      poiId: this.data.poiId,
      poiName: (this.data.poi || {}).name || '',
      rating: this.data.myRating || '',
      content: this.data.myContent.trim()
    }).catch(() => null);
    api.toast((res && res.message) || (res && res.status === 'success' ? '已发布' : '发布失败'));
    if (res && res.status === 'success') {
      this.setData({ myContent: '', myRating: '' });
      this.load();
    }
  }
});
