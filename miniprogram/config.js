/**
 * 小程序端全局配置。
 *
 * - 开发者工具里调试：保持 http://127.0.0.1:8078/chat/ ，
 *   并在「详情 → 本地设置」勾上「不校验合法域名」（project.config.json 里已默认关闭校验）。
 * - 真机预览 / 上线：必须换成 https 且域名已完成 ICP 备案，
 *   再到 mp.weixin.qq.com →「开发管理 → 开发设置 → 服务器域名」把它加进 request 合法域名。
 *   小程序不允许 http（除开发者工具），也不允许 IP 直连。
 */
module.exports = {
  // 后端上下文路径：server.port=8078 + server.servlet.context-path=/chat
  baseUrl: 'http://127.0.0.1:8078/chat/',

  // 定位失败时的兜底坐标（广州塔），方便没有定位权限时也能跑通「附近吃喝玩乐」
  fallbackLocation: { lat: 23.10647, lng: 113.32455, name: '广州塔（兜底坐标）' }
};
