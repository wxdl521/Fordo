package com.wenjin.eval.fixture;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The isolated eval world's course content: a small software-engineering graph covering every node code
 * referenced by the scenarios (personas, review queues, goldens), prerequisite edges, and a few APPROVED
 * questions per node so {@code start_practice} / probes / degrade practice cards can produce items.
 */
public final class EvalCourseCatalog {

    public static final String COURSE_CODE = "EVAL_SE";
    public static final String COURSE_NAME = "软件工程（评测）";
    public static final String TEACHER_USERNAME = "eval_teacher";
    public static final String STUDENT_USERNAME = "eval_student";
    /** APPROVED questions seeded per node. */
    public static final int QUESTIONS_PER_NODE = 3;

    public record Node(String code, String name, String chapter, int difficulty, boolean key, String description) {}

    /** Prerequisite edge: {@code from} must be learned before {@code to} (relation_type=1). */
    public record Edge(String from, String to) {}

    /** Course material chunk linked to a node (RAG corpus for {@code search_materials}). */
    public record Material(String nodeCode, String title, String content) {}

    public static final List<Node> NODES = List.of(
            new Node("SE-1.1", "软件工程概述", "软件工程基础", 1, true,
                    "软件危机、软件工程的定义与目标"),
            new Node("SE-1.2", "软件生命周期模型", "软件工程基础", 2, false,
                    "瀑布模型、增量模型、螺旋模型与敏捷"),
            new Node("SE-2.1", "需求获取", "需求工程", 2, true,
                    "访谈、问卷、观察等需求获取方法"),
            new Node("SE-2.2", "需求规格说明", "需求工程", 2, false,
                    "需求规格说明书的结构与质量属性"),
            new Node("SE-3.1", "数据流图", "需求建模", 3, true,
                    "数据流、加工、数据存储与外部实体，分层数据流图"),
            new Node("SE-3.2", "用例图", "需求建模", 3, true,
                    "参与者、用例、系统边界以及包含/扩展关系"),
            new Node("SE-4.1", "软件体系结构设计", "软件设计", 3, false,
                    "分层、客户端/服务器、MVC 等体系结构风格"),
            new Node("SE-4.2", "详细设计", "软件设计", 3, false,
                    "模块内部算法与数据结构设计"));

    public static final List<Edge> EDGES = List.of(
            new Edge("SE-1.1", "SE-1.2"),
            new Edge("SE-1.1", "SE-2.1"),
            new Edge("SE-2.1", "SE-2.2"),
            new Edge("SE-2.1", "SE-3.2"),
            new Edge("SE-2.2", "SE-3.1"),
            new Edge("SE-3.1", "SE-4.1"),
            new Edge("SE-3.2", "SE-4.1"),
            new Edge("SE-4.1", "SE-4.2"));

    public static final List<Material> MATERIALS = List.of(
            new Material("SE-3.2", "需求建模讲义·用例图",
                    "用例图描述系统外部参与者与系统提供的功能之间的关系。画用例图时先确定系统边界，"
                            + "再识别参与者，最后为每个参与者列出它要完成的用例；包含关系表示必经的公共步骤，"
                            + "扩展关系表示在特定条件下才发生的可选行为。"),
            new Material("SE-3.1", "需求建模讲义·数据流图",
                    "数据流图从数据流动和加工的角度描述系统，由外部实体、加工、数据流和数据存储四种元素组成。"
                            + "分层数据流图先画顶层图，再逐层分解加工，父图与子图的输入输出必须保持平衡。"),
            new Material("SE-2.1", "需求工程讲义·需求获取",
                    "需求获取常用访谈、问卷调查、现场观察和原型等方法，目标是弄清用户真正要解决的问题。"));

    private EvalCourseCatalog() {
    }

    public static Set<String> nodeCodes() {
        return NODES.stream().map(Node::code).collect(Collectors.toUnmodifiableSet());
    }
}
