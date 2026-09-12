package com.slz.crm.unit.knowledge.document;

import com.slz.crm.knowledge.document.DocumentIngestionService;
import com.slz.crm.knowledge.document.KnowledgeReingestRunner;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UploadedFileMapper;
import com.slz.crm.server.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 重建入库运维 runner 单测（提案4 任务 4.3/4.4）：
 * 触发变量缺失为空操作；指定文档跑批隔离单文档失败；all 展开为存量全集；
 * 操作人必须解析到真实用户身份；操作人上下文逐文档传给服务层做写授权。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeReingestRunnerTest {

    @Mock
    private DocumentIngestionService ingestionService;
    @Mock
    private UploadedFileMapper uploadedFileMapper;
    @Mock
    private UserMapper userMapper;

    private final MockEnvironment environment = new MockEnvironment();

    private KnowledgeReingestRunner runner() {
        return new KnowledgeReingestRunner(ingestionService, uploadedFileMapper, userMapper, environment);
    }

    @Test
    void noTriggerIsNoOp() {
        runner().run(null);
        verifyNoInteractions(ingestionService, uploadedFileMapper, userMapper);
    }

    /** 指定文档跑批：单文档失败不中断（可修复后幂等重跑），操作人取真实身份。 */
    @Test
    void specifiedDocumentsRunSequentiallyAndIsolateFailures() {
        environment.withProperty(KnowledgeReingestRunner.TRIGGER_KEY, "doc-1, doc-2")
                .withProperty(KnowledgeReingestRunner.OPERATOR_ID_KEY, "7");
        when(userMapper.selectById(7L)).thenReturn(operator());
        doThrow(new IllegalArgumentException("待重建文档不存在: doc-2"))
                .when(ingestionService).reingest(eq("doc-2"), any());

        assertThatCode(() -> runner().run(null)).doesNotThrowAnyException();

        ArgumentCaptor<UserContext> operators = ArgumentCaptor.forClass(UserContext.class);
        verify(ingestionService, times(2)).reingest(anyString(), operators.capture());
        assertThat(operators.getValue().userId()).isEqualTo(7L);
        assertThat(operators.getValue().displayName()).isEqualTo("ops admin");
    }

    @Test
    void allTriggerExpandsToStoredDocuments() {
        environment.withProperty(KnowledgeReingestRunner.TRIGGER_KEY, "all")
                .withProperty(KnowledgeReingestRunner.OPERATOR_ID_KEY, "7");
        when(userMapper.selectById(7L)).thenReturn(operator());
        when(uploadedFileMapper.selectList(any())).thenReturn(List.of(storedDoc("doc-a"), storedDoc("doc-b")));

        runner().run(null);

        verify(ingestionService).reingest(eq("doc-a"), any());
        verify(ingestionService).reingest(eq("doc-b"), any());
    }

    @Test
    void missingOperatorAbortsBatch() {
        environment.withProperty(KnowledgeReingestRunner.TRIGGER_KEY, "all");
        runner().run(null);
        verifyNoInteractions(ingestionService, uploadedFileMapper);
    }

    @Test
    void unknownOperatorAbortsBatch() {
        environment.withProperty(KnowledgeReingestRunner.TRIGGER_KEY, "doc-1")
                .withProperty(KnowledgeReingestRunner.OPERATOR_ID_KEY, "404");
        when(userMapper.selectById(404L)).thenReturn(null);
        runner().run(null);
        verifyNoInteractions(ingestionService);
    }

    private UserEntity operator() {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setRoleId(1L);
        user.setDeptId(2L);
        user.setRealName("ops admin");
        return user;
    }

    private UploadedFileEntity storedDoc(String documentId) {
        UploadedFileEntity file = new UploadedFileEntity();
        file.setDocumentId(documentId);
        return file;
    }
}
