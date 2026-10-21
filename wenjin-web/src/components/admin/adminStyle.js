/**
 * 管理后台各页共用的内联样式常量（亮 / 暗主题都走 CSS 变量）。
 * 导入本模块会顺带加载 admin.css（hover / focus / disabled 伪类）。
 */
import './admin.css'

export const pageStyle = {
  padding: '24px clamp(16px, 3vw, 32px) 48px',
  maxWidth: '1280px',
  boxSizing: 'border-box'
}

export const headingRowStyle = {
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: '16px',
  flexWrap: 'wrap',
  marginBottom: '18px'
}

export const h1Style = {
  margin: '0 0 6px',
  fontFamily: "'Noto Serif SC', serif",
  fontSize: '22px',
  fontWeight: 600,
  letterSpacing: '1px'
}

export const subStyle = {
  fontSize: '12.5px',
  color: 'var(--mut)'
}

export const headingActionsStyle = {
  display: 'flex',
  gap: '10px',
  flexWrap: 'wrap'
}

export const filterBarStyle = {
  display: 'flex',
  alignItems: 'center',
  gap: '10px',
  flexWrap: 'wrap',
  marginBottom: '14px'
}

export const fieldStyle = {
  height: '34px',
  boxSizing: 'border-box',
  padding: '0 10px',
  background: 'var(--card)',
  color: 'var(--ink)',
  border: '1px solid var(--line)',
  borderRadius: '8px',
  fontSize: '13px',
  outline: 'none',
  width: '100%',
  transition: 'border-color 0.18s, background-color 0.35s'
}

export const primaryBtnStyle = {
  height: '34px',
  padding: '0 18px',
  background: 'var(--acc)',
  border: 'none',
  borderRadius: '8px',
  color: '#FFFDF8',
  fontSize: '13px',
  fontWeight: 500,
  cursor: 'pointer',
  whiteSpace: 'nowrap'
}

export const ghostBtnStyle = {
  height: '34px',
  padding: '0 16px',
  background: 'transparent',
  border: '1px solid var(--line)',
  borderRadius: '8px',
  color: 'var(--ink)',
  fontSize: '13px',
  cursor: 'pointer',
  whiteSpace: 'nowrap'
}

export const noticeStyle = {
  fontSize: '13px',
  color: 'var(--ok)',
  background: 'var(--okSoft)',
  border: '1px solid var(--ok)',
  borderRadius: '8px',
  padding: '8px 12px',
  margin: '0 0 12px'
}

export const errorStyle = {
  fontSize: '13px',
  color: 'var(--acc)',
  background: 'var(--accSoft)',
  border: '1px solid var(--acc)',
  borderRadius: '8px',
  padding: '8px 12px',
  margin: '0 0 12px'
}

export const tableCardStyle = {
  background: 'var(--card)',
  border: '1px solid var(--line)',
  borderRadius: '12px',
  overflow: 'hidden',
  transition: 'background-color 0.35s, border-color 0.35s'
}

export const tableScrollStyle = {
  overflowX: 'auto'
}

export const tableStyle = {
  width: '100%',
  borderCollapse: 'collapse',
  fontSize: '13px'
}

/** 表头单元格；col.width 可选。 */
export function thStyle(col = {}) {
  return {
    textAlign: 'left',
    padding: '10px 14px',
    fontSize: '12px',
    fontWeight: 500,
    color: 'var(--mut)',
    background: 'var(--card2)',
    borderBottom: '1px solid var(--line)',
    whiteSpace: 'nowrap',
    width: col.width || 'auto'
  }
}

export const tdStyle = {
  padding: '10px 14px',
  borderBottom: '1px solid var(--line)',
  verticalAlign: 'middle',
  color: 'var(--ink)'
}

export const hintStyle = {
  textAlign: 'center',
  padding: '36px 16px',
  fontSize: '13px',
  color: 'var(--mut)'
}

export const paginationStyle = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'flex-end',
  gap: '12px',
  flexWrap: 'wrap',
  marginTop: '14px'
}

export const pageInfoStyle = {
  fontSize: '12.5px',
  color: 'var(--mut)'
}

export function pageBtnStyle(disabled) {
  return {
    ...ghostBtnStyle,
    height: '30px',
    padding: '0 14px',
    fontSize: '12.5px',
    opacity: disabled ? 0.5 : 1,
    cursor: disabled ? 'not-allowed' : 'pointer'
  }
}

/** 胶囊标签基础样式。 */
export function tagBase() {
  return {
    display: 'inline-block',
    fontSize: '11.5px',
    borderRadius: '999px',
    padding: '2px 10px',
    whiteSpace: 'nowrap'
  }
}

/**
 * 胶囊标签配色。
 * @param {'ok'|'acc'|'warn'|'mut'} tone
 */
export function tagStyle(tone) {
  if (tone === 'ok') return { ...tagBase(), color: 'var(--ok)', background: 'var(--okSoft)' }
  if (tone === 'acc') return { ...tagBase(), color: 'var(--acc)', background: 'var(--accSoft)' }
  if (tone === 'warn') return { ...tagBase(), color: 'var(--warn)', background: 'var(--warnSoft)' }
  return { ...tagBase(), color: 'var(--mut)', background: 'var(--card2)', border: '1px solid var(--line)' }
}
