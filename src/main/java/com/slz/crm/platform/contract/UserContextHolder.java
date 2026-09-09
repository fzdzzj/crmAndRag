package com.slz.crm.platform.contract;

/**
 * 用户身份快照的线程内持有器 + 跨线程传播工具（跨 lane 冻结契约）。
 *
 * <p>为什么单独抽一个 Holder：CRM 现有 {@code BaseUnit.threadLocal} 存的是 {@code RoleAO}
 * 且只有 id/roleId，没有 deptId，也无法表达“异步任务里身份是谁”。
 * 契约层必须提供统一的取用/清理/传播语义，否则各 lane 会各自复制 ThreadLocal，出现越权或内存泄漏。</p>
 *
 * <p>传播语义（异步快照传播）：</p>
 * <ul>
 *   <li>{@link #current()}：当前线程内的快照；无身份返回 {@code null}（匿名已被 D8 移除，
 *       调用方应当抛 {@code UNAUTHORIZED}，而不是伪造身份）；</li>
 *   <li>{@link #require()}：与 {@link #current()} 相同但空值直接抛 {@link IllegalStateException}，
 *       用于“必须登录才能继续”的代码路径；</li>
 *   <li>{@link #runWith(UserContext, Runnable)} / {@link #callWith(UserContext, Supplier)}：
 *       在提交到线程池前捕获快照，在任务线程内恢复；</li>
 *   <li>{@link #clear()}：请求结束（含异常路径）必须调用，防止线程池复用导致身份串号。</li>
 * </ul>
 *
 * <p>线程安全：{@code ThreadLocal} 只在本线程可见；复用线程池时 MUST 在 finally 里 {@link #clear()}。</p>
 */
public final class UserContextHolder {

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    /** 工具类禁止实例化 */
    private UserContextHolder() {
    }

    /**
     * @return 当前线程的身份快照；未设置返回 {@code null}
     */
    public static UserContext current() {
        return HOLDER.get();
    }

    /**
     * 取当前身份，空则失败（fail-fast）。
     *
     * @return 当前线程的身份快照
     * @throws IllegalStateException 匿名调用（D8 已移除匿名链路，此处视为程序缺陷）
     */
    public static UserContext require() {
        UserContext context = current();
        if (context == null) {
            throw new IllegalStateException("当前线程缺少 UserContext（匿名请求已在 D8 移除，必须先由认证层注入）");
        }
        return context;
    }

    /**
     * 设置当前线程身份（仅认证拦截器 / 测试装配应调用）。
     *
     * @param context 身份快照；传 {@code null} 等价于 {@link #clear()}
     */
    public static void set(UserContext context) {
        if (context == null) {
            clear();
            return;
        }
        HOLDER.set(context);
    }

    /** 清理当前线程身份；线程池任务结束时 MUST 调用，否则复用线程会串号 */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 在指定身份下执行任务（异步快照传播的最简形式）。
     *
     * <p>实现细节：进入时保存旧快照 → 设置新快照 → 执行 → finally 恢复旧快照，
     * 保证“嵌套包装”也能正确回退。</p>
     *
     * @param context 任务应使用的身份快照
     * @param action  任务体
     */
    public static void runWith(UserContext context, Runnable action) {
        UserContext previous = HOLDER.get();
        HOLDER.set(context);
        try {
            action.run();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    /**
     * 在指定身份下执行有返回值任务。
     *
     * @param context 任务应使用的身份快照
     * @param action  任务体
     * @param <T>     返回值类型
     * @return 任务返回值
     */
    public static <T> T callWith(UserContext context, java.util.function.Supplier<T> action) {
        UserContext previous = HOLDER.get();
        HOLDER.set(context);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }

    /**
     * 把任务包装成“提交线程快照 → 执行线程恢复快照”的形式，便于直接交给线程池。
     *
     * @param action 待包装任务
     * @param <T>    返回值类型
     * @return 包装后的任务；未设置身份时执行期会抛 {@link IllegalStateException}
     */
    public static <T> java.util.function.Supplier<T> capture(java.util.function.Supplier<T> action) {
        return () -> callWith(current(), action);
    }
}
