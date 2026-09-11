package com.slz.crm.platform.resilience;

/**
 * 依赖失败后的业务恢复策略。
 *
 * <p>Lane B 可实现该接口把上传文件映射为 PENDING/FAILED；Lane D 不持有知识库实体，
 * 因此用泛型隔离治理语义与业务模型。</p>
 *
 * @param <T> 业务目标对象（如上传文件实体）
 * @param <R> 恢复动作结果或状态
 */
public interface DependencyRecoveryPolicy<T, R> {

    /**
     * 处理可恢复失败。
     *
     * @param target 业务目标对象
     * @param error 依赖失败异常
     * @return 恢复动作结果
     */
    R onRecoverableFailure(T target, DependencyUnavailableException error);

    /**
     * 处理不可恢复失败。
     *
     * @param target 业务目标对象
     * @param error 原始异常
     * @return 失败落地结果
     */
    R onPermanentFailure(T target, Throwable error);
}
