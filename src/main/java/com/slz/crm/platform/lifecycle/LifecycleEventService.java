package com.slz.crm.platform.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.platform.mapper.PlatformLifecycleEventMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 文档生命周期事件服务。
 *
 * <p>幂等键是崩溃恢复的核心：先写事件、后做副作用；消费方重复读取同一键时跳过。
 * 事件落库失败必须抛出，避免“副作用已做但无审计记录”。</p>
 */
@Service
public class LifecycleEventService {

    private final PlatformLifecycleEventMapper lifecycleEventMapper;
    private final MeterRegistry meterRegistry;

    /**
     * 构造生命周期事件服务。
     *
     * @param lifecycleEventMapper 事件 Mapper
     * @param meterRegistry Micrometer 注册表
     */
    public LifecycleEventService(PlatformLifecycleEventMapper lifecycleEventMapper,
                                 MeterRegistry meterRegistry) {
        this.lifecycleEventMapper = lifecycleEventMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 幂等记录生命周期事件。
     *
     * @param request 事件请求
     * @return 新建或重复跳过结果
     */
    public LifecycleEventResult record(LifecycleEventRequest request) {
        validate(request);
        PlatformLifecycleEventEntity entity = toEntity(request);
        try {
            lifecycleEventMapper.insert(entity);
            meterRegistry.counter("platform.lifecycle.recorded",
                            "event", request.eventType().name(), "result", "created")
                    .increment();
            return new LifecycleEventResult(true, false, entity);
        } catch (DuplicateKeyException exception) {
            PlatformLifecycleEventEntity existing = findByIdempotencyKey(request.idempotencyKey());
            meterRegistry.counter("platform.lifecycle.recorded",
                            "event", request.eventType().name(), "result", "duplicate")
                    .increment();
            return new LifecycleEventResult(false, true, existing);
        }
    }

    /**
     * 查询崩溃后尚未处理的事件。
     *
     * @param limit 最大返回数量
     * @return 事件列表
     */
    public List<PlatformLifecycleEventEntity> findUnprocessed(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return lifecycleEventMapper.selectList(new LambdaQueryWrapper<PlatformLifecycleEventEntity>()
                .isNull(PlatformLifecycleEventEntity::getProcessedTime)
                .orderByAsc(PlatformLifecycleEventEntity::getStatusVersion)
                .orderByAsc(PlatformLifecycleEventEntity::getId)
                .last("limit " + limit));
    }

    /**
     * 标记事件处理完成。
     *
     * @param eventId 事件主键
     * @return 是否更新成功
     */
    public boolean markProcessed(Long eventId) {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        PlatformLifecycleEventEntity entity = lifecycleEventMapper.selectById(eventId);
        if (entity == null || entity.getProcessedTime() != null) {
            return false;
        }
        entity.setProcessedTime(LocalDateTime.now());
        entity.setUpdateTime(entity.getProcessedTime());
        boolean updated = lifecycleEventMapper.updateById(entity) > 0;
        if (updated) {
            meterRegistry.counter("platform.lifecycle.processed").increment();
        }
        return updated;
    }

    private void validate(LifecycleEventRequest request) {
        Objects.requireNonNull(request, "LifecycleEventRequest 不能为空");
        if (request.documentId() == null || request.documentId().isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        if (request.eventType() == null) {
            throw new IllegalArgumentException("eventType 不能为空");
        }
        if (request.statusVersion() < 0) {
            throw new IllegalArgumentException("statusVersion 不能小于0");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
    }

    private PlatformLifecycleEventEntity toEntity(LifecycleEventRequest request) {
        PlatformLifecycleEventEntity entity = new PlatformLifecycleEventEntity();
        entity.setDocumentId(request.documentId());
        entity.setEventType(request.eventType().name());
        entity.setStatusVersion(request.statusVersion());
        entity.setIdempotencyKey(request.idempotencyKey());
        entity.setPayload(request.payload());
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        return entity;
    }

    private PlatformLifecycleEventEntity findByIdempotencyKey(String idempotencyKey) {
        PlatformLifecycleEventEntity existing = lifecycleEventMapper.selectOne(
                new LambdaQueryWrapper<PlatformLifecycleEventEntity>()
                        .eq(PlatformLifecycleEventEntity::getIdempotencyKey, idempotencyKey));
        if (existing == null) {
            throw new IllegalStateException("幂等键冲突但未找到已存在事件: " + idempotencyKey);
        }
        return existing;
    }
}
