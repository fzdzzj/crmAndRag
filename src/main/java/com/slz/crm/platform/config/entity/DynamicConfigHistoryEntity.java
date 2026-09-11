package com.slz.crm.platform.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 动态配置版本历史实体（表 {@code dynamic_config_history}，Flyway V6 建表）。
 *
 * <p>每次变更（CREATE/UPDATE/ROLLBACK/DELETE/REVIVE）追加一行；回滚语义：
 * 回滚到版本 N = 取该版本行的 {@code new_value} 作为当前值（回滚自身也 +1 版本并留痕）。
 * 冗余存 {@code config_key} 便于按键查历史，且软删配置项后历史仍可追溯。</p>
 */
@Data
@TableName("dynamic_config_history")
public class DynamicConfigHistoryEntity {

    /** 历史记录ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置项ID（软删后仍保留引用） */
    @TableField("config_id")
    private Long configId;

    /** 配置键（冗余存储） */
    @TableField("config_key")
    private String configKey;

    /** 本次变更后的版本号 */
    private Integer version;

    /** 变更时值类型快照 */
    @TableField("value_type")
    private String valueType;

    /** 操作类型：CREATE/UPDATE/ROLLBACK/DELETE/REVIVE */
    @TableField("operation_type")
    private String operationType;

    /** 变更前值（CREATE/REVIVE 为空） */
    @TableField("old_value")
    private String oldValue;

    /** 变更后值（DELETE 为空） */
    @TableField("new_value")
    private String newValue;

    /** 操作人 user:&lt;id&gt; */
    @TableField("operator_ref")
    private String operatorRef;

    /** 操作备注（回滚时记录目标版本号） */
    private String remark;

    /** 操作时间 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
