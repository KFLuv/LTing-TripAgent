const api = require('../../utils/api.js');
const conf = require('../../config.js');

Page({
  data: {
    mode: 'login',
    username: '',
    password: '',
    password2: '',
    email: '',
    loading: false,
    baseUrl: conf.getBaseUrl()
  },

  /** 真机 / 体验版要指到 https 域名，这里改完存本地，不用重新上传代码 */
  editBaseUrl() {
    wx.showModal({
      title: '后端地址',
      editable: true,
      placeholderText: conf.getBaseUrl(),
      content: '',
      success: (r) => {
        if (!r.confirm) return;
        const v = conf.setBaseUrl(r.content);
        this.setData({ baseUrl: v });
        api.toast('已切到 ' + v);
      }
    });
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
    const username = (this.data.username || '').trim();
    const password = this.data.password || '';
    if (!username || !password) {
      api.toast('账号和密码都要填');
      return;
    }
    this.setData({ loading: true });
    try {
      // 走令牌接口：拿到 token 存本地，后续请求带 X-Auth-Token。
      // 不再依赖 Cookie（微信对跨站 Set-Cookie 会直接丢，之前「登录了却说没登录」就是它）
      await this.loginByToken(api.tokenLogin(username, password));
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
      // 注册接口只负责建号（返回 HTML），这里紧接着用令牌接口登录
      await this.loginByToken(api.tokenLogin(username.trim(), password));
    } catch (e) {
      // 忽略，已提示
    } finally {
      this.setData({ loading: false });
    }
  },

  async doGuest() {
    this.setData({ loading: true });
    try {
      await this.loginByToken(api.tokenGuest());
    } catch (e) {
      // 忽略
    } finally {
      this.setData({ loading: false });
    }
  },

  /** 统一处理：拿 token → 存起来 → 用 /myinfo 复核 → 进首页 */
  async loginByToken(promise) {
    const res = await promise.catch(() => null);
    if (!res || res.status !== 'success' || !res.token) {
      api.toast((res && res.message) || '登录失败，请重试');
      return;
    }
    api.setToken(res.token);
    const info = await api.myInfo().catch(() => null);
    if (!info || info.login !== 'true') {
      api.setToken('');
      api.toast('拿到令牌但身份没生效，请检查后端是否是新版本');
      return;
    }
    getApp().globalData.user = info;
    wx.switchTab({ url: '/pages/home/home' });
  }
});
