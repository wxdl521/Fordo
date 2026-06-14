<template>
  <router-link :to="to" class="wj-navlink">
    <slot />
  </router-link>
</template>

<script setup>
defineProps({ to: { type: [String, Object], required: true } })
</script>
