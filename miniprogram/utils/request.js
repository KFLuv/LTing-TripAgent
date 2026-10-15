const { baseUrl } = require('../config.js');

/** 令牌存在本地缓存里：小程序对 Cookie 不可靠（跨站 Set-Cookie 会被丢），身份走这个头 */
const TOKEN_KEY = 'aitvToken';

function getToken() {
  try {
    return wx.getStorageSync(TOKEN_KEY) || '';
  } catch (e) {
    return '';
  }
}

function setToken(token) {
  try {
    if (token) {
      wx.setStorageSync(TOKEN_KEY, token);
    } else {
      wx.removeStorageSync(TOKEN_KEY);
    }
  } catch (e) {
    // 缓存写不进去就当作未登录
  }
}

function toast(title) {
  wx.showToast({ title: title, icon: 'none', duration: 2400 });
}

/**
 * 统一请求。
 *
 * 后端所有业务接口都是 POST：
 *  - 大多数用 @RequestParam 接表单 → 默认 content-type: application/x-www-form-urlencoded
 *  - /agent/chat 用 @RequestBody String 接纯文本 → 传 raw
 *
 * 身份：后端靠 JSESSIONID。微信客户端的网络层会自动保存 Set-Cookie 并在后续
 * 但小程序里 Cookie 很不可靠，所以这里优先带 {@code X-Auth-Token}：
 * 后端 TokenAuthFilter 会把它翻译成会话里的登录身份，页面代码不用管这层。
 */
function request(path, options) {
  const opt = options || {};
  const method = opt.method || 'POST';
  const isRaw = typeof opt.raw === 'string';
  const header = Object.assign({}, opt.header);

  if (isRaw) {
    header['Content-Type'] = 'text/plain;charset=UTF-8';
    header['Accept'] = opt.accept || 'application/json';
  } else if (method !== 'GET') {
    header['Content-Type'] = 'application/x-www-form-urlencoded';
    header['Accept'] = 'application/json';
  }

  const token = getToken();
  if (token) {
    header['X-Auth-Token'] = token;
  }

  return new Promise(function (resolve, reject) {
    wx.request({
      url: baseUrl + path,
      method: method,
      data: isRaw ? opt.raw : (opt.data || {}),
      header: header,
      // Agent 一轮最多 12 次工具调用，慢的时候要几十秒，别用默认 60s 卡掉
      timeout: opt.timeout || 120000,
      success: function (res) {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data);
        } else {
          const msg = '服务返回 ' + res.statusCode;
          if (!opt.silent) toast(msg);
          reject(new Error(msg));
        }
      },
      fail: function (err) {
        const raw = (err && err.errMsg) || '网络异常';
        let msg;
        if (raw.indexOf('domain') >= 0 || raw.indexOf('not in domain') >= 0) {
          msg = '域名未放行：开发者工具里勾上「不校验合法域名」再编译一次';
        } else if (raw.indexOf('timeout') >= 0) {
          msg = '请求超时：后端的 Agent 还在跑，过一会儿看「历史」里的会话';
        } else {
          // 最常见的一种：后端没起来（8078 没人监听）
          msg = '连不上后端 ' + baseUrl + '：先把后端起起来再登录';
        }
        console.error('[request] ' + path + ' 失败：' + raw);
        if (!opt.silent) {
          wx.showToast({ title: msg, icon: 'none', duration: 4000 });
        }
        reject(new Error(msg));
      }
    });
  });
}

/** 定位：拿不到权限就退回兜底坐标，保证「附近」这类功能有东西可看 */
function currentLocation() {
  const { fallbackLocation } = require('../config.js');
  return new Promise(function (resolve) {
    wx.getLocation({
      type: 'gcj02',
      success: function (res) {
        resolve({ lat: res.latitude, lng: res.longitude, real: true, error: '' });
      },
      fail: function (err) {
        resolve({
          lat: fallbackLocation.lat,
          lng: fallbackLocation.lng,
          real: false,
          name: fallbackLocation.name,
          // 把微信给的原文带出去，页面直接显示，不用猜"为什么定位失败"
          error: (err && err.errMsg) || 'getLocation:fail'
        });
      }
    });
  });
}

module.exports = {
  request: request,
  toast: toast,
  currentLocation: currentLocation,
  getToken: getToken,
  setToken: setToken
};
