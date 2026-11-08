/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<object, object, any>
  export default component
}

declare module 'view-ui-plus'

interface PdsRuntimeConfig {
  title?: string
  brandName?: string
  issuer: string
  apiBaseUrl?: string
  clientId: string
}

interface Window {
  __PDS_CONFIG__?: PdsRuntimeConfig
}
