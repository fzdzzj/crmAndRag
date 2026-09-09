package com.slz.crm.pojo.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 生日信息 DTO
 */
@Data
public class BirthdayInfoDTO {

    /**
     * 是否为本人（true-本人，false-亲属）
     */
    private Boolean isSelf;

    /**
     * 过生日的人姓名
     */
    private String name;

    /**
     * 出生日期
     */
    private LocalDate birthday;

    /**
     * 距离生日还有几天
     */
    private Integer daysUntil;

    /**
     * 即将过生日的日期（今年）
     */
    private LocalDate upcomingBirthday;
}
