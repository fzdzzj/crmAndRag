package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AutoTable
@Table(value = "tage", comment = "标签表")
@TableName("tage")
public class TagsEntity {
    /**
     * 标签
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "标签ID")
    private Long id;

    @Column(comment = "标签名字",notNull = true,type = "varchar(20)")
    private String tageName;

    /**
     * 创建人ID
     */
    @Column(comment = "创建者ID", type = "bigint")
    private Long creatorId;

    /**
     * 创建时间
     */
    @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime createTime;
}
