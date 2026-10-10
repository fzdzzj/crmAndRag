# 提案：按依赖名覆盖熔断参数（wire-circuit-per-dependency-override）

## 为什么

P-w 上线全局 2 键（`platform.resilience.failure-threshold` / `open-duration-ms`）后，五个依赖（model-chat / model-embed / model-vision / vector-qdrant / storage-minio）共用一组参数。不同依赖的故障特征不同（嵌入调用重且贵、存储调用轻而快），运维需要给单依赖定制阈值/窗口的能力——P-w 立项时明确"按依赖名级联留后续"。

## 做什么

1. **10 平键**：`platform.resilience.failure-threshold.<dep>` / `platform.resilience.open-duration-ms.<dep>`（五依赖 × 2 参数），范围沿用全局（1~1000 / 0~86400000），Registry catalog 注册 + 文档登记。
2. **逐级回落**：ResilienceConfigResolver 带依赖名重载——覆盖键未配置/非法/越界 → 全局键；全局键也越界/缺失 → 默认 5/30000。每级独立校验，删覆盖键即恢复全局；拼错依赖名自然回落（未注册键 get 返回 default）。
3. **Executor supplier 泛化**：`Supplier` 字段泛化为 `Function<String, ...>` 按依赖名解析；测试构造器签名不变内部包装恒定函数，既有断言零破坏。

## 取舍

- **平键而非 JSON 聚合键**：每键独立范围校验与管理端展示，与 Registry"键必须预注册"模式零冲突；JSON 键需引入值形状解析与嵌套 fail-safe 面。
- **逐级回落而非越界即默认**：配错一个覆盖键跌回全局定制值（而非突然跌回 5/30000 默认），运维心智连续；每级独立校验链条清晰。
- **不改三态状态机与 execute 语义**：本卡只动参数解析链，P-v 半开探测与 F-3 修复零触碰。

## 影响

- 写集：`DynamicConfigKeyRegistry` / `ResilienceConfigResolver` / `DependencyResilienceExecutor` / 对应测试 / `docs/dynamic-config-keys.md` / `scripts/test-baseline.txt` / tasks.md，≤10 tracked。
- 零改动：三门面+ModelCallGuard+两装配、P-x 全部产物、Registry 既有键、execute/executeNoRetry 语义。
- 生效语义：与 P-w 同——失败判定实时读、新值即刻生效、在飞 OPEN 窗口不追溯。
