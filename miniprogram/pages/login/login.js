const api = require('../../utils/api.js');
const { baseUrl } = require('../../config.js');

Page({
  data: {
    mode: 'login',
    username: '',
    password: '',
    password2: '',
    email: '',
    loading: false,
    baseUrl: baseUrl
  },

  onUser(e) {
    this.setData({ username: e.detail.value });
  },
  onPwd(e) {
    this.setData({ password: e.detail.value });
  },
  onPwd2(e) {
    this.setData({ password2: e.detail.value });
  },
  onEmail(e) {
    this.setData({ email: e.detail.value });
  },
  toggleMode() {
    this.setData({ mode: this.data.mode === 'login' ? 'reg' : 'login' });
  },

  async doLogin() {
    const { username, password } = this.data;
    if (!username || !password) {
      api.toast('账号和密码都要填');
      return;
    }
    this.setData({ loading: true });
    try {
      // /login 成功会 302 到 home.html，wx.request 自动跟随，
      // 所以这里用 /myinfo 复核登录态，比解析 HTML 稳。
      await api.login(username, password, true);
      const ok = await this.confirmLogin();
      if (!ok) {
        api.toast('用户名或密码错误');
      }
    } catch (e) {
      // request 里已经提示过了
    } finally {
      this.setData({ loading: false });
    }
  },

  async doReg() {
    const { username, password, password2, email } = this.data;
    if (!username || !password) {
      api.toast('账号和密码都要填');
      return;
    }
    if (password !== password2) {
      api.toast('两次输入的密码不一致');
      return;
    }
    this.setData({ loading: true });
    try {
      const res = await api.register(username, password, email);
      const text = typeof res === 'string' ? res : JSON.stringify(res);
      if (text.indexOf('账号存在') >= 0 || text.indexOf('不能为空') >= 0) {
        api.toast(text.replace(/<[^>]+>/g, '').slice(0, 40));
        return;
      }
      // 注册接口会重定向到 login.html，没有自动登录，这里补一次登录
      await api.login(username, password, true);
      const ok = await this.confirmLogin();
      if (!ok) {
        api.toast('注册成功，请手动登录');
      }
    } catch (e) {
      // 忽略，已提示
    } finally {
      this.setData({ loading: false });
    }
  },

  async doGuest() {
    this.setData({ loading: true });
    try {
      await api.guest();
      await this.confirmLogin();
    } catch (e) {
      // 忽略
    } finally {
      this.setData({ loading: false });
    }
  },

  async confirmLogin() {
    const info = await api.myInfo();
    if (info && info.login === 'true') {
      getApp().globalData.user = info;
      wx.switchTab({ url: '/pages/home/home' });
      return true;
    }
    return false;
  }
});
