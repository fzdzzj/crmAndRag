package com.slz.crm.pojo.ao;

import java.util.Collections;
import java.util.Set;
/**
 * 隐私接口
 */
public interface Privacy {

    /**
     * 屏蔽邮箱
     * @return 是否成功
     */
    default Boolean email(){
        return false;
    }

    /**
     * 屏蔽电话
     * @return 是否成功
     */
    default Boolean mobile(){
        return false;
    }

    /**
     * 屏蔽固定电话
     * @return 是否成功
     */
    default Boolean phone(){
        return false;
    }

    /**
     * 屏蔽网址
     * @return 是否成功
     */
    default Boolean website(){
        return false;
    }

    /**
     * 屏蔽客户等级
     * @return 是否成功
     */
    default Boolean grade(){
        return false;
    }

    /**
     * 屏蔽岗位
     * @return 是否成功
     */
    default Boolean position(){
        return false;
    }

    /**
     * 执行所有方法
     */
    default void setPrivacy(){
        email();
        mobile();
        phone();
        website();
        grade();
        position();
    };

    /**
     * 返回该业务记录中“视同本人”的额外关联用户ID（如协助人/参与人），
     * 用于隐私脱敏 isSelf 判断：命中其中任意一个即不脱敏。
     * 默认空集合；由带业务身份字段的 VO 覆盖实现。
     *
     * @return 关联用户ID集合
     */
    default Set<Long> relatedUserIds() {
        return Collections.emptySet();
    }
}
