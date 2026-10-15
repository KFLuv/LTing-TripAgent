/** 后端接口封装。路径与 backend controller 的 @PostMapping 一一对应。 */
const req = require('./request.js');

const request = req.request;

module.exports = {
  currentLocation: req.currentLocation,
  toast: req.toast,
  getToken: req.getToken,
  setToken: req.setToken,

  // ---------- 小程序端登录（令牌版，不依赖 Cookie） ----------
  tokenLogin: function (username, password) {
    return request('api/auth/login', { data: { username: username, password: password } });
  },
  tokenGuest: function () {
    return request('api/auth/guest');
  },
  tokenLogout: function () {
    return request('api/auth/logout', { silent: true });
  },
  tokenMe: function () {
    return request('api/auth/me', { silent: true });
  },

  // ---------- 账号 ----------
  login: function (username, password, remember) {
    // 后端返回 302 → home.html，wx.request 会自动跟随，因此登录结果用 /myinfo 复核
    return request('login', {
      data: { username: username, password: password, remember: remember ? 'on' : '' }
    });
  },
  register: function (username, password, email) {
    return request('reg', { data: { username: username, password: password, email: email } });
  },
  guest: function () {
    return request('guest');
  },
  myInfo: function () {
    return request('myinfo', { silent: true });
  },
  updateUserInfo: function (data) {
    return request('updateUserInfo', { data: data });
  },

  // ---------- AI 管家 ----------
  agentAvailable: function () {
    return request('agent/available', { silent: true });
  },
  chat: function (message, sessionId) {
    const path = sessionId ? 'agent/chat?sessionId=' + sessionId : 'agent/chat';
    return request(path, { raw: message });
  },
  history: function (sessionId, limit) {
    return request('agent/history', {
      data: { sessionId: sessionId || '', limit: limit || 50 }
    });
  },
  sessions: function () {
    return request('agent/session/list');
  },
  sessionNew: function () {
    return request('agent/session/new');
  },
  sessionRename: function (sessionId, title) {
    return request('agent/session/rename', { data: { sessionId: sessionId, title: title } });
  },
  sessionDelete: function (sessionId) {
    return request('agent/session/delete', { data: { sessionId: sessionId } });
  },

  // ---------- 行程本 ----------
  trips: function () {
    return request('trips/list');
  },
  tripGet: function (id) {
    return request('trips/get', { data: { id: id } });
  },
  tripSave: function (title, content) {
    return request('trips/save', { data: { title: title, content: content || '' } });
  },
  tripDelete: function (id) {
    return request('trips/delete', { data: { id: id } });
  },
  tripVersionRollback: function (id, versionId) {
    return request('trips/version/rollback', { data: { id: id, versionId: versionId } });
  },

  // ---------- 附近吃喝玩乐 ----------
  nearbySearch: function (lat, lng, radius, keyword, limit) {
    return request('nearby/search', {
      data: { lat: lat, lng: lng, radius: radius, keyword: keyword || '', limit: limit || 0 }
    });
  },
  nearbyDetail: function (poiId) {
    return request('nearby/detail', { data: { poiId: poiId } });
  },
  nearbyReviews: function (poiId) {
    return request('nearby/review/list', { data: { poiId: poiId } });
  },
  nearbyMenu: function (poiId) {
    // 返回体是 {status, menu:[...]}
    return request('nearby/menu/list', { data: { poiId: poiId } });
  },
  nearbyReviewSave: function (data) {
    return request('nearby/review/save', { data: data });
  },
  nearbyReviewDelete: function (id) {
    return request('nearby/review/delete', { data: { id: id } });
  },
  checkinsByPoi: function (poiId) {
    return request('travel-log/by-poi', { data: { poiId: poiId } });
  },
  /** 把当前定位上报给后端（管家排行程、附近搜索都以它为准） */
  reportLocation: function (data) {
    return request('agent/location', { data: data, silent: true });
  },
  /** 反查地名：「我的位置」先定位、再显示到具体地点，就是靠它 */
  reverse: function (lat, lng) {
    return request('agent/reverse', { data: { lat: lat, lng: lng }, silent: true });
  },
  /** 地名 → 坐标：回答里只有地名没有坐标时，用它把地图画出来 */
  geocode: function (address, city) {
    return request('agent/geocode', { data: { address: address, city: city || '' }, silent: true });
  },

  // ---------- 个人资料 / 偏好档案 ----------
  profileGet: function () {
    return request('profile/get');
  },
  profileSave: function (data) {
    return request('profile/save', { data: data });
  },
  preferences: function () {
    return request('preference/list');
  },
  preferenceSave: function (data) {
    return request('preference/item/save-current', { data: data });
  },

  // ---------- 动态 / 打卡 ----------
  momentSquare: function () {
    return request('moment/square');
  },
  moments: function () {
    return request('moment/list');
  },
  momentSave: function (data) {
    return request('moment/save', { data: data });
  },
  momentDelete: function (id) {
    return request('moment/delete', { data: { id: id } });
  },
  checkins: function () {
    return request('travel-log/list');
  },
  checkinSave: function (data) {
    return request('travel-log/save', { data: data });
  },
  checkinDelete: function (id) {
    return request('travel-log/delete', { data: { id: id } });
  },

  // ---------- 好友 / 他人主页 ----------
  friends: function () {
    return request('friend/list');
  },
  friendAdd: function (target) {
    return request('friend/add', { data: { target: target } });
  },
  friendDelete: function (target) {
    // 注意：这个接口的参数名是 friend，不是 target
    return request('friend/delete', { data: { friend: target } });
  },
  userHome: function (username) {
    return request('user/home', { data: { username: username } });
  },

  // ---------- 同行人 / 证件 ----------
  companions: function () {
    return request('companion/list');
  },
  companionSave: function (data) {
    return request('companion/save', { data: data });
  },
  companionDelete: function (id) {
    return request('companion/delete', { data: { id: id } });
  },
  documents: function () {
    return request('toolbox/document/list');
  },
  documentSave: function (data) {
    return request('toolbox/document/save', { data: data });
  },
  documentDelete: function (id) {
    return request('toolbox/document/delete', { data: { id: id } });
  },

  // ---------- 预算 / 行李（绑在行程上） ----------
  budgetList: function (tripId) {
    return request('toolbox/budget/list', { data: { tripId: tripId } });
  },
  budgetSummary: function (tripId) {
    return request('toolbox/budget/summary', { data: { tripId: tripId } });
  },
  budgetSave: function (data) {
    return request('toolbox/budget/save', { data: data });
  },
  budgetDelete: function (id) {
    return request('toolbox/budget/delete', { data: { id: id } });
  },
  budgetThreshold: function (data) {
    return request('toolbox/budget/threshold', { data: data });
  },
  packingList: function (tripId) {
    return request('toolbox/packing/list', { data: { tripId: tripId } });
  },
  packingSave: function (data) {
    return request('toolbox/packing/save', { data: data });
  },
  packingDelete: function (id) {
    return request('toolbox/packing/delete', { data: { id: id } });
  },

  // ---------- 偏好模板 ----------
  prefTemplates: function () {
    return request('preference/template/list');
  },
  prefTemplateActivate: function (id) {
    return request('preference/template/activate', { data: { id: id } });
  },
  prefTemplateDelete: function (id) {
    return request('preference/template/delete', { data: { id: id } });
  },
  prefTemplateCopy: function (id) {
    return request('preference/template/copy', { data: { id: id } });
  },

  // ---------- 账号管理 ----------
  unbindEmail: function (password) {
    return request('account/unbind-email', { data: { password: password } });
  },
  accountDelete: function (password, confirm) {
    return request('account/delete', { data: { password: password, confirm: confirm } });
  }
};
