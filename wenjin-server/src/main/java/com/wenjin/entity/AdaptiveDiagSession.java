package com.wenjin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 自适应诊断会话实体（adaptive_diag_session 表，M2 图引导自适应诊断）。
 *
 * <p>会话是短生命周期对象（分钟级），不参与任何联表查询。
 * 引擎状态通过 {@link com.wenjin.support.AdaptiveDiagState#toJson()} / {@link com.wenjin.support.AdaptiveDiagState#fromJson}
 * 序列化入 {@code state_json}，服务器为唯一事实源，HTTP 调用间无状态。
 */
@Data
@TableName("adaptive_diag_session")
public class AdaptiveDiagSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学生（逻辑外键→sys_user.id） */
    private Long studentId;

    /** 课程（逻辑外键→course.id） */
    private Long courseId;

    /**
     * 会话状态快照 JSON（服务端唯一事实源）。
     * 由 {@link com.wenjin.support.AdaptiveDiagState#toJson()} 生成，
     * 读取后由 {@link com.wenjin.support.AdaptiveDiagState#fromJson} 反序列化。
     */
    private String stateJson;

    /** 状态：0=进行中, 1=已完成, 2=已放弃 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
