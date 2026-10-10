# 增量契约规范：按依赖名覆盖熔断参数（wire-circuit-per-dependency-override）

## 1. 行为契约增量规范

### 契约 1：覆盖键优先（Override Takes Precedence）
- **GIVEN** `platform.resilience.failure-threshold.<dep>`（或 `open-duration-ms.<dep>`）在 DB 有合法范围内值；
- **WHEN** 依赖 `<dep>` 的熔断参数在下一次失败判定被解析；
- **THEN** 使用覆盖值（每次判定实时读，新写即刻生效；在飞 OPEN 窗口 openUntilNanos 已定值不追溯）。

### 契约 2：逐级回落（Cascading Fallback）
- **GIVEN** 覆盖键未配置、值非法（脏类型）或越界；
- **WHEN** 解析该依赖参数；
- **THEN** 视同未配置，读全局键 `platform.resilience.failure-threshold`（/ `open-duration-ms`）；全局键也越界/缺失/异常 → 默认 5 / 30000。每级独立校验，任一级异常不外抛（fail-safe）；删覆盖键即恢复全局。

### 契约 3：依赖隔离独立生效（Per-Dependency Isolation）
- **GIVEN** 依赖甲配置覆盖值而依赖乙未配置（或配置不同值）；
- **WHEN** 两依赖各自累计失败；
- **THEN** 甲按覆盖值开闸/开窗，乙按全局值——互不影响；拼错的依赖名（未注册键）自然回落全局。

### 契约 4：既有语义零回归（Zero Regression）
- **GIVEN** 全部覆盖键未配置；
- **WHEN** 任意依赖的熔断行为；
- **THEN** 与本变更合入前逐字等价（P-w 全局 2 键语义、P-v 三态状态机、F-3 次序修复、executeNoRetry 语义全部不变）；既有 executor/resolver 测试断言零修改；测试构造器签名不变。

### 契约 5：配置面边界（Configuration Surface Boundary）
- **GIVEN** 本变更合入后；
- **WHEN** 检查 Registry 键目录；
- **THEN** platform.resilience 命名空间下恰有全局 2 键 + 覆盖 10 键（五依赖 × 2 参数），范围同全局（threshold 1~1000 / open-duration-ms 0~86400000）；`docs/dynamic-config-keys.md` 与代码内联值一致；maxAttempts/backoff 仍不暴露（P-u 拍板维持）。
