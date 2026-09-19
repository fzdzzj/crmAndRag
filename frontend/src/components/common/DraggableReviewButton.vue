<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
import { CommentOutlined } from '@ant-design/icons-vue';

const props = defineProps<{
  active?: boolean;
}>();

const emit = defineEmits<{
  toggle: [];
}>();

const buttonRef = ref<HTMLButtonElement | null>(null);
const position = ref({ x: 0, y: 0 });
const isDragging = ref(false);
const dragOffset = ref({ x: 0, y: 0 });
const hasDragged = ref(false);

function initPosition() {
  const padding = 24;
  const size = 56;
  position.value = {
    x: window.innerWidth - size - padding,
    y: window.innerHeight - size - padding,
  };
}

function handleMouseDown(e: MouseEvent) {
  if (!buttonRef.value) return;
  isDragging.value = true;
  hasDragged.value = false;
  dragOffset.value = {
    x: e.clientX - position.value.x,
    y: e.clientY - position.value.y,
  };
  buttonRef.value.style.cursor = 'grabbing';
}

function handleTouchStart(e: TouchEvent) {
  if (!buttonRef.value) return;
  isDragging.value = true;
  hasDragged.value = false;
  const touch = e.touches[0];
  dragOffset.value = {
    x: touch.clientX - position.value.x,
    y: touch.clientY - position.value.y,
  };
}

function handleMouseMove(e: MouseEvent) {
  if (!isDragging.value) return;
  hasDragged.value = true;
  updatePosition(e.clientX, e.clientY);
}

function handleTouchMove(e: TouchEvent) {
  if (!isDragging.value) return;
  hasDragged.value = true;
  const touch = e.touches[0];
  updatePosition(touch.clientX, touch.clientY);
}

function updatePosition(clientX: number, clientY: number) {
  const size = 56;
  let x = clientX - dragOffset.value.x;
  let y = clientY - dragOffset.value.y;

  x = Math.max(0, Math.min(window.innerWidth - size, x));
  y = Math.max(0, Math.min(window.innerHeight - size, y));

  position.value = { x, y };
}

function handleMouseUp() {
  if (!buttonRef.value) return;
  isDragging.value = false;
  buttonRef.value.style.cursor = 'grab';
}

function handleClick() {
  if (hasDragged.value) return;
  emit('toggle');
}

onMounted(() => {
  initPosition();
  window.addEventListener('resize', initPosition);
});

onUnmounted(() => {
  window.removeEventListener('resize', initPosition);
});
</script>

<template>
  <button
    ref="buttonRef"
    class="draggable-review-button"
    :class="{ active: props.active }"
    :style="{
      left: `${position.x}px`,
      top: `${position.y}px`,
    }"
    @mousedown="handleMouseDown"
    @mousemove="handleMouseMove"
    @mouseup="handleMouseUp"
    @mouseleave="handleMouseUp"
    @touchstart.prevent="handleTouchStart"
    @touchmove.prevent="handleTouchMove"
    @touchend="handleMouseUp"
    @click="handleClick"
  >
    <CommentOutlined />
    <span class="label">评审</span>
  </button>
</template>

<style scoped>
.draggable-review-button {
  position: fixed;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: #1890ff;
  color: #fff;
  border: none;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  cursor: grab;
  z-index: 999;
  user-select: none;
  touch-action: none;
  transition: background 0.2s, transform 0.2s;
}

.draggable-review-button:hover {
  background: #40a9ff;
}

.draggable-review-button.active {
  background: #52c41a;
}

.draggable-review-button .label {
  font-size: 10px;
  line-height: 1;
}
</style>
