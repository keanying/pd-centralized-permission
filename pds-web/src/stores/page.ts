import { defineStore } from 'pinia'

/** 顶栏标题：页面可覆盖路由默认标题，并提供副标题（设计稿：工作台"欢迎回来"、人员明细权限的人员信息）。 */
export const usePage = defineStore('page', {
  state: () => ({ title: '', subtitle: '', inline: '' }),
  actions: {
    set(title: string, opts: { subtitle?: string; inline?: string } = {}) {
      this.title = title
      this.subtitle = opts.subtitle ?? ''
      this.inline = opts.inline ?? ''
    },
  },
})
