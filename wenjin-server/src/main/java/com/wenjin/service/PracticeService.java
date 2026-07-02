package com.wenjin.service;

import com.wenjin.dto.PracticeStartVO;

/**
 * 节点练习服务（M1 练习闭环）。
 *
 * <p>Controller 层（T7）负责鉴权与入参绑定，此接口只处理业务逻辑。</p>
 */
public interface PracticeService {

    /**
     * 开始节点练习：组卷 + 创建 practice_session（状态 0=进行中）。
     *
     * <p>组卷策略：
     * <ol>
     *   <li>从 question_node 取该节点关联题（weight=1 优先），过滤已审核（status=1）；</li>
     *   <li>排除该学生 7 天内在任意场景答过的题；</li>
     *   <li>不足 size 时放宽到 weight=2，再不足就有多少出多少（≥1 开会话，0 题抛异常）；</li>
     *   <li>轻度难度分层：优先覆盖难度 2–4 各至少一题。</li>
     * </ol>
     *
     * @param studentId 学生 ID（由 Controller 从 CurrentUser 取，service 不做鉴权）
     * @param courseId  课程 ID
     * @param nodeId    练习节点 ID（kg_node.id）
     * @param size      期望题数，null 时取配置默认值（5），超上限（10）自动夹紧
     * @return 会话 + 节点信息 + 脱敏题目列表
     */
    PracticeStartVO start(Long studentId, Long courseId, Long nodeId, Integer size);
}
