package com.slz.crm.knowledge.document;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UploadedFileMapper;
import com.slz.crm.server.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 存量知识库重建入库 runner（提案4 任务 4.3/4.4，运维触发入口）。
 *
 * <p>入口选型（任务 4.3 二选一，实现时定）：取<b>运维 runner</b>——web 层
 * {@code UserContextHolder} 的登录态装配尚未落地，管理端点拿不到操作人身份，
 * 授权与审计都会落空；runner 由超管在运维环境显式触发，操作人身份取真实用户行。</p>
 *
 * <p>触发方式（环境变量，Spring Environment 同时吃 OS env 与启动属性）：</p>
 * <ul>
 *   <li>{@code RAG_REINGEST_TRIGGER=all}：全量重建（= 任务 4.4 的存量迁移跑批，
 *       真实嵌入 API 成本 × chunk 总量，执行前必须获用户确认）；</li>
 *   <li>{@code RAG_REINGEST_TRIGGER=<documentId>[,<documentId>...]}：指定文档重建，
 *       支持逗号分隔分批执行（跑批复用失败可恢复语义：单文档失败不中断，修复后幂等重跑）。</li>
 * </ul>
 *
 * <p>操作人：{@code RAG_REINGEST_OPERATOR_ID=<用户主键>}（必填），从 sys_user 加载真实
 * 身份构造 {@link UserContext}——服务层逐文档强制知识库写授权（canWrite），未授权文档
 * 记 DENIED 审计并计失败，不放大授权。未配置触发变量时本 runner 为空操作。</p>
 */
@Component
public class KnowledgeReingestRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeReingestRunner.class);

    public static final String TRIGGER_KEY = "rag.reingest.trigger";
    public static final String OPERATOR_ID_KEY = "rag.reingest.operator-id";
    private static final String TRIGGER_ALL = "all";

    private final DocumentIngestionService ingestionService;
    private final UploadedFileMapper uploadedFileMapper;
    private final UserMapper userMapper;
    private final Environment environment;

    public KnowledgeReingestRunner(DocumentIngestionService ingestionService,
                                   UploadedFileMapper uploadedFileMapper,
                                   UserMapper userMapper,
                                   Environment environment) {
        this.ingestionService = ingestionService;
        this.uploadedFileMapper = uploadedFileMapper;
        this.userMapper = userMapper;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String trigger = environment.getProperty(TRIGGER_KEY);
        if (trigger == null || trigger.isBlank()) {
            return;
        }
        UserContext operator = resolveOperator();
        if (operator == null) {
            return;
        }
        List<String> documentIds = resolveDocumentIds(trigger);
        int success = 0;
        int failed = 0;
        for (String documentId : documentIds) {
            try {
                ingestionService.reingest(documentId, operator);
                success++;
            } catch (Exception exception) {
                // 单文档失败不中断跑批：授权拒绝/嵌入失败均记审计，修复后幂等重跑
                failed++;
                log.error("重建入库失败 documentId={}", documentId, exception);
            }
        }
        log.info("重建入库跑批完成 total={} success={} failed={}", documentIds.size(), success, failed);
    }

    /** 操作人身份：必须配置真实用户主键，从 sys_user 加载（roleId/deptId 参与 UserContext 契约自检）。 */
    private UserContext resolveOperator() {
        Long operatorId = environment.getProperty(OPERATOR_ID_KEY, Long.class);
        if (operatorId == null) {
            log.error("触发重建入库但未配置操作人 {}，本次不执行", OPERATOR_ID_KEY);
            return null;
        }
        UserEntity operatorUser = userMapper.selectById(operatorId);
        if (operatorUser == null || operatorUser.getRoleId() == null || operatorUser.getDeptId() == null) {
            log.error("重建入库操作人不存在或身份不完整 operatorId={}", operatorId);
            return null;
        }
        return new UserContext(operatorUser.getId(), operatorUser.getRoleId(),
                operatorUser.getDeptId(), DataScopeLevel.NONE, operatorUser.getRealName());
    }

    /** all = 全量存量文档（软删除外）；其余按逗号分隔 documentId 列表（去空白、去空项）。 */
    private List<String> resolveDocumentIds(String trigger) {
        String normalized = trigger.strip();
        if (TRIGGER_ALL.equalsIgnoreCase(normalized)) {
            return uploadedFileMapper.selectList(new QueryWrapper<UploadedFileEntity>().select("document_id"))
                    .stream()
                    .map(UploadedFileEntity::getDocumentId)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
        }
        return Arrays.stream(normalized.split(","))
                .map(String::strip)
                .filter(id -> !id.isEmpty())
                .toList();
    }
}
