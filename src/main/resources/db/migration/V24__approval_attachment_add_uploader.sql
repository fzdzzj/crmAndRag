-- ============================================================
-- V24：approval_attachment 补 uploader_id 列（P0 产品修复）
-- 背景：ApprovalAttachmentEntity 带 @TableField("uploader_id")（L83-86），
--       ApprovalAttachmentServiceImpl 保存时 setUploaderId(BaseUnit.getCurrentId())，
--       删除鉴权 assertCanDelete 亦依赖 UploaderId==currentId —— 业务逻辑事实上依赖该列；
--       但 V1 建表漏了该列（仅 and_id/model_name/file_name/file_path/file_size/file_type/upload_time），
--       导致真库上任何读取 approval_attachment 全字段的查询抛 Unknown column 'uploader_id'，
--       删除任务接口 90004。详见 docs/writechain-regression-diagnosis.md §3。
-- 回填：当前无生产数据（本地/测试库均无该表存量行），uploader_id 直接 NULL 即可，不做回填。
-- 不可逆前向迁移：不提供 DROP 回滚（遵循项目 runbook）。
-- ============================================================
ALTER TABLE approval_attachment
    ADD COLUMN uploader_id bigint NULL COMMENT '上传人ID';

ALTER TABLE approval_attachment
    ADD INDEX idx_attachment_uploader (uploader_id);
