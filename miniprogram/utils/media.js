/** 选图并转成 data URL：后端 images/photos 字段收的就是「JSON 字符串数组」，不依赖上传接口 */

function toDataUrl(filePath) {
  return new Promise(function (resolve) {
    wx.getFileSystemManager().readFile({
      filePath: filePath,
      encoding: 'base64',
      success: function (res) {
        const ext = (filePath.split('.').pop() || 'jpeg').toLowerCase();
        const mime = ext === 'png' ? 'image/png' : (ext === 'gif' ? 'image/gif' : 'image/jpeg');
        resolve('data:' + mime + ';base64,' + res.data);
      },
      fail: function () {
        resolve('');
      }
    });
  });
}

function pickImages(count) {
  return new Promise(function (resolve) {
    wx.chooseMedia({
      count: count || 3,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      sizeType: ['compressed'],
      success: async function (res) {
        const files = (res.tempFiles || []).map(function (f) { return f.tempFilePath; });
        const urls = [];
        for (let i = 0; i < files.length; i++) {
          const u = await toDataUrl(files[i]);
          if (u) urls.push(u);
        }
        resolve(urls);
      },
      fail: function () {
        resolve([]);
      }
    });
  });
}

function preview(urls, index) {
  if (!urls || !urls.length) return;
  wx.previewImage({ urls: urls, current: urls[index || 0] });
}

module.exports = { pickImages: pickImages, preview: preview };
