package com.wenjin.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量加学生的结果。
 * <ul>
 *   <li>{@code added}：本次新进入该班级的学生数，含从别的班转入的（{@code movedFrom} 是其子集）；
 *       本来就在该班的学生不计入。</li>
 *   <li>{@code notFound}：不存在或不是学生的输入（studentIds 模式下为 id 的字符串）。</li>
 *   <li>{@code enrolled}：因此新补的 student_course 行数（班级已关联课程 x 新进入的学生，已存在的跳过）。</li>
 * </ul>
 */
@Data
public class ClazzAddStudentsResultVO {

    private int added;
    private List<MovedFrom> movedFrom = new ArrayList<>();
    private List<String> notFound = new ArrayList<>();
    private int enrolled;

    @Data
    public static class MovedFrom {
        private Long studentId;
        private String fromClazzName;

        public MovedFrom() {}

        public MovedFrom(Long studentId, String fromClazzName) {
            this.studentId = studentId;
            this.fromClazzName = fromClazzName;
        }
    }
}
