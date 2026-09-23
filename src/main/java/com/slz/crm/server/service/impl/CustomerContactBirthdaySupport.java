package com.slz.crm.server.service.impl;

import com.slz.crm.pojo.dto.BirthdayInfoDTO;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.CustomerContactRemarkEntity;
import com.slz.crm.server.mapper.CustomerContactRemarkMapper;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** 客户联系人生日提醒支持类：生日备注筛选、剩余天数计算与提醒消息生成，纯静态、无状态。 */
final class CustomerContactBirthdaySupport {

  private CustomerContactBirthdaySupport() {}

  /**
   * 生成生日提醒消息列表：筛选本人/亲属生日备注、按剩余天数排序并按配置天数过滤后逐人生成消息。
   *
   * @param entity 联系人实体（调用方已确认存在且未删除）
   * @param today 当前日期
   * @param reminderDays 提醒提前天数（配置项）
   * @param remarkMapper 备注 Mapper
   */
  static List<String> buildReminderMessages(
      CustomerContactEntity entity,
      LocalDate today,
      int reminderDays,
      CustomerContactRemarkMapper remarkMapper) {
    // 2. 获取称呼和性别
    String title;
    if (entity.getGender() == null) {
      title = "";
    } else {
      title = entity.getGender() == 1 ? "先生" : "女士";
    }
    String contactName = entity.getName();

    // 3. 查询联系人的所有生日备注（类型 3-本人，类型 4-亲属）
    List<CustomerContactRemarkEntity> remarks = remarkMapper.selectByContactId(entity.getId());

    List<String> result = List.of();
    if (remarks != null && !remarks.isEmpty()) {
      // 4. 筛选出有出生日期的备注并计算剩余天数，5. 按天数排序，最近的在前面
      List<BirthdayInfoDTO> birthdayInfos = collectBirthdayInfos(contactName, remarks, today);
      birthdayInfos.sort((a, b) -> a.getDaysUntil() - b.getDaysUntil());
      result = buildBirthdayMessages(contactName, title, birthdayInfos, reminderDays);
    }
    return result;
  }

  /** 从备注中筛选本人/亲属生日项并计算今年 upcoming 日期与剩余天数 */
  private static List<BirthdayInfoDTO> collectBirthdayInfos(
      String contactName, List<CustomerContactRemarkEntity> remarks, LocalDate today) {
    List<BirthdayInfoDTO> birthdayInfos = new ArrayList<>();
    for (CustomerContactRemarkEntity remark : remarks) {
      if (remark.getRemarkType() == 3 || remark.getRemarkType() == 4) {
        LocalDate birthday = remark.getRemarkDate();
        if (birthday != null) {
          BirthdayInfoDTO info = new BirthdayInfoDTO();
          info.setIsSelf(remark.getRemarkType() == 3);
          info.setName(remark.getRemarkType() == 3 ? contactName : remark.getRemarkName());
          info.setBirthday(birthday);

          // 计算今年的生日日期
          LocalDate upcomingBirthday = calculateUpcomingBirthday(birthday);
          info.setUpcomingBirthday(upcomingBirthday);

          // 计算距离生日还有几天
          long daysUntil = ChronoUnit.DAYS.between(today, upcomingBirthday);
          info.setDaysUntil((int) daysUntil);

          birthdayInfos.add(info);
        }
      }
    }
    return birthdayInfos;
  }

  /** 按配置天数过滤后生成提醒消息列表，每个人对应一条消息 */
  private static List<String> buildBirthdayMessages(
      String contactName, String title, List<BirthdayInfoDTO> birthdayInfos, int reminderDays) {
    List<String> result;
    // 6. 根据配置的天数过滤，只保留指定天数内的生日
    List<BirthdayInfoDTO> filteredInfos =
        birthdayInfos.stream()
            .filter(info -> info.getDaysUntil() >= 0 && info.getDaysUntil() <= reminderDays)
            .toList();

    // 7. 生成提醒消息列表，每个人对应一条消息
    if (filteredInfos.isEmpty()) {
      result = List.of();
    } else {
      List<String> messages = new ArrayList<>();
      for (BirthdayInfoDTO info : filteredInfos) {
        messages.add(buildSingleMessage(contactName, title, info));
      }
      result = messages;
    }
    return result;
  }

  /** 构建单条提醒消息 */
  private static String buildSingleMessage(String contactName, String title, BirthdayInfoDTO item) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("尊敬的%s%s，", contactName, title));

    if (item.getIsSelf()) {
      // 本人生日
      sb.append(String.format("您的生日还有%d天，", item.getDaysUntil()));
      if (item.getDaysUntil() == 0) {
        sb.append("祝您生日快乐！");
      } else {
        sb.append("提前祝您生日快乐！");
      }
    } else {
      // 亲属生日
      sb.append(String.format("您的亲属%s的生日还有%d天，", item.getName(), item.getDaysUntil()));
      if (item.getDaysUntil() == 0) {
        sb.append("记得送上祝福哦！");
      } else {
        sb.append("别忘了准备礼物和祝福！");
      }
    }

    return sb.toString();
  }

  /** 计算今年的生日日期（处理闰年 2 月 29 日） 如果是闰年 2 月 29 日出生，非闰年则按 2 月 28 日处理 */
  private static LocalDate calculateUpcomingBirthday(LocalDate birthday) {
    int currentYear = Year.now().getValue();
    LocalDate upcomingBirthday = calculateBirthdayForYear(birthday, currentYear);

    // 如果今年的生日已经过了，计算明年的生日
    if (upcomingBirthday.isBefore(LocalDate.now())) {
      upcomingBirthday = calculateBirthdayForYear(birthday, currentYear + 1);
    }

    return upcomingBirthday;
  }

  /**
   * 计算指定年份的生日日期（处理闰年 2 月 29 日）
   *
   * @param birthday 原始出生日期
   * @param year 目标年份
   * @return 该年份的生日日期
   */
  private static LocalDate calculateBirthdayForYear(LocalDate birthday, int year) {
    boolean isLeapYearBirthday = birthday.getMonthValue() == 2 && birthday.getDayOfMonth() == 29;
    boolean isTargetYearLeapYear = Year.of(year).isLeap();

    LocalDate result;
    if (isLeapYearBirthday && !isTargetYearLeapYear) {
      // 闰年出生但在非闰年，返回 2 月 28 日
      result = LocalDate.of(year, 2, 28);
    } else {
      // 其他情况直接设置年份
      result = birthday.withYear(year);
    }
    return result;
  }
}
