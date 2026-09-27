// 极简 Markdown 渲染（自实现，先转义后渲染 → 无 XSS 面）。
// 支持：代码块 / 行内代码 / 粗体 / 链接(仅 http(s)) / 无序与有序列表 / 换行。
// 约束：所有原文先做 HTML 转义，标签均由本模块生成，杜绝注入。

const ESC_MAP = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ESC_MAP[c])
}

/** 拆出 ``` 围栏代码块；返回 [{type:'md'|'code', text, lang?}] */
function splitFenced(src) {
  const segments = []
  const re = /```([\w+-]*)\r?\n?([\s\S]*?)```/g
  let last = 0
  let m
  while ((m = re.exec(src)) !== null) {
    if (m.index > last) {
      segments.push({ type: 'md', text: src.slice(last, m.index) })
    }
    segments.push({ type: 'code', lang: m[1], text: m[2].replace(/\n$/, '') })
    last = re.lastIndex
  }
  if (last < src.length) {
    segments.push({ type: 'md', text: src.slice(last) })
  }
  return segments
}

/** 行内格式：代码/粗体/链接（入参须为已转义文本） */
function inlineFormat(escaped) {
  let s = escaped
  const spans = []
  s = s.replace(/`([^`]+)`/g, (_, code) => {
    const ph = `@@CODE${spans.length}@@`
    spans.push(code) // code 已是转义内容
    return ph
  })
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
    '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
  s = s.replace(/@@CODE(\d+)@@/g, (_, i) => `<code>${spans[Number(i)]}</code>`)
  return s
}

function renderParagraph(escaped) {
  const lines = escaped.split('\n')
  const nonEmpty = lines.filter((l) => l.trim())
  const isList = nonEmpty.every((l) => /^\s*(?:[-*+]|\d+\.)\s+/.test(l.trim()))
  if (isList && lines.length > 1) {
    const items = lines.map((l) => {
      const content = l.trim().replace(/^(?:[-*+]|\d+\.)\s+/, '')
      return `<li>${inlineFormat(content)}</li>`
    })
    return `<ul>${items.join('')}</ul>`
  }
  return `<p>${inlineFormat(lines.join('<br>'))}</p>`
}

/**
 * 把 Markdown 源文本渲染为安全的 HTML（供 v-html 使用）。
 * @param {string} src
 * @returns {string}
 */
export function renderMarkdown(src) {
  if (!src) {
    return ''
  }
  const parts = []
  for (const seg of splitFenced(String(src))) {
    if (seg.type === 'code') {
      const lang = seg.lang ? ` class="lang-${escapeHtml(seg.lang)}"` : ''
      parts.push(`<pre><code${lang}>${escapeHtml(seg.text)}</code></pre>`)
      continue
    }
    const escaped = escapeHtml(seg.text)
    for (const block of escaped.split(/\n{2,}/)) {
      if (!block.trim()) {
        continue
      }
      parts.push(renderParagraph(block))
    }
  }
  return parts.join('')
}
