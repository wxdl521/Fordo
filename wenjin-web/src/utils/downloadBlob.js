/**
 * 把 Blob 当文件保存到本地（浏览器下载）。
 * @param {Blob} blob
 * @param {string} filename
 */
export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.style.display = 'none'
  document.body.appendChild(link)
  try {
    link.click()
  } finally {
    document.body.removeChild(link)
    // 延后释放，给浏览器启动下载留时间
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
}
