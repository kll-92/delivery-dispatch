const dateFormat = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false
})

export function localDateTime(value?: string) {
  if (!value) return '暂无记录'
  const utc = /(?:Z|[+-]\d{2}:\d{2})$/i.test(value) ? value : value + 'Z'
  const date = new Date(utc)
  return Number.isNaN(date.getTime()) ? value : dateFormat.format(date)
}
