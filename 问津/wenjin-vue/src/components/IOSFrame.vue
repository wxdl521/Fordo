<template>
  <div :style="{ width: '402px', height: '872px', flex: 'none', boxSizing: 'border-box', background: dark ? '#0a0a0b' : '#e8e6e1', borderRadius: '56px', padding: '13px', boxShadow: dark ? '0 30px 70px rgba(0,0,0,0.5), inset 0 0 0 2px #2a2a2d' : '0 30px 70px rgba(0,0,0,0.22), inset 0 0 0 2px #fbfaf7' }">
    <div :style="{ position: 'relative', width: '100%', height: '100%', borderRadius: '44px', overflow: 'hidden', background: 'var(--bg)', transition: 'background-color 0.35s' }">
      <!-- 状态栏 -->
      <div :style="{ position: 'absolute', top: 0, left: 0, right: 0, height: '52px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '14px 30px 0', zIndex: 35, pointerEvents: 'none' }">
        <span :style="{ fontSize: '14px', fontWeight: 600, color: 'var(--ink)', letterSpacing: '0.5px' }">9:41</span>
        <div :style="{ display: 'flex', alignItems: 'center', gap: '6px' }">
          <svg width="17" height="11" viewBox="0 0 17 11" :fill="'var(--ink)'"><rect x="0" y="7" width="3" height="4" rx="1" /><rect x="4.5" y="5" width="3" height="6" rx="1" /><rect x="9" y="2.5" width="3" height="8.5" rx="1" /><rect x="13.5" y="0" width="3" height="11" rx="1" /></svg>
          <svg width="16" height="11" viewBox="0 0 16 11" fill="none"><path d="M8 2.2C10 2.2 11.8 3 13.1 4.3l1.2-1.3C12.7 1.4 10.5.5 8 .5S3.3 1.4 1.7 3l1.2 1.3C4.2 3 6 2.2 8 2.2z" :fill="'var(--ink)'" /><path d="M8 5.6c1 0 2 .4 2.7 1.1l1.2-1.2C10.8 4.4 9.5 3.9 8 3.9s-2.8.5-3.9 1.6l1.2 1.2C6 6 7 5.6 8 5.6z" :fill="'var(--ink)'" /><circle cx="8" cy="9" r="1.6" :fill="'var(--ink)'" /></svg>
          <div :style="{ display: 'flex', alignItems: 'center', gap: '2px' }">
            <div :style="{ width: '23px', height: '11px', borderRadius: '3px', border: '1px solid var(--ink)', opacity: 0.5, position: 'relative', boxSizing: 'border-box' }">
              <div :style="{ position: 'absolute', inset: '1.5px', width: '70%', background: 'var(--ink)', borderRadius: '1.5px' }"></div>
            </div>
            <div :style="{ width: '1.5px', height: '4px', background: 'var(--ink)', opacity: 0.5, borderRadius: '0 1px 1px 0' }"></div>
          </div>
        </div>
      </div>
      <!-- 灵动岛 -->
      <div :style="{ position: 'absolute', top: '13px', left: '50%', transform: 'translateX(-50%)', width: '112px', height: '32px', background: '#000', borderRadius: '999px', zIndex: 40 }"></div>
      <!-- 屏幕内容 -->
      <div :style="{ position: 'absolute', inset: 0 }">
        <slot></slot>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({ dark: { type: Boolean, default: true } })
</script>
