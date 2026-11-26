<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'

const props = defineProps<{ paused: boolean }>()
const canvas = ref<HTMLCanvasElement>()
const frameWidth = 216
const frameHeight = 190
const origin = { x: 166, y: 142 }

const sparks = Array.from({ length: 27 }, (_, index) => {
  const fraction = ((index * 17) % 29) / 29
  return {
    lifetime: 1850 + ((index * 173) % 1420),
    offset: index * 127,
    angle: (1.05 + fraction * 2.48) * Math.PI,
    distance: 46 + ((index * 37) % 119),
    curve: ((index * 23) % 47) - 23,
    radius: 0.78 + ((index * 13) % 12) / 8,
    glint: index % 6 === 0,
    tint: index % 3 === 0 ? '255,227,151' : '187,108,18'
  }
})

type Spark = (typeof sparks)[number]

const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
let context: CanvasRenderingContext2D | null = null
let frame = 0
let startedAt = 0

function position(spark: Spark, progress: number, drift: number) {
  const distance = 22 + spark.distance * progress
  const sweep = Math.sin(progress * Math.PI) * spark.curve
  return {
    x: origin.x + Math.cos(spark.angle) * distance + sweep,
    y: origin.y - 3 + drift + Math.sin(spark.angle) * distance - 36 * progress
  }
}

function draw(now: number) {
  const brush = context
  if (!brush) return
  if (!startedAt) startedAt = now
  const elapsed = now - startedAt
  const drift = Math.sin(elapsed / 570) * 2.5
  brush.clearRect(0, 0, frameWidth, frameHeight)

  for (const spark of sparks) {
    const progress =
      ((elapsed + spark.offset) % spark.lifetime) / spark.lifetime
    const alpha = Math.min(progress * 8, 1) * (1 - progress) ** 1.5
    const head = position(spark, progress, drift)
    const tail = position(spark, Math.max(0, progress - 0.08), drift)
    const trail = brush.createLinearGradient(tail.x, tail.y, head.x, head.y)
    trail.addColorStop(0, `rgba(${spark.tint},0)`)
    trail.addColorStop(1, `rgba(${spark.tint},0.82)`)

    brush.globalAlpha = alpha
    brush.strokeStyle = trail
    brush.lineWidth = spark.radius * 0.9
    brush.beginPath()
    brush.moveTo(tail.x, tail.y)
    brush.lineTo(head.x, head.y)
    brush.stroke()

    brush.fillStyle = '#a75b19'
    brush.beginPath()
    brush.arc(head.x, head.y, spark.radius * 1.03, 0, Math.PI * 2)
    brush.fill()

    brush.fillStyle = '#fff5bb'
    if (spark.glint) {
      const ray = spark.radius * (2.1 + 0.7 * Math.sin(progress * Math.PI * 3))
      brush.beginPath()
      brush.moveTo(head.x, head.y - ray)
      brush.lineTo(head.x + ray * 0.19, head.y - ray * 0.19)
      brush.lineTo(head.x + ray * 0.65, head.y)
      brush.lineTo(head.x + ray * 0.19, head.y + ray * 0.19)
      brush.lineTo(head.x, head.y + ray)
      brush.lineTo(head.x - ray * 0.19, head.y + ray * 0.19)
      brush.lineTo(head.x - ray * 0.65, head.y)
      brush.lineTo(head.x - ray * 0.19, head.y - ray * 0.19)
      brush.closePath()
      brush.fill()
    } else {
      brush.beginPath()
      brush.arc(head.x, head.y, spark.radius * 0.62, 0, Math.PI * 2)
      brush.fill()
    }
  }
  brush.globalAlpha = 1
  frame = requestAnimationFrame(draw)
}

function syncPlayback() {
  cancelAnimationFrame(frame)
  const layer = canvas.value
  if (
    !layer ||
    typeof CanvasRenderingContext2D === 'undefined' ||
    props.paused ||
    reducedMotion.matches ||
    document.hidden
  )
    return
  const scale = Math.min(window.devicePixelRatio || 1, 2)
  layer.width = Math.round(frameWidth * scale)
  layer.height = Math.round(frameHeight * scale)
  context = layer.getContext('2d')
  context?.setTransform(scale, 0, 0, scale, 0, 0)
  startedAt = 0
  if (context) frame = requestAnimationFrame(draw)
}

onMounted(() => {
  syncPlayback()
  document.addEventListener('visibilitychange', syncPlayback)
  reducedMotion.addEventListener('change', syncPlayback)
})
onUnmounted(() => {
  cancelAnimationFrame(frame)
  document.removeEventListener('visibilitychange', syncPlayback)
  reducedMotion.removeEventListener('change', syncPlayback)
})
watch(() => props.paused, syncPlayback)
</script>

<template>
  <span
    class="firelight"
    :class="{ 'firelight--paused': paused }"
    aria-hidden="true"
  >
    <canvas ref="canvas" class="firelight__sparks" />
    <span class="firelight__orb">
      <span class="firelight__halo" />
      <span class="firelight__glass">
        <span class="firelight__reflection" />
        <span class="firelight__star" />
      </span>
    </span>
  </span>
</template>

<style scoped>
.firelight,
.firelight__orb,
.firelight__halo,
.firelight__glass {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  pointer-events: none;
}

.firelight__sparks {
  position: absolute;
  z-index: 1;
  inline-size: 216px;
  block-size: 190px;
  inset-inline-end: -20px;
  inset-block-end: -18px;
  max-inline-size: none;
}

.firelight__orb {
  animation: firelight-hover 3.6s ease-in-out infinite;
}

.firelight__halo {
  inset: -15px;
  background: radial-gradient(
    circle,
    #f2a9399c,
    #e6a2444a 48%,
    transparent 71%
  );
  animation: firelight-glow 3.6s ease-in-out infinite;
}

.firelight__glass {
  overflow: hidden;
  border: 1px solid #d2a16b;
  background:
    radial-gradient(
      ellipse at 46% 44%,
      #c77e42 2%,
      #794175 48%,
      transparent 76%
    ),
    linear-gradient(150deg, #764ea9 3%, #3c235f 56%, #2f1c4d);
  box-shadow:
    0 8px 22px #4a274d66,
    0 2px 4px #73441646,
    inset 0 -4px 11px #261636a0,
    inset 0 2px 5px #fff0bfc4;
}

.firelight__reflection {
  position: absolute;
  inset: -23%;
  border-radius: 50%;
  background:
    radial-gradient(ellipse at 46% 48%, #ffd371a0, transparent 45%),
    conic-gradient(
      from 56deg,
      transparent 62%,
      #ffcb7196 73%,
      transparent 82%,
      #f6c1d163 91%,
      transparent
    );
  mix-blend-mode: screen;
  animation: firelight-reflect 7s linear infinite;
}

.firelight__star {
  position: absolute;
  inset: 24%;
  background: #fff8e2;
  clip-path: polygon(
    50% 0,
    61% 38%,
    90% 50%,
    61% 61%,
    50% 100%,
    39% 61%,
    10% 50%,
    39% 38%
  );
  animation: firelight-star 3.6s ease-in-out infinite;
}

.firelight--paused .firelight__sparks {
  display: none;
}

.firelight--paused .firelight__orb,
.firelight--paused .firelight__halo,
.firelight--paused .firelight__star,
.firelight--paused .firelight__reflection {
  animation: none;
}

@keyframes firelight-hover {
  0%,
  100% {
    transform: translateY(1px);
  }
  45% {
    transform: translateY(-6px);
  }
}

@keyframes firelight-glow {
  0%,
  100% {
    opacity: 0.55;
    transform: scale(0.96);
  }
  46% {
    opacity: 0.98;
    transform: scale(1.15);
  }
}

@keyframes firelight-star {
  0%,
  100% {
    transform: rotate(-9deg) scale(0.88);
  }
  45% {
    transform: rotate(7deg) scale(1.11);
  }
}

@keyframes firelight-reflect {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .firelight__sparks {
    display: none;
  }
  .firelight__orb,
  .firelight__halo,
  .firelight__star,
  .firelight__reflection {
    animation: none;
  }
}

@media (forced-colors: active) {
  .firelight__sparks,
  .firelight__halo,
  .firelight__reflection {
    display: none;
  }
  .firelight__glass {
    border-color: ButtonText;
  }
  .firelight__star {
    forced-color-adjust: none;
    background: ButtonText;
  }
}
</style>
