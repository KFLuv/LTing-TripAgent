const api = require('../../utils/api.js');

Page({
  data: {
    user: {},
    form: {
      currentPassword: '', newPassword: '', confirmPassword: '',
      currentPassword2: '', newEmail: ''
    }
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
    this.setData({ user: user });
  },

  on(e) {
    this.setData({ ['form.' + e.currentTarget.dataset.k]: e.detail.value });
  },

  async changePassword() {
    const f = this.data.form;
    if (!f.currentPassword || !f.newPassword) {
      api.toast('当前密码和新密码都要填');
      return;
    }
    if (f.newPassword !== f.confirmPassword) {
      api.toast('两次输入的新密码不一致');
      return;
    }
    const res = await api.updateUserInfo({
      currentPassword: f.currentPassword,
      newPassword: f.newPassword,
      confirmPassword: f.confirmPassword
    }).catch(() => null);
    api.toast(pickMessage(res, '密码已更新'));
  },

  async bindEmail() {
    const f = this.data.form;
    if (!f.currentPassword2 || !f.newEmail) {
      api.toast('要填邮箱和当前密码');
      return;
    }
    const res = await api.updateUserInfo({
      currentPassword: f.currentPassword2,
      newEmail: f.newEmail
    }).catch(() => null);
    api.toast(pickMessage(res, '邮箱已保存'));
    this.load();
  },

  unbindEmail() {
    const pwd = this.data.form.currentPassword2;
    if (!pwd) {
      api.toast('解绑要填当前密码');
      return;
    }
    wx.showModal({
      title: '解绑邮箱？',
      content: '解绑后邮箱不再用于任何用途。',
      success: async (r) => {
        if (!r.confirm) return;
        const res = await api.unbindEmail(pwd).catch(() => null);
        api.toast(pickMessage(res, '已解绑'));
        this.load();
      }
    });
  },

  removeAccount() {
    wx.showModal({
      title: '确定注销账号？',
      editable: true,
      placeholderText: '把用户名原样输入一遍以确认',
      confirmText: '注销',
      confirmColor: '#ff3b30',
      success: (r1) => {
        if (!r1.confirm) return;
        const name = this.data.user.name || '';
        if ((r1.content || '').trim() !== name) {
          api.toast('用户名没对上，已取消');
          return;
        }
        wx.showModal({
          title: '再确认一次',
          editable: true,
          placeholderText: '输入当前密码',
          confirmText: '注销',
          confirmColor: '#ff3b30',
          success: async (r2) => {
            if (!r2.confirm) return;
            const res = await api.accountDelete(r2.content || '', name).catch(() => null);
            if (res && res.status === 'success') {
              api.setToken('');
              getApp().globalData.user = null;
              wx.showToast({ title: '账号已注销', icon: 'none' });
              setTimeout(() => wx.navigateTo({ url: '/pages/login/login' }), 800);
            } else {
              api.toast((res && res.message) || '注销失败');
            }
          }
        });
      }
    });
  }
});

function pickMessage(res, okText) {
  if (!res) return '请求失败';
  if (res.status === 'success') return res.message || okText;
  return res.message || '操作失败';
}
