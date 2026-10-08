<template>
  <div class="stat-card zd-card">
    <div class="stat-card__head">
      <span class="stat-card__label">{{ label }}</span>
      <el-icon v-if="icon" class="stat-card__icon"><component :is="icon" /></el-icon>
    </div>
    <div class="stat-card__value">{{ value }}<small v-if="unit"> {{ unit }}</small></div>
    <div v-if="hint" class="stat-card__hint muted">{{ hint }}</div>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ label: string; value: string | number; unit?: string; hint?: string; icon?: string }>(), {
  unit: '',
  hint: '',
  icon: '',
})
</script>

<style scoped>
.stat-card__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.stat-card__label {
  font-size: var(--font-aux);
  color: #7a857f;
}
.stat-card__icon {
  color: var(--brand);
}
.stat-card__value {
  font-size: 26px;
  font-weight: 700;
  margin-top: 6px;
  line-height: 1.2;
}
.stat-card__value small {
  font-size: var(--font-aux);
  font-weight: 400;
  color: #7a857f;
}
</style>
