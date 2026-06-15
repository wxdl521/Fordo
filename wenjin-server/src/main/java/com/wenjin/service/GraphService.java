package com.wenjin.service;

import com.wenjin.dto.GraphDataVO;
import com.wenjin.dto.GraphImportRequest;
import com.wenjin.dto.GraphImportResult;

/**
 * 图谱服务：导入与查询。
 */
public interface GraphService {

    /**
     * 导入图谱（同一课程重复导入 = 全量替换）。
     * 导入前执行校验，任一不通过则整体拒绝并抛出携带明细的业务异常。
     *
     * @param courseCode 课程业务编码（URL 参数）
     * @param request    图谱数据（nodes + edges）
     * @return 导入摘要（课程ID、节点/边数）
     */
    GraphImportResult importGraph(String courseCode, GraphImportRequest request);

    /**
     * 查询某课程的全部节点与边，供前端染色地图渲染。
     *
     * @param courseId 课程主键
     * @return 图谱数据（节点掌握度本阶段统一返回 unlearned）
     */
    GraphDataVO getGraph(Long courseId);

    /**
     * 查询某课程的全部节点与边，并按学生填充真实掌握度。
     *
     * @param courseId  课程主键
     * @param studentId 学生主键；为 null 时节点掌握度统一返回 unlearned（向后兼容）
     * @return 图谱数据（节点含 mastery 级别串与 masteryScore 分值）
     */
    GraphDataVO getGraph(Long courseId, Long studentId);
}
