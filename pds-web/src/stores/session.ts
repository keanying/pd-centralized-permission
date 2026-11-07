import { defineStore } from 'pinia'
import { get } from '@/api/http'

export interface Me {
  id: string
  username: string
  name: string
  scenicId?: string
  primaryOrgId?: string
  mustChangePassword: boolean
  pdsPermissions: string[]
}

export const useSession = defineStore('session', {
  state: () => ({ me: null as Me | null }),
  getters: {
    permissions: (s) => new Set(s.me?.pdsPermissions ?? []),
  },
  actions: {
    async load() {
      this.me = await get<Me>('/me')
    },
    has(...codes: string[]) {
      return codes.some((c) => this.permissions.has(c))
    },
  },
})
