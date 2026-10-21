-- V18: 管理后台 A4 系统配置 — sys_config（配置覆盖层）
--
-- yml / 环境变量仍是默认值；本表里存在的 key 覆盖之（只认应用内白名单登记过的 key，其余启动时忽略并告警）。
--   · 启动（ApplicationReadyEvent）先快照每个白名单 key 的默认值，再把本表覆盖值写回对应 properties bean；
--   · 保存 / 恢复默认后立即写回 bean，无需重启；删除一行 = 恢复启动时快照的默认值。
--   · secret 类型（AI api-key）存 AES-GCM 密文（base64，含 12 字节 IV），encrypted=1；接口永不返回明文。
-- 无物理 FK：updated_by 只做留痕，用户删除后配置仍需保留。

CREATE TABLE `sys_config` (
    `config_key`   VARCHAR(128) NOT NULL                COMMENT '配置项 key，如 wenjin.ai.model（白名单登记）',
    `config_value` TEXT         NOT NULL                COMMENT '覆盖值；secret 类型为 AES-GCM 密文(base64)',
    `encrypted`    TINYINT      NOT NULL DEFAULT 0      COMMENT '1=config_value 为密文, 0=明文',
    `updated_by`   BIGINT       NULL                    COMMENT '最后修改人 sys_user.id（仅留痕，无物理外键）',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    PRIMARY KEY (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置覆盖层（白名单 key）';
