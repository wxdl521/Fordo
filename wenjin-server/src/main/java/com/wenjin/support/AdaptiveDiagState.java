package com.wenjin.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * 自适应诊断会话状态快照 record（M2 T3）。
 *
 * <p>序列化进 {@code adaptive_diag_session.state_json}，T4 反序列化后传入
 * {@link AdaptiveEngine} 恢复构造器（{@code Map<Long,NodeState> existingStates}），
 * 使服务器在两次 HTTP 调用之间无状态。
 *
 * <h2>Jackson Map 键类型说明</h2>
 * <p>JSON 的 key 始终是字符串。当目标类型是 {@code Map<Long,NodeState>} 时，
 * Jackson 必须从 record 组件的泛型类型推断出 key 的目标类型为 {@code Long}，
 * 才能正确调用 Long 的 KeyDeserializer 将字符串 "10" 反序列化为 {@code 10L}。
 * 此处通过 {@code readValue(json, AdaptiveDiagState.class)} 让 Jackson 完整解析
 * record 组件泛型（Jackson 2.12+ 对 Java 16+ record 的原生支持），
 * key 正确恢复为 {@code Long}。测试用例显式断言 key instanceof Long。
 *
 * <h2>enum 序列化</h2>
 * <p>Jackson 默认使用枚举 name 字符串（如 "TESTED_PASS"），满足简报"enum 存字符串名"要求，
 * 无需额外配置。
 *
 * <h2>畸形 JSON 处理</h2>
 * <p>{@code state_json} 由服务端自产自销，畸形属于程序 bug 而非用户输入，
 * 因此 {@link #fromJson} 包一层 {@link IllegalStateException}（而非 BusinessException），
 * 让调用栈直接暴露 bug 信息，方便排查。
 */
public record AdaptiveDiagState(
        /** 节点状态机快照（传播完成后的全量状态，缺失节点视为 UNKNOWN）。 */
        Map<Long, NodeState> nodeStates,

        /** 已消耗题数（对应 {@link AdaptiveEngine#getBudgetUsed()}）。 */
        int budgetUsed,

        /**
         * 当前在途探针（可空）。
         * 进行中会话非空（题目已下发、作答尚未提交）；终止/放弃后为 {@code null}。
         */
        Probe currentProbe,

        /** 已完成探针历史（含判定结果），按时序追加。 */
        List<ProbeRecord> probeHistory
) {

    // ── 内嵌 record ──────────────────────────────────────────────────────────

    /**
     * 在途探针（题目顺序即下发顺序）。
     *
     * @param nodeId      被探针的知识点 id
     * @param questionIds 下发题目 id 列表（顺序与下发顺序一致，用于作答校验）
     */
    public record Probe(long nodeId, List<Long> questionIds) {}

    /**
     * 已完成探针记录。
     *
     * @param nodeId      被探针的知识点 id
     * @param questionIds 该探针使用的题目 id 列表
     * @param pass        探针判定结果（多数正确=PASS）
     */
    public record ProbeRecord(long nodeId, List<Long> questionIds, boolean pass) {}

    // ── Jackson 静态 ObjectMapper（单例，线程安全） ───────────────────────────

    /**
     * 共享 ObjectMapper，仅在此 record 内使用。
     * {@link JsonMapper#builder()} 构造的默认配置已满足本记录所有序列化需求：
     * <ul>
     *   <li>record 组件作为 JSON 属性（Jackson 2.12+ 原生支持）；</li>
     *   <li>enum 序列化为名称字符串（默认行为）；</li>
     *   <li>Map key 通过泛型解析正确反序列化为 Long（Jackson 解析 record 组件泛型）。</li>
     * </ul>
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    // ── 序列化方法 ────────────────────────────────────────────────────────────

    /**
     * 将当前状态快照序列化为 JSON 字符串，用于写入 {@code state_json} 列。
     *
     * @return JSON 字符串
     * @throws IllegalStateException 序列化失败（正常不会触发，属程序 bug）
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            // AdaptiveDiagState 全为标准 Java 类型，序列化失败意味着程序 bug
            throw new IllegalStateException("AdaptiveDiagState 序列化失败（bug）", e);
        }
    }

    /**
     * 从 JSON 字符串反序列化状态快照，用于从 {@code state_json} 列恢复会话。
     *
     * <p>反序列化后 {@code nodeStates} 的 Map key 类型为 {@code Long}（非 String），
     * 可直接传入 {@link AdaptiveEngine} 恢复构造器。
     *
     * @param json state_json 列的原始字符串
     * @return 恢复的状态快照
     * @throws IllegalStateException JSON 格式错误或结构不匹配（state_json 由服务端自产自销，
     *                               畸形属程序 bug 而非用户输入）
     */
    public static AdaptiveDiagState fromJson(String json) {
        try {
            AdaptiveDiagState result = MAPPER.readValue(json, AdaptiveDiagState.class);
            if (result == null) {
                throw new IllegalStateException("state_json 反序列化结果为 null（bug）");
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "state_json 反序列化失败，可能是 schema 不兼容（bug）: " + e.getOriginalMessage(), e);
        }
    }
}
