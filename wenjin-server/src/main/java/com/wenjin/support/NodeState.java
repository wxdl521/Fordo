package com.wenjin.support;

/**
 * 自适应诊断会话内的节点状态机（M2 T2）。
 *
 * <p>纯内存状态，无持久化注解。T3 负责将其序列化进
 * {@code adaptive_diag_session.state_json}，T4 负责驱动。
 *
 * <h2>状态取值</h2>
 * <ul>
 *   <li>{@link #UNKNOWN}：尚未实测、也未被推断，或推断冲突回退。</li>
 *   <li>{@link #TESTED_PASS} / {@link #TESTED_FAIL}：该节点探针题实测结果（最高优先级，永不被推断覆盖）。</li>
 *   <li>{@link #INFERRED_PASS}：其某个后继（依赖）节点实测通过 ⇒ 前置祖先推断掌握。</li>
 *   <li>{@link #INFERRED_FAIL}：其某个前置节点实测未通过 ⇒ 后继子孙推断薄弱。</li>
 * </ul>
 */
public enum NodeState {
    UNKNOWN,
    TESTED_PASS,
    TESTED_FAIL,
    INFERRED_PASS,
    INFERRED_FAIL;

    /** 是否为实测态（TESTED_*）——实测优先级高于推断，永不被传播改动。 */
    public boolean isTested() {
        return this == TESTED_PASS || this == TESTED_FAIL;
    }

    /** 是否为推断态（INFERRED_*）——可被选中复测，实测结果覆盖之。 */
    public boolean isInferred() {
        return this == INFERRED_PASS || this == INFERRED_FAIL;
    }
}
