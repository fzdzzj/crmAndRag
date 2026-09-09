package com.slz.crm.pojo.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户交接记录查询DTO
 */
@Data
public class UserHandoverQueryDTO {

    /**
     * 按离职用户筛选
     */
    private Long fromUserId;

    /**
     * 按接收用户筛选
     */
    private Long toUserId;

    /**
     * 按交接时间范围筛选（开始时间）
     */
    private LocalDateTime startTime;

    /**
     * 按交接时间范围筛选（结束时间）
     */
    private LocalDateTime endTime;

    /**
     * 页码（默认1）
     */
    private Integer pageNum = 1;

    /**
     * 每页大小（默认10）
     */
    private Integer pageSize = 10;
}
