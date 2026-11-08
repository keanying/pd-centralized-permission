<template>
  <!-- 设计稿工作台主视觉：等距视角的绿色平台 + 盾牌锁 + 树木 -->
  <svg viewBox="0 0 300 210" class="h-full w-full" aria-hidden="true">
    <!-- 平台侧面 -->
    <polygon points="20,112 150,170 150,206 20,148" fill="#3E5A24" />
    <polygon points="150,170 280,112 280,148 150,206" fill="#56792F" />
    <!-- 平台顶面 -->
    <polygon points="150,54 280,112 150,170 20,112" fill="#A3D65C" />
    <polygon points="150,70 248,112 150,154 52,112" fill="#B6E07A" />
    <!-- 小路 -->
    <polygon points="150,118 196,138 182,144 136,124" fill="#E9F5D6" />
    <!-- 树木 -->
    <g>
      <ellipse cx="62" cy="122" rx="15" ry="6" fill="#3E5A24" opacity=".35" />
      <circle cx="62" cy="110" r="14" fill="#5E8F2E" /><circle cx="58" cy="106" r="6" fill="#7DB244" />
      <ellipse cx="96" cy="140" rx="11" ry="4.5" fill="#3E5A24" opacity=".35" />
      <circle cx="96" cy="131" r="10" fill="#5E8F2E" /><circle cx="93" cy="128" r="4" fill="#7DB244" />
      <ellipse cx="236" cy="118" rx="14" ry="5.5" fill="#3E5A24" opacity=".35" />
      <circle cx="236" cy="106" r="13" fill="#5E8F2E" /><circle cx="232" cy="102" r="5" fill="#7DB244" />
      <ellipse cx="202" cy="146" rx="10" ry="4" fill="#3E5A24" opacity=".35" />
      <circle cx="202" cy="138" r="9" fill="#5E8F2E" /><circle cx="199" cy="135" r="3.5" fill="#7DB244" />
    </g>
    <!-- 盾牌与锁 -->
    <ellipse cx="150" cy="114" rx="34" ry="10" fill="#3E5A24" opacity=".3" />
    <path d="M150 18 L186 30 V64 C186 88 170 104 150 112 C130 104 114 88 114 64 V30 Z" fill="#A3D65C" stroke="#fff" stroke-width="4" stroke-linejoin="round" />
    <path d="M150 18 L186 30 V64 C186 88 170 104 150 112 Z" fill="#8FC14A" opacity=".55" />
    <rect x="137" y="56" width="26" height="22" rx="4" fill="#1F2421" />
    <path d="M142 56 V49 a8 8 0 0 1 16 0 V56" fill="none" stroke="#1F2421" stroke-width="4.5" />
    <circle cx="150" cy="66" r="3" fill="#A3D65C" />
  </svg>
</template>
