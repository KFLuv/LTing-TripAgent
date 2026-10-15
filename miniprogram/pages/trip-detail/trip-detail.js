const api = require('../../utils/api.js');

Page({
  data: { id: 0, trip: null, nodes: [], versions: [] },

  onLoad(options) {
    this.setData({ id: Number(options.id || 0) });
    this.load();
  },

  async load() {
    const user = await getApp().refreshUser().catch(() => null);
    if (!user) {
      wx.navigateTo({ url: '/pages/login/login' });
      return;
    }
    const res = await api.tripGet(this.data.id).catch(() => null);
    if (!res || res.status !== 'success') {
      api.toast((res && res.message) || '行程读取失败');
      return;
    }
    const t = res.trip || {};
    this.setData({
      trip: t,
      nodes: t.nodes || [],
      versions: t.versions || []
    });
    wx.setNavigationBarTitle({ title: t.title || '行程详情' });
    this.loadMoney();
  },

  /** 预算与行李：都挂在这条行程上 */
  async loadMoney() {
    const b = await api.budgetList(this.data.id).catch(() => null);
    this.setData({ budget: (b && b.entries) || [] });
    const s = await api.budgetSummary(this.data.id).catch(() => null);
    this.setData({
      budgetTotal: (s && s.total) || '0.00',
      threshold: (s && s.threshold) || ''
    });
    const p = await api.packingList().catch(() => null);
    this.setData({ packing: (p && p.templates) || [] });
  },

  rollback(e) {
    const versionId = e.currentTarget.dataset.id;
    wx.showModal({
      title: '回退到这个版本？',
      content: '当前内容会被这个快照覆盖（旧内容仍会留成新版本，可再回退）。',
      success: async (r) => {
        if (!r.confirm) return;
        const res = await api.tripVersionRollback(this.data.id, versionId).catch(() => null);
        api.toast(res && res.status === 'success' ? '已回退' : '回退失败');
        if (res && res.status === 'success') this.load();
      }
    });
  },

  /* ---------- 预算 ---------- */
  openBudget() {
    this.setData({ sheet: 'budget', bf: { category: '', item: '', amount: '', entryDate: '' } });
  },
  onBf(e) {
    this.setData({ ['bf.' + e.currentTarget.dataset.k]: e.detail.value });
  },
  async saveBudget() {
    const f = this.data.bf;
    if (!f.item.trim() || !f.amount) {
      api.toast('项目和金额要填');
      return;
    }
    const res = await api.budgetSave({
      tripId: this.data.id,
      category: f.category || 'other',
      item: f.item,
      amount: f.amount,
      entryDate: f.entryDate
    }).catch(() => null);
    api.toast((res && res.message) || (res && res.status === 'success' ? '已记账' : '保存失败'));
    this.setData({ sheet: '' });
    this.loadMoney();
  },
  removeBudget(e) {
    const b = this.data.budget[e.currentTarget.dataset.index];
    if (!b) return;
    wx.showModal({
      title: '删掉这笔账？',
      confirmColor: '#ff3b30',
      success: async (r) => {
        if (!r.confirm) return;
        await api.budgetDelete(b.id).catch(() => null);
        this.loadMoney();
      }
    });
  },
  setThreshold() {
    wx.showModal({
      title: '预算告警阈值',
      editable: true,
      placeholderText: '例如 3000',
      success: async (r) => {
        if (!r.confirm || !r.content) return;
        const res = await api
          .budgetThreshold({ tripId: this.data.id, threshold: r.content })
          .catch(() => null);
        api.toast((res && res.message) || (res && res.status === 'success' ? '已设置' : '设置失败'));
        this.loadMoney();
      }
    });
  },

  /* ---------- 行李 ---------- */
  openPacking() {
    this.setData({ sheet: 'packing', pf: { name: '', items: '' } });
  },
  onPf(e) {
    this.setData({ ['pf.' + e.currentTarget.dataset.k]: e.detail.value });
  },
  async savePacking() {
    if (!this.data.pf.name.trim()) {
      api.toast('先给清单起个名');
      return;
    }
    const res = await api.packingSave(this.data.pf).catch(() => null);
    api.toast((res && res.message) || (res && res.status === 'success' ? '已保存' : '保存失败'));
    this.setData({ sheet: '' });
    this.loadMoney();
  },
  removePacking(e) {
    const p = this.data.packing[e.currentTarget.dataset.index];
    if (!p) return;
    wx.showModal({
      title: '删掉这份清单？',
      confirmColor: '#ff3b30',
      success: async (r) => {
        if (!r.confirm) return;
        await api.packingDelete(p.id).catch(() => null);
        this.loadMoney();
      }
    });
  },

  closeSheet() {
    this.setData({ sheet: '' });
  },
  noop() {}
});
