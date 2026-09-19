package com.slz.crm.common.untils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/** 单号生成工具类 用于生成回款单号、发票编号等业务单号 */
public class NumberGenerator {

  /** 回款单号前缀 */
  private static final String PAYMENT_PREFIX = "PAY";

  /** 发票编号前缀 */
  private static final String INVOICE_PREFIX = "INV";

  /** 日期格式化器 */
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

  /** 回款单号序号（线程安全） */
  private static final AtomicInteger paymentSequence = new AtomicInteger(1);

  /** 发票编号序号（线程安全） */
  private static final AtomicInteger invoiceSequence = new AtomicInteger(1);

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
      paymentSequence.set(1);
    }

    // 获取当前序号
    int sequence = paymentSequence.getAndIncrement();

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
      invoiceSequence.set(1);
    }

    // 获取当前序号
    int sequence = invoiceSequence.getAndIncrement();

    // 格式化为4位序号
    String sequenceStr = String.format("%04d", sequence);

    // 生成时间戳哈希值的前4位
    String timestampHash = getTimestampHash();

    return INVOICE_PREFIX + currentDate + sequenceStr + "-" + timestampHash;
  }

  /**
   * 获取当前时间戳哈希值的前4位 使用系统时间的毫秒值计算哈希，确保唯一性
   *
   * @return 16进制哈希值的前4位（大写）
   */
  private static String getTimestampHash() {
    long timestamp = System.currentTimeMillis();
    int hash = (int) (timestamp ^ (timestamp >>> 32));
    // 转为16进制并取前4位，大写
    return Integer.toHexString(hash).toUpperCase().substring(0, 4);
  }

  /** 重置回款单号序号（用于测试或手动重置） */
  public static synchronized void resetPaymentSequence() {
    paymentSequence.set(1);
    paymentCachedDate = "";
  }

  /** 重置发票编号序号（用于测试或手动重置） */
  public static synchronized void resetInvoiceSequence() {
    invoiceSequence.set(1);
    invoiceCachedDate = "";
  }
}
