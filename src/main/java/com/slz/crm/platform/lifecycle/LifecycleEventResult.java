package com.slz.crm.platform.lifecycle;

/**
 * 生命周期事件写入结果。
 *
 * @param created 本次是否新建
 * @param duplicate 是否因幂等键重复跳过
 * @param event 已存在或新建的事件
 */
public record LifecycleEventResult(
    boolean created, boolean duplicate, PlatformLifecycleEventEntity event) {}
