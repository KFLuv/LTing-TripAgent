/**
 * 小程序端全局配置。
 *
 * - 开发者工具里调试：保持 http://127.0.0.1:8078/chat/ ，
 *   并在「详情 → 本地设置」勾上「不校验合法域名」（project.config.json 里已默认关闭校验）。
 * - 真机预览 / 上线：必须换成 https 且域名已完成 ICP 备案，
 *   再到 mp.weixin.qq.com →「开发管理 → 开发设置 → 服务器域名」把它加进 request 合法域名。
 *   小程序不允许 http（除开发者工具），也不允许 IP 直连。
 */
/**
 * 后端地址。默认是本地开发用的 127.0.0.1；
 * 真机 / 体验版要指向 https 域名，可以在登录页「服务器地址」里直接改，
 * 改完存在本地缓存里，不用重新上传代码。
 */
const DEFAULT_BASE_URL = 'http://127.0.0.1:8078/chat/';

const BASE_URL_KEY = 'aitvBaseUrl';

function getBaseUrl() {
  try {
    return wx.getStorageSync(BASE_URL_KEY) || DEFAULT_BASE_URL;
  } catch (e) {
    return DEFAULT_BASE_URL;
  }
}

function setBaseUrl(url) {
  try {
    const v = (url || '').trim();
    if (!v) {
      wx.removeStorageSync(BASE_URL_KEY);
      return DEFAULT_BASE_URL;
    }
    const normalized = v.endsWith('/') ? v : v + '/';
    wx.setStorageSync(BASE_URL_KEY, normalized);
    return normalized;
  } catch (e) {
    return DEFAULT_BASE_URL;
  }
}

module.exports = {
  defaultBaseUrl: DEFAULT_BASE_URL,
  getBaseUrl: getBaseUrl,
  setBaseUrl: setBaseUrl,

  // 定位失败时的兜底坐标（广州塔），方便没有定位权限时也能跑通「附近吃喝玩乐」
  fallbackLocation: { lat: 23.10647, lng: 113.32455, name: '广州塔（兜底坐标）' }
};
