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
    coords: null,
    originText: '还没起点（可用下面的输入框手填）',
    originInput: ''
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
    this.setData({ originText: '我的位置（自动定位）' });
  },

  onOriginInput(e) {
    this.setData({ originInput: e.detail.value });
  },

  /**
   * 手填起点：拿不到定位（比如开发者工具没开位置模拟）时，把地名换成坐标当起点，
   * 页面内的地图照样能看到"从哪到店"的连线——系统地图的起点不受我们控制，这条只救内嵌地图。
   */
  async setOrigin() {
    const kw = (this.data.originInput || '').trim();
    if (!kw) {
      api.toast('先写个起点，比如：广州塔');
      return;
    }
    const g = await api.geocode(kw);
    if (!g || !g.ok || !g.lat || !g.lng) {
      api.toast('这个地名没解析出坐标，换个更完整的写法');
      return;
    }
    const p = this.data.poi || {};
    this.setData({
      originText: (g.name || kw) + '（手填）',
      polyline: [{
        points: [
          { latitude: Number(g.lat), longitude: Number(g.lng) },
          { latitude: p.lat, longitude: p.lng }
        ],
        color: '#ff9500',
        width: 4,
        arrowLine: true
      }]
    });
    api.toast('已把「' + (g.name || kw) + '」设为起点');
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

  /**
   * 一键导航。注意「定位失败，请手动输入位置」是**内置地图页**在拿不到
   * "你从哪出发"时的提示——目的地坐标我们是有的。所以这里先确认有起点，
   * 没有就先把原因讲清楚，别让用户对着地图页干瞪眼。
   */
  async navigate() {
    const p = this.data.poi || {};
    if (!p.lat || !p.lng) {
      api.toast('这家店没有坐标，没法导航');
      return;
    }
    let me = this.data.coords;
    if (!me || !me.real) {
      me = await api.currentLocation();
      this.setData({ coords: me, locText: me.real ? '已定位' : ('没拿到定位：' + (me.error || '')) });
    }
    if (!me.real) {
      const go = await confirmModal(
        '地图缺"你在哪"',
        '目的地已经传过去了，但拿不到你的位置（' + (me.error || '未知原因') + '），'
          + '地图里就没有起点。先开定位更准，也可以只打开目的地。',
        '仍然打开',
        '去开定位'
      );
      if (go === 'setting') {
        wx.openSetting({ complete: () => this.locate() });
        return;
      }
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

/** 返回 'ok'(确认) / 'setting'(去设置) / ''(取消) */
function confirmModal(title, content, confirmText, cancelText) {
  return new Promise(function (resolve) {
    wx.showModal({
      title: title,
      content: content,
      confirmText: confirmText,
      cancelText: cancelText,
      success: function (r) {
        resolve(r.confirm ? 'ok' : (r.cancel ? 'setting' : ''));
      },
      fail: function () {
        resolve('');
      }
    });
  });
}
