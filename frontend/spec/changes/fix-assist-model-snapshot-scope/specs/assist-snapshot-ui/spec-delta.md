# 协助模型化快照前端规范差异

## ADDED Requirements

### Requirement: 按来源模型展示协助快照

WHEN 用户打开协助终态详情，前端 SHALL 根据 `modelName` 展示后端返回的 `recordSnapshot`，不得把三类快照统一当作商机全部活动。

#### Scenario: 审批推进协助

GIVEN `modelName=SALES_STAGE_APPROVAL`
WHEN 用户打开终态详情
THEN 页面展示整条商机、公司、主要联系人、全部业务活动、活动附件和审批附件

#### Scenario: 业务活动协助

GIVEN `modelName=BUSINESS_ACTIVITY`
WHEN 用户打开终态详情
THEN 页面只展示被协助活动及其附件
AND 展示关联商机、公司和主要联系人摘要
AND 不展示同商机其他无关活动

#### Scenario: 联络任务协助

GIVEN `modelName=CONTACT_TASK`
WHEN 用户打开终态详情
THEN 页面展示被协助任务及明确关联活动
AND 不展示同商机下无关活动

### Requirement: 协助字段按当前记录匹配

WHEN 列表或详情返回 `hasAssistant` 或等价字段，前端 SHALL 以 `modelName + recordId` 作为记录键渲染协助入口。

#### Scenario: 同商机其他记录有协助

GIVEN 当前活动没有协助，但同商机另一活动存在协助
WHEN 用户查看当前活动
THEN 当前活动不显示有协助状态或协助入口

### Requirement: 交付物终态可读

WHEN 用户是协助申请人或被指派协助人，前端 SHALL 调用交付物实时查询接口并展示返回附件；协助终态不应因 `assistStatus != 0` 隐藏查看/下载入口。

#### Scenario: 终态查看交付物

GIVEN 协助已完成、驳回、拒绝或取消
AND 当前用户是申请人或协助人
WHEN 用户打开协助详情
THEN 页面仍显示交付物列表和下载按钮
AND 不依赖 `recordSnapshot.deliveryAttachments`

#### Scenario: 终态写操作

GIVEN 协助已进入终态
WHEN 后端返回不可写
THEN 页面隐藏上传/删除按钮
AND 不影响已有交付物查看和下载

### Requirement: OpenAPI 生成结果为前端契约来源

WHEN 前端执行 API 同步和生成命令，系统 SHALL 从后端 `/v3/api-docs.yaml` 生成 `openapi.yaml`、TypeScript 类型和 SDK。

#### Scenario: 契约发生漂移

GIVEN 后端接口变化但前端未重新生成
WHEN CI 执行 `verify:api`
THEN CI 检测到生成文件差异并失败
AND 提示先同步 OpenAPI 再提交前端代码

## MODIFIED Requirements

### Requirement: 终态快照只读

WHEN 协助进入终态，前端 SHALL 只展示 `recordSnapshot` 的业务历史内容；快照缺失时 SHALL 显示明确提示，不得请求实时业务接口拼接替代历史内容。

