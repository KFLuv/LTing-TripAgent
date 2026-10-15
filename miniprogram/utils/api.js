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
  budgetSummary: function () {
    return request('toolbox/budget/summary');
  },
  packingList: function () {
    return request('toolbox/packing/list');
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
    return request('nearby/menu/list', { data: { poiId: poiId } });
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
  checkins: function () {
    return request('travel-log/list');
  }
};
