package com.slz.crm.platform.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 动态配置项实体（表 {@code dynamic_config_item}，Flyway V6 建表）。
 *
 * <p>约定（见 V6 脚本头注释）：{@code config_key} 全局唯一（含软删行）；软删（{@code is_deleted}=1）
 * 表示“恢复静态默认”，历史保留可回滚/复活；{@code version} 每次变更自增，作为乐观并发依据。</p>
 *
 * <p>注意：本实体不使用 tangzc auto-table 注解（表结构完全由 Flyway V6 管理，
 * 生产 auto-table.mode=none，避免双轨建表冲突）。</p>
 */
@Data
@TableName("dynamic_config_item")
public class DynamicConfigItemEntity {

    /** 配置项ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键（点分命名空间，全局唯一含软删行） */
    @TableField("config_key")
    private String configKey;

    /** 命名空间：ai.prompt/ai.model/rag.retrieval/rag.intent/business */
    private String namespace;

    /** 值类型：STRING/INTEGER/LONG/BOOLEAN/DOUBLE/STRING_LIST */
    @TableField("value_type")
    private String valueType;

    /** 配置值（按类型序列化的文本；STRING_LIST 为紧凑 JSON 数组） */
    @TableField("config_value")
    private String configValue;

    /** 配置项含义与影响面说明（超管界面展示） */
    private String description;

    /** 是否敏感值（true=管理端读取/审计掩码）；列名是 MySQL 8.0 保留字（8.0.36 实测裸用语法错误），必须反引号包裹 */
    @TableField("`sensitive`")
    private Boolean sensitive;

    /** 当前版本号（每次变更/回滚 +1） */
    private Integer version;

    /** 软删除（true=已删除：读取回退静态默认） */
    @TableField("is_deleted")
    private Boolean isDeleted;

    /** 创建人 user:&lt;id&gt; */
    @TableField("created_by")
    private String createdBy;

    /** 最后修改人 user:&lt;id&gt; */
    @TableField("updated_by")
    private String updatedBy;

    /** 创建时间 */
    @TableField("create_time")
    private LocalDateTime createTime;

    /** 最后更新时间（由应用在变更时显式维护） */
    @TableField("update_time")
    private LocalDateTime updateTime;
}
