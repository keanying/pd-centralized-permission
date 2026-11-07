<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { handleCallback, login } from '@/auth/oidc'

const router = useRouter()
const error = ref('')

onMounted(async () => {
  try {
    const returnTo = await handleCallback(window.location.search)
    router.replace(returnTo || '/')
  } catch (e: any) {
    error.value = e.message || '登录失败'
  }
})
</script>

<template>
  <div class="flex h-full items-center justify-center">
    <div v-if="!error" class="pds-card text-center">
      <Spin size="large" />
      <p class="pds-muted mt-3">正在登录…</p>
    </div>
    <div v-else class="pds-card max-w-md text-center">
      <h2 class="m-0 mb-2 text-lg text-ink">无法登录</h2>
      <p class="pds-muted mb-5">{{ error }}</p>
      <Button type="primary" @click="login('/')">重新登录</Button>
    </div>
  </div>
</template>
