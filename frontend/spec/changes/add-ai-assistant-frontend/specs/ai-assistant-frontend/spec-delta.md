# 规范差异：ai-assistant-frontend

本文件定义前端 AI 助手能力的行为需求。

## ADDED Requirements

### Requirement: 全局 AI 助手入口
WHEN 已登录用户进入 dashboard 布局,
系统 SHALL 显示可打开 AI 助手抽屉的全局入口, 并在桌面与移动端提供可用触控目标。

#### Scenario: 打开聊天面板
GIVEN 用户已登录并停留在任意业务页面
WHEN 用户点击 AI 助手入口
THEN 系统打开右侧 AI 助手抽屉
AND 聊天面板包含会话入口、消息区、输入区和发送按钮。

#### Scenario: 移动端适配
GIVEN 视口宽度小于 md 断点
WHEN 用户打开 AI 助手
THEN 抽屉占据全部宽度
AND 主要交互控件保持不低于 40px 的触控目标。

### Requirement: 流式对话渲染
WHEN 用户发送非空消息,
系统 SHALL 立即展示用户消息, 按后端 SSE 事件增量渲染助手文本、工具状态和图表, 并在流式期间阻止重复发送。

#### Scenario: 消费文本与图表事件
GIVEN 后端依次发送 meta、tool、text、chart 和 done 事件
WHEN 前端接收跨 chunk 的 SSE 数据
THEN 系统正确缓冲并解析事件
AND 工具结束后移除占位, 文本和图表按顺序展示。

#### Scenario: 处理未支持事件
GIVEN 前端收到未声明的 SSE 事件
WHEN 事件无法映射为当前契约中的消息
THEN 系统忽略该事件
AND 不中断当前流、不渲染空白消息、不向用户暴露实现细节。

### Requirement: 会话历史与归档
WHEN 用户新建、切换或归档会话,
系统 SHALL 使用真实会话 ID 加载对应历史消息, 并避免上一会话数据进入下一会话。

#### Scenario: 切换历史会话
GIVEN 会话列表中存在历史会话
WHEN 用户选择某个会话
THEN 系统按会话 ID 拉取历史消息
AND 当前流式状态被清理。

#### Scenario: 归档当前会话
GIVEN 用户在当前会话中确认归档
WHEN 归档接口成功
THEN 系统刷新会话列表
AND 聊天面板回到新对话状态。

### Requirement: 停止与错误恢复
WHEN 用户在流式输出期间点击停止或组件即将卸载,
系统 SHALL 中断客户端流式请求, 通知服务端取消, 保留已渲染内容, 并恢复输入状态。

#### Scenario: 用户主动停止
GIVEN 助手文本正在流式输出
WHEN 用户点击停止
THEN 当前文本立即标记为 interrupted
AND 系统调用会话取消接口并允许后续发送。

#### Scenario: 流式或网络错误
GIVEN SSE 返回错误事件或请求失败
WHEN 前端处理该异常
THEN 系统展示稳定的业务错误文案
AND 清理 streaming 状态, 不让发送按钮永久禁用。

### Requirement: 待确认操作卡片
WHEN 后端下发有效的 actionCard 事件或加载到 actionCard 历史消息,
系统 SHALL 展示操作摘要、参数表、确认按钮、取消按钮和当前终态。

#### Scenario: 确认成功
GIVEN 卡片处于 PENDING 状态
WHEN 用户点击确认且接口返回成功
THEN 系统使用接口返回的最终状态渲染卡片
AND 禁用确认、取消和候选编辑。

#### Scenario: 编辑候选实体
GIVEN 卡片带有候选列表且未进入终态
WHEN 用户选择一个候选
THEN 系统在既有 payload 上合并目标字段
AND 提交完整 payload 给编辑接口。

#### Scenario: 终态历史卡片
GIVEN 历史卡片在刷新后返回 CONFIRMED、CANCELLED、EXPIRED 或 FAILED
WHEN 用户查看该卡片
THEN 系统展示对应状态
AND 不再提交变更。

### Requirement: 追问进度
WHEN 后端下发与 pendingId 关联的 draftProgress 事件,
系统 SHALL 更新已收集字段、缺失字段、追问文案和轮数, 且不重复创建同 pendingId 的进度卡片。

#### Scenario: 追问进度更新
GIVEN 同一草稿的 draftProgress 已存在
WHEN 后端发送新的字段进度
THEN 系统按 pendingId 原位更新卡片
AND 不新增重复消息。

#### Scenario: 进度契约未启用
GIVEN 后端尚未实现 draftProgress 事件
WHEN 前端加载对话
THEN 系统不渲染前端伪造的追问卡片
AND 仅展示后端真实持久化的内容。

### Requirement: 实体引用跳转
WHEN 后端通过 references 事件或确认结果返回实体引用,
系统 SHALL 仅渲染可解析到有效路由的引用标签, 并在点击后跳转对应详情或列表页。

#### Scenario: 合同引用跳转
GIVEN 引用类型为 contract 且包含有效 ID
WHEN 用户点击引用标签
THEN 系统跳转到合同详情页。

#### Scenario: 引用不可跳转
GIVEN 引用类型未知或缺少 ID
WHEN 前端构建引用列表
THEN 系统过滤该引用
AND 不渲染空路由或无效链接。

### Requirement: 前端质量门禁
WHEN AI 助手前端发生变更,
系统 SHALL 通过 API 生成校验、单元测试、ESLint、类型检查和生产构建后才能合并。

#### Scenario: 合并前验证
GIVEN 功能分支准备发起 PR
WHEN 执行 pnpm gen:api、pnpm test、pnpm precommit:check 和 pnpm build
THEN 所有命令成功
AND 生成目录 `src/api/axios` 不进入提交。

#### Scenario: 组件 API 调用约束
GIVEN AI 助手组件需要访问后端接口
WHEN 开发者实现交互逻辑
THEN 调用必须封装在 `src/hooks` 的 composable 中
AND 组件不得直接动态导入或调用生成客户端。
