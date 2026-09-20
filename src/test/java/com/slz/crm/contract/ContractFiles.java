package com.slz.crm.contract;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 契约测试用的跨目录文件定位。
 *
 * <p>这些测试同时从仓根（Maven/CI）与 {@code src/test/java} 层级（IDE 直跑）启动，工作目录不固定， 因此按候选根目录逐个探测而不是硬编码相对路径。
 */
final class ContractFiles {

  private ContractFiles() {}

  /**
   * 定位仓根下的文件。
   *
   * @param relativeFromRoot 相对仓根的路径，例如 {@code frontend/openapi.yaml}
   * @return 存在的文件路径
   * @throws IllegalStateException 两个候选根都找不到时抛出（宁可报错也不要静默跳过门禁）
   */
  static Path find(String relativeFromRoot) {
    for (Path root : new Path[] {Path.of("."), Path.of("..")}) {
      Path candidate = root.resolve(relativeFromRoot).toAbsolutePath().normalize();
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "找不到契约文件 " + relativeFromRoot + "；请在仓库根目录运行 `mvn -B -ntp test`，不要从子目录直跑单测");
  }
}
