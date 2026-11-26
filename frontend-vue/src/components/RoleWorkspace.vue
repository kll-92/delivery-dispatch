<script setup lang="ts">
import { session } from '../router'
defineProps<{ roleLabel: string }>()
defineEmits<{ logout: [] }>()
</script>

<template>
  <div class="workspace">
    <header class="mast"><div class="mast-inner">
      <span class="mark" aria-hidden="true">◈</span><strong>同城配送</strong>
      <span class="role">{{ roleLabel }}</span>
      <span class="user">{{ session.username }}</span><button @click="$emit('logout')">退出</button>
    </div></header>
    <main class="content"><slot /></main>
  </div>
</template>

<style scoped>
.workspace { min-height:100vh; background:#f6f8fc; color:#202b42; --el-color-primary:#425fee; }
.mast { background:#fff; border-bottom:1px solid #dfe5ef; }
.mast-inner,.content { max-width:1152px; margin:auto; padding:0 32px; }
.mast-inner { min-height:66px; display:flex; align-items:center; gap:12px; }
.mark { color:#4564ef; background:#edf1ff; border-radius:9px; padding:2px 8px; font-size:25px; }
.mast strong { font-size:17px; }
.role { color:#596784; border-left:1px solid #dbe1ec; padding-left:14px; font-size:13px; }
.user { margin-left:auto; color:#5e6a7c; font-size:13px; }
.mast button { background:transparent; border:0; color:#435578; cursor:pointer; padding:8px; }
.content { padding-top:32px; padding-bottom:40px; }
@media(max-width:650px) {
  .mast-inner,.content { padding-left:16px; padding-right:16px; }
  .mast-inner { min-height:56px; gap:7px; }
  .content { padding-top:16px; }
  .role { padding-left:8px; }
}
</style>
