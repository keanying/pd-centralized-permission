<script setup lang="ts">
import { computed } from 'vue'
import {
  IconFolder, IconServer, IconAppCenter, IconMenu, IconArticle, IconHandle, IconCode, IconGridSquare, IconFont, IconBolt,
  IconWrench, IconLayers, IconKey, IconBox,
} from '@kousum/semi-icons-vue'

/** 权限节点类型图标：图标与颜色来自节点类型字典（icon / color），可在"节点类型管理"中修改。 */
const props = withDefaults(defineProps<{ icon?: string; color?: string; size?: 'extra-small' | 'small' | 'default' }>(), { size: 'small' })
const ICONS: Record<string, any> = {
  folder: IconFolder, database: IconServer, module: IconAppCenter, menu: IconMenu, page: IconArticle, button: IconHandle,
  api: IconCode, table: IconGridSquare, field: IconFont, ai: IconBolt, tool: IconWrench, model: IconLayers, key: IconKey,
}
const comp = computed(() => ICONS[props.icon || ''] || IconBox)
</script>

<template>
  <component :is="comp" :size="size" :style="{ color: color || '#6B7280' }" />
</template>
