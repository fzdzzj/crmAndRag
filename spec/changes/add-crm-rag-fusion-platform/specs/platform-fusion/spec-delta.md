# 规范差异：platform-fusion（融合基座）

本文件包含对 `spec/specs/platform-fusion/spec.md` 的规范变更。

## ADDED 需求

### Requirement: 统一技术栈基线
系统 SHALL 以 Spring Boot 3.5.x / Java 21 作为唯一构建基线，所有迁入代码 MUST 在该基线下编译与运行。

#### Scenario: RAG 代码回填到基线
GIVEN RAG 原基于 Spring Boot 4.0.2（Jackson 3 `tools.jackson`、`spring-boot-starter-webmvc`、Security 7）
WHEN 将其迁入融合平台
THEN 系统将其适配为 Jackson 2、`spring-boot-starter-web`、Spring Security 6
AND LangChain4j、Qdrant、MinIO、Spring Data JPA 与 MyBatis-Plus 在同一构建中同时可用
AND 应用可正常启动

#### Scenario: 依赖版本冲突
GIVEN CRM 使用 jjwt 0.12.5 而 RAG 使用 jjwt 0.11.5
WHEN 合并依赖
THEN 系统统一到单一 jjwt 版本
AND 不产生重复类或运行期 NoSuchMethodError

### Requirement: 单一身份与认证源
系统 SHALL 以 CRM 的 JWT 与用户上下文作为平台唯一认证来源，迁入的知识库能力 MUST 消费同一身份主体而非独立登录。

#### Scenario: 登录后访问知识库
GIVEN 用户通过 CRM 登录获得 JWT
WHEN 用户携带该 JWT 访问知识库接口
THEN 系统从 CRM 用户上下文解析出稳定 userId
AND 知识库授权基于该 userId 判定
AND 不要求用户进行第二次登录

#### Scenario: 无效或缺失令牌
GIVEN 请求未携带有效 JWT
WHEN 访问受保护的知识库或业务接口
THEN 系统拒绝请求并返回统一未授权错误
AND 不泄露内部堆栈或 SQL 细节

### Requirement: 统一响应封装与错误处理
系统 SHALL 使用唯一的 `Result` 响应封装与全局异常处理器，迁入能力的响应 MUST 适配该封装且保持既有字段兼容。

#### Scenario: 迁入控制器返回统一封装
GIVEN RAG 控制器原返回其自有 Result
WHEN 迁入融合平台
THEN 系统改为返回 CRM `Result`
AND 成功/失败语义与状态码保持一致
AND 前端无需为不同模块编写两套解析逻辑

#### Scenario: 业务异常统一处理
GIVEN 任一模块抛出业务异常
WHEN 全局异常处理器捕获
THEN 系统返回稳定的用户侧提示
AND 完整异常仅写入服务端日志

### Requirement: 持久化统一到 MyBatis-Plus 与表归属边界
系统 SHALL 将平台持久化统一到 MyBatis-Plus：CRM 域沿用，知识库域由 Spring Data JPA 迁移为 MyBatis-Plus；表结构 MUST 由 Flyway 版本化迁移管理，并 MUST 明确每张表的归属域。

#### Scenario: 知识库域迁移到 MyBatis-Plus
GIVEN 知识库域原使用 Spring Data JPA（Repository + Hibernate ddl-auto）
WHEN 迁入融合平台
THEN 系统将 JPA Repository 改写为 MyBatis-Plus Mapper、实体注解改为 MyBatis-Plus 注解
AND 移除 Hibernate 与 ddl-auto，改由 Flyway 管理表结构
AND 迁移后原有查询语义保持一致

#### Scenario: 表归属边界
GIVEN 每张表明确归属 CRM 域或知识库域
WHEN 某域代码尝试直接写入对方域的表
THEN 系统拒绝该设计并要求改为服务接口协作

### Requirement: 统一配置与生产配置保护
系统 SHALL 合并两套配置为单一配置体系，敏感项 MUST 通过环境变量注入，并按 dev/test/prod 分离 profile。

#### Scenario: 敏感项外置
GIVEN JWT 密钥、数据库凭据、MinIO 密钥、LLM API Key
WHEN 应用启动
THEN 系统从环境变量读取而非硬编码
AND 生产环境使用弱默认值时启动失败（fail-fast）

#### Scenario: 生产 profile 保护
GIVEN 应用以 prod profile 启动
WHEN 检测到不安全的默认配置
THEN 系统拒绝启动并给出明确的配置缺失项

---

## 备注

- 本能力域对应决策 D1（Boot 基线）、D2（持久化归一 MyBatis-Plus）、D3（认证）、D5（模块结构）。
- 包重命名 `com.mark.knowledge.*` → `com.slz.crm.knowledge.*` 属实现细节，须在基座阶段一次完成。
