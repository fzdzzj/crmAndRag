package com.slz.crm.common.untils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单号生成工具类 用于生成回款单号、发票编号等业务单号
 *
 * <p>单副本假设：序号（AtomicInteger）与日期缓存（静态字段）均为进程内状态，单号唯一性依赖单副本部署契约 ——多副本部署各副本独立递增，必然产生重复业务单号，严禁水平扩容（见
 * deploy/README.md 组件清单）。
 */
public class NumberGenerator {

  /** 回款单号前缀 */
  private static final String PAYMENT_PREFIX = "PAY";

  /** 发票编号前缀 */
  private static final String INVOICE_PREFIX = "INV";

  /** 日期格式化器 */
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

  /** 回款单号序号（线程安全） */
  private static final AtomicInteger PAYMENT_SEQUENCE = new AtomicInteger(1);

  /** 发票编号序号（线程安全） */
  private static final AtomicInteger INVOICE_SEQUENCE = new AtomicInteger(1);

  /** 回款单号缓存日期 */
  private static String paymentCachedDate = "";

  /** 发票编号缓存日期 */
  private static String invoiceCachedDate = "";

  /**
   * 生成回款单号 格式：PAY + yyyyMMdd + 4位序号 + "-" + 时间戳哈希值前4位 示例：PAY202501110001-A3F2
   *
   * @return 回款单号
   */
  public static synchronized String generatePaymentNo() {
    String currentDate = LocalDateTime.now().format(DATE_FORMATTER);

    // 如果日期变化，重置序号
    if (!currentDate.equals(paymentCachedDate)) {
      paymentCachedDate = currentDate;
      PAYMENT_SEQUENCE.set(1);
    }

    // 获取当前序号
    int sequence = PAYMENT_SEQUENCE.getAndIncrement();

    // 格式化为4位序号
    String sequenceStr = String.format("%04d", sequence);

    // 生成时间戳哈希值的前4位
    String timestampHash = getTimestampHash();

    return PAYMENT_PREFIX + currentDate + sequenceStr + "-" + timestampHash;
  }

  /**
   * 生成发票编号 格式：INV + yyyyMMdd + 4位序号 + "-" + 时间戳哈希值前4位 示例：INV202501110001-B4E8
   *
   * @return 发票编号
   */
  public static synchronized String generateInvoiceNo() {
    String currentDate = LocalDateTime.now().format(DATE_FORMATTER);

    // 如果日期变化，重置序号
    if (!currentDate.equals(invoiceCachedDate)) {
      invoiceCachedDate = currentDate;
      INVOICE_SEQUENCE.set(1);
    }

    // 获取当前序号
    int sequence = INVOICE_SEQUENCE.getAndIncrement();

    // 格式化为4位序号
    String sequenceStr = String.format("%04d", sequence);

    // 生成时间戳哈希值的前4位
    String timestampHash = getTimestampHash();

    return INVOICE_PREFIX + currentDate + sequenceStr + "-" + timestampHash;
  }

  /**
   * 获取当前时间戳哈希值的前4位 使用系统时间的毫秒值计算哈希，确保唯一性
   *
   * <p>单副本假设补充：哈希段不承担跨副本去重职责，唯一性由"前缀 + 日期 + 4 位序号"的单副本递增保证。
   *
   * @return 16进制哈希值的前4位（大写）
   */
  private static String getTimestampHash() {
    long timestamp = System.currentTimeMillis();
    int hash = (int) (timestamp ^ (timestamp >>> 32));
    // 掩码取低 16 位并恒定补齐 4 位大写十六进制（%04X）——原先 toHexString 前导零被吞，低哈希值会不足 4 位
    return String.format("%04X", hash & 0xFFFF);
  }

  /** 重置回款单号序号（用于测试或手动重置） */
  public static synchronized void resetPaymentSequence() {
    PAYMENT_SEQUENCE.set(1);
    paymentCachedDate = "";
  }

  /** 重置发票编号序号（用于测试或手动重置） */
  public static synchronized void resetInvoiceSequence() {
    INVOICE_SEQUENCE.set(1);
    invoiceCachedDate = "";
  }
}
