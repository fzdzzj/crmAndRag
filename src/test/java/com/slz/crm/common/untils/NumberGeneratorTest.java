package com.slz.crm.common.untils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 单号生成器单测（harden-single-replica-contract 任务 3.2）：纯 JVM，无 Spring 上下文。
 *
 * <p>覆盖：回款/发票单号分段格式契约（前缀 + 8 位日期 + 4 位序号 + - + 4 位大写十六进制哈希，其中哈希段 恒定 4 字符是本次 %04X
 * 加固的回归锚）、多线程并发下的单号唯一性与序号从 1 连续递增、 resetPaymentSequence / resetInvoiceSequence 跨日重置行为。
 */
class NumberGeneratorTest {

  /** 回款单号整号形态：PAY + 8位日期 + 4位序号 + - + 4位哈希，总长 20。 */
  private static final Pattern PAYMENT_NO_PATTERN = Pattern.compile("PAY\\d{12}-[0-9A-F]{4}");

  /** 发票编号整号形态：INV + 8位日期 + 4位序号 + - + 4位哈希，总长 20。 */
  private static final Pattern INVOICE_NO_PATTERN = Pattern.compile("INV\\d{12}-[0-9A-F]{4}");

  /** 并发参数：8 线程 × 每线程 200 个，量级足以在 synchronized 串行化下暴露唯一性/递增破坏。 */
  private static final int THREADS = 8;

  private static final int NUMBERS_PER_THREAD = 200;

  @Test
  void generatePaymentNoMatchesContractFormat() {
    NumberGenerator.resetPaymentSequence();
    String no = NumberGenerator.generatePaymentNo();
    assertTrue(PAYMENT_NO_PATTERN.matcher(no).matches(), "回款单号格式契约被破坏: " + no);
    assertEquals(20, no.length(), "回款单号总长必须为 20: " + no);
    assertEquals("0001", no.substring(11, 15), "重置后首个序号必须为 0001: " + no);
    assertTrue(no.substring(3, 11).matches("\\d{8}"), "日期段必须为 8 位数字: " + no);
  }

  @Test
  void generateInvoiceNoMatchesContractFormat() {
    NumberGenerator.resetInvoiceSequence();
    String no = NumberGenerator.generateInvoiceNo();
    assertTrue(INVOICE_NO_PATTERN.matcher(no).matches(), "发票编号格式契约被破坏: " + no);
    assertEquals(20, no.length(), "发票编号总长必须为 20: " + no);
    assertEquals("0001", no.substring(11, 15), "重置后首个序号必须为 0001: " + no);
    assertTrue(no.substring(3, 11).matches("\\d{8}"), "日期段必须为 8 位数字: " + no);
  }

  @Test
  void concurrentPaymentNumbersAreUniqueAndSequentiallyIncreasing() throws Exception {
    NumberGenerator.resetPaymentSequence();
    Set<String> numbers = generateConcurrently(() -> NumberGenerator.generatePaymentNo());
    assertEquals(THREADS * NUMBERS_PER_THREAD, numbers.size(), "并发生成的回款单号必须全部唯一");
    assertSequencesContiguousFromOne(numbers, "PAY");
  }

  @Test
  void concurrentInvoiceNumbersAreUniqueAndSequentiallyIncreasing() throws Exception {
    NumberGenerator.resetInvoiceSequence();
    Set<String> numbers = generateConcurrently(() -> NumberGenerator.generateInvoiceNo());
    assertEquals(THREADS * NUMBERS_PER_THREAD, numbers.size(), "并发生成的发票编号必须全部唯一");
    assertSequencesContiguousFromOne(numbers, "INV");
  }

  @Test
  void resetPaymentSequenceRestartsFromOne() {
    NumberGenerator.generatePaymentNo();
    NumberGenerator.generatePaymentNo();
    NumberGenerator.resetPaymentSequence();
    String no = NumberGenerator.generatePaymentNo();
    assertEquals("0001", no.substring(11, 15), "重置后首个回款单号序号必须回到 0001: " + no);
  }

  @Test
  void resetInvoiceSequenceRestartsFromOne() {
    NumberGenerator.generateInvoiceNo();
    NumberGenerator.generateInvoiceNo();
    NumberGenerator.resetInvoiceSequence();
    String no = NumberGenerator.generateInvoiceNo();
    assertEquals("0001", no.substring(11, 15), "重置后首个发票编号序号必须回到 0001: " + no);
  }

  /** 多线程并发生成单号：栅栏对齐同时起跑，返回全部单号去重后的集合。 */
  private Set<String> generateConcurrently(Supplier<String> generator) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      CountDownLatch ready = new CountDownLatch(THREADS);
      CountDownLatch start = new CountDownLatch(1);
      List<Future<List<String>>> futures = new ArrayList<>();
      for (int t = 0; t < THREADS; t++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  List<String> numbers = new ArrayList<>(NUMBERS_PER_THREAD);
                  for (int i = 0; i < NUMBERS_PER_THREAD; i++) {
                    numbers.add(generator.get());
                  }
                  return numbers;
                }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS), "工作线程未全部就绪");
      start.countDown();
      Set<String> all = new HashSet<>();
      for (Future<List<String>> future : futures) {
        future.get(30, TimeUnit.SECONDS).forEach(all::add);
      }
      return all;
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 按日期段分组校验序号：每组必须恰好为 1..k 连续递增（跨日重置时两组各自独立成立，不跨组比较）。
   *
   * @param numbers 去重后的完整单号集合
   * @param prefix 期望前缀（PAY / INV）
   */
  private void assertSequencesContiguousFromOne(Set<String> numbers, String prefix) {
    Map<String, List<Integer>> sequencesByDate = new HashMap<>();
    for (String no : numbers) {
      assertEquals(prefix, no.substring(0, 3), "单号前缀不符: " + no);
      sequencesByDate
          .computeIfAbsent(no.substring(3, 11), key -> new ArrayList<>())
          .add(Integer.parseInt(no.substring(11, 15)));
    }
    for (Map.Entry<String, List<Integer>> entry : sequencesByDate.entrySet()) {
      List<Integer> sequences = entry.getValue();
      sequences.sort(Comparator.naturalOrder());
      for (int i = 0; i < sequences.size(); i++) {
        assertEquals(i + 1, sequences.get(i), entry.getKey() + " 序号必须从 1 连续递增");
      }
    }
  }
}
