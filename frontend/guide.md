这是一个关于使用 unplugin-vue-router 处理嵌套路由与布局分组的指南。
Vue Router 基于文件的自动化路由指南
在使用 unplugin-vue-router 时，路由的结构完全由文件系统决定。通过合理的目录结构，可以实现复杂的嵌套路由和局部布局（Layout）隔离。
1. 嵌套路由基础 (Nested Routes)
嵌套路由通过「同名文件 + 同名文件夹」的模式实现。
目录结构
text
src/pages/
├── users.vue         # 父路由组件 (包含 <RouterView />)
└── users/            # 子路由文件夹
    ├── index.vue     # 对应 /users
    └── [id].vue      # 对应 /users/:id
请谨慎使用此类代码。

父组件实现
在父组件 users.vue 中，必须包含 <RouterView /> 才能渲染子路由内容：
vue
<!-- src/pages/users.vue -->
<template>
  <div class="user-layout">
    <h2>用户管理系统</h2>
    <router-view /> <!-- 子组件渲染占位符 -->
  </div>
</template>
请谨慎使用此类代码。

2. 路由分组与局部布局 (Route Groups)
如果你希望实现：/login 是独立空白页，而其他所有路径（如 /, /settings）都嵌套在 navLayout 下，且 URL 路径中不出现 layout 字样，可以使用路由分组功能。
目录结构
使用括号 ( ) 包裹文件夹名，该文件夹名不会出现在生成的 URL 中。
text
src/pages/
├── login.vue                # 独立页面，路径: /login
└── (dashboard)/             # 路由分组（不影响 URL 路径）
    ├── (dashboard).vue      # 分组布局文件 (navLayout)
    ├── index.vue            # 路径: /
    └── settings.vue         # 路径: /settings
请谨慎使用此类代码。

实现原理
分组文件夹 (dashboard)/：告诉插件这是一个逻辑分组，不要在路径中加入 /dashboard。
布局文件 (dashboard).vue：在该文件夹下创建一个同名的 .vue 文件。它将自动成为该分组下所有路由的父组件。
布局文件示例
vue
<!-- src/pages/(dashboard)/(dashboard).vue -->
<template>
  <div class="main-layout">
    <nav>
      <router-link to="/">首页</router-link>
      <router-link to="/settings">设置</router-link>
    </nav>
    
    <main>
      <!-- index.vue 或 settings.vue 会渲染在这里 -->
      <router-view />
    </main>
  </div>
</template>
请谨慎使用此类代码。

3. 进阶控制：definePage 宏
在任何页面组件中，你可以使用 definePage 宏来覆盖自动生成的配置（例如修改路由名称或增加路由元信息）：
vue
<script setup>
definePage({
  name: 'custom-login-name',
  meta: {
    requiresAuth: false,
    transition: 'fade'
  }
})
</script>
请谨慎使用此类代码。

4. 常见问题总结
需求	文件结构	备注
普通嵌套	parent.vue + parent/child.vue	URL 为 /parent/child
独立布局	(group)/.vue + (group)/page.vue	URL 为 /page，不含分组名
默认子路由	parent/index.vue	访问 /parent 时渲染
不渲染父组件	仅创建文件夹，不创建同名 .vue	仅用于路径分组，无通用 UI
相关资源
unplugin-vue-router 官方文档
Vue Router 官方文档