package com.wenjin.support;

import com.wenjin.entity.KgEdge;

/**
 * 前置边方向常量与访问器（M2 起点，所有传播代码必须经此取方向）。
 *
 * <h2>方向语义（已交叉验证，勿改）</h2>
 * <p><b>from_node_id 是 to_node_id 的前置（先修）节点。</b>
 * 即：存在边 (from=A, to=B, relation_type=1) 时，必须先学 A，才能学 B。
 *
 * <h2>确认证据（两个独立源，结论一致）</h2>
 * <ol>
 *   <li><b>主证据</b>：{@code DiagnosticResultServiceImpl#getResult}（错因回溯实现）——
 *       第 109–111 行构造 prereqMap 时注释写明
 *       {@code // toNodeId -> [fromNodeId]}，并以 {@code e.getToNodeId()} 为 key、
 *       {@code e.getFromNodeId()} 为值；沿该 map 反向 BFS 时取出的 fromNodeId
 *       正是"更基础的前置"，逻辑语义与结构一致。</li>
 *   <li><b>旁证</b>：{@code PathServiceImpl#generate}（学习路径 Kahn 拓扑排序）——
 *       第 137–139 行同样用相同注释和构造方式建立 prereqMap，
 *       并在拓扑排序中将 from 节点（prereqOf[to]）作为 to 节点的前置依赖计入入度。</li>
 * </ol>
 *
 * <h2>演示数据示例</h2>
 * <p>课程"软件工程"演示图谱（m1-data.json）中有边
 * {@code {f:97, r:1, t:98}}，即：
 * <pre>
 *     from = KT04「软件需求基本概念、任务目标」
 *     to   = KT05「分析构建业务流程」
 * </pre>
 * 语义：「软件需求基本概念、任务目标」是「分析构建业务流程」的前置，
 * 存储为 from=KT04, to=KT05。
 *
 * <h2>使用约定</h2>
 * <p>M2 后续所有传播代码（T2 InferenceEngine 等）一律通过本类访问器取端点 ID，
 * <b>不得</b>直接读取 {@code KgEdge.fromNodeId} / {@code KgEdge.toNodeId}。
 */
public final class PrereqDirection {

    /**
     * 前置边的 {@code kg_edge.relation_type} 值。
     * 与 {@link RelationType#PREREQUISITE} 语义等同，此处定义为 {@code int}
     * 常量供数值比较场景使用（无需引入枚举依赖）。
     */
    public static final int RELATION_TYPE_PREREQUISITE = RelationType.PREREQUISITE.getCode();

    private PrereqDirection() {
        // 工具类，禁止实例化
    }

    /**
     * 返回给定前置边的 <b>先修（前置）节点 ID</b>，即 {@code from_node_id}。
     *
     * @param edge 关系类型为前置的图谱边（调用方应自行过滤 relation_type=1）
     * @return 先修节点的 {@code kg_node.id}
     */
    public static Long prerequisiteNodeId(KgEdge edge) {
        return edge.getFromNodeId();
    }

    /**
     * 返回给定前置边的 <b>后继（依赖）节点 ID</b>，即 {@code to_node_id}。
     *
     * @param edge 关系类型为前置的图谱边（调用方应自行过滤 relation_type=1）
     * @return 后继节点的 {@code kg_node.id}
     */
    public static Long dependentNodeId(KgEdge edge) {
        return edge.getToNodeId();
    }
}
