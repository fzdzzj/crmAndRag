## ADDED Requirements

### Requirement: 终态协助的冻结展示

WHEN 协助状态为已协助、已驳回、已拒绝或已取消,
前端 SHALL 展示后端返回的 `recordSnapshot`，且不得以实时详情接口替代该快照。

#### Scenario: 历史数据缺少快照

GIVEN 终态协助返回 `snapshotMissing=true`
WHEN 用户打开协助详情
THEN 系统显示“历史快照缺失，不能展示实时数据”的提示
AND 不显示实时业务详情、上传或删除入口。

### Requirement: 协助过程对话

WHEN 用户查看自己参与的协助详情,
系统 SHALL 展示消息发送者姓名、发送时间、正文和消息类型组成的时间线。

#### Scenario: 待协助发送文本

GIVEN 协助状态为待协助
WHEN 申请人、指定协助人，或联络任务协助中的创建人、指派人、执行人提交非空文本
THEN 系统调用消息发送接口
AND 成功后刷新时间线。

#### Scenario: 任务执行人参与协助沟通

GIVEN 当前用户是联络任务的执行人且关联协助处于待协助状态
WHEN 用户从联络任务详情打开该协助详情
THEN 系统展示该 assistId 下的消息时间线和任务来源附件
AND 用户可以发送消息及上传任务来源附件
AND 系统不得因此展示“已协助”“驳回”或“拒绝”处理控件。

本轮前端采用手工验收，不新增组件测试或 Playwright 用例；后端已有的权限单元测试继续作为接口行为保障。

#### Scenario: 发送者姓名展示

GIVEN 协助消息接口返回人工消息和系统消息
WHEN 用户查看消息时间线
THEN 人工消息显示 `senderName` 和发送时间
AND 系统消息显示“系统”及发送时间。

#### Scenario: 终态只读

GIVEN 协助已经终态
WHEN 用户查看消息
THEN 系统展示历史消息
AND 不展示发送控件。

### Requirement: 附件部分删除反馈

WHEN 用户批量删除协助、活动或联络任务附件,
系统 SHALL 依据后端返回的 `deletedIds`、`denied` 和 `notFoundIds` 更新界面。

#### Scenario: 部分删除成功

GIVEN 后端返回至少一个 deletedId 且包含 denied 或 notFoundIds
WHEN 前端处理响应
THEN 系统仅移除已删除附件
AND 显示未删除附件及其原因。

### Requirement: 协助人输入完整性

WHEN 用户编辑协助申请,
前端 SHALL 仅提交 `assistApplyList`，每项包含协助人、协作目的和协作要求。

#### Scenario: 同一申请选择重复协助人

GIVEN 表单存在两个相同协助人 ID
WHEN 用户提交
THEN 系统阻止提交并定位到重复项。
