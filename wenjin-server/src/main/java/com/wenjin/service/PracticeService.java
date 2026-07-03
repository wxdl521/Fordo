package com.wenjin.service;

import com.wenjin.dto.PracticeHistoryVO;
import com.wenjin.dto.PracticeStartVO;
import com.wenjin.dto.PracticeSubmitRequest;
import com.wenjin.dto.PracticeSubmitVO;

import java.util.List;

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
     *   <li>不足 size 时放宽到 weight=2；</li>
     *   <li>recency 排除后可用为 0 时，放宽末档允许重复近期已答题（复练语义）；</li>
     *   <li>轻度难度分层：优先覆盖难度 2–4 各至少一题。</li>
     * </ol>
     *
     * @param studentId  学生 ID（由 Controller 从 CurrentUser 取，service 不做鉴权）
     * @param courseId   课程 ID
     * @param nodeId     练习节点 ID（kg_node.id）
     * @param pathItemId 来源路径步骤 ID（可空=自由练习；非空时 service 校验归属并写入 session）
     * @param size       期望题数，null 时取配置默认值（5），超上限（10）自动夹紧
     * @return 会话 + 节点信息 + 脱敏题目列表
     */
    PracticeStartVO start(Long studentId, Long courseId, Long nodeId, Long pathItemId, Integer size);

    /**
     * 提交练习会话：服务端判分 + answer_record 落库 + 掌握度更新。
     *
     * <p>幂等保证：session.status=1 时直接重建上次结果，不重复写 answer_record、
     * 不重复调 {@code MasteryService.applyAnswers}。
     *
     * @param sessionId 练习会话 ID
     * @param req       提交请求（studentId + 作答列表）；不含 isCorrect/pointNodeCode/answer
     * @return 判分明细 + 练习节点掌握度变化（itemCompleted/weakPrerequisites/pathRegenerated 由后续任务填充）
     */
    PracticeSubmitVO submit(Long sessionId, PracticeSubmitRequest req);

    /**
     * 查询学生在指定课程某知识点的近期练习历史（按创建时间倒序）。
     *
     * <p>供前端显示"上次练习 3/5"所需：每条记录包含题目总数、答对数、会话状态和创建时间。
     * 鉴权由 Controller 层处理（assertSelf + assertAccessibleByStudent），此方法只做查询。</p>
     *
     * @param studentId 学生 ID
     * @param courseId  课程 ID
     * @param nodeId    知识点 ID（kg_node.id）
     * @return 历史列表（最新在前，空列表 = 无练习记录）
     */
    List<PracticeHistoryVO> getHistory(Long studentId, Long courseId, Long nodeId);
}
