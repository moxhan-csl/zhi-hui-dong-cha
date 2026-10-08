import { onBeforeUnmount, onMounted, ref } from 'vue'

/** 响应式断点：<768 mobile，<1200 tablet（手册 3.6） */
export function useMedia() {
  const width = ref(window.innerWidth)
  const onResize = () => { width.value = window.innerWidth }
  onMounted(() => window.addEventListener('resize', onResize))
  onBeforeUnmount(() => window.removeEventListener('resize', onResize))
  const isMobile = () => width.value < 768
  const isTablet = () => width.value >= 768 && width.value < 1200
  return { width, isMobile, isTablet }
}
