package com.wenjin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 代码执行沙箱后端（wenjin.code-check.*，方向 6）。
 * <p>开关在 {@code wenjin.features.code-check-enabled}，资源上限在 {@code wenjin.limits.code.*}；这里只放
 * 「用哪个后端、怎么连」。{@link #backend} 启动时决定（选择沙箱 bean），不可热切换；Judge0 的地址与密钥
 * 每次调用现读，登记在管理后台「系统配置」，故标 {@code volatile}。</p>
 */
@Data
@ConfigurationProperties(prefix = "wenjin.code-check")
public class CodeCheckProperties {

    /** 沙箱后端：docker（本机 docker CLI）/ judge0（自托管 Judge0）/ none（恒不可用） */
    private Backend backend = Backend.DOCKER;

    private final Docker docker = new Docker();
    private final Judge0 judge0 = new Judge0();

    public enum Backend {
        DOCKER, JUDGE0, NONE
    }

    @Data
    public static class Docker {
        /** docker CLI 可执行文件（名或绝对路径） */
        private String binary = "docker";
        /** 编译+运行镜像；须预先 docker pull（运行时 --pull never，缺镜像即不可用） */
        private String image = "eclipse-temurin:21-jdk";
        /** 宿主侧临时目录的父目录；空 = java.io.tmpdir。须能被 docker 守护进程绑定挂载 */
        private String workDir = "";
        /** 可用性后台刷新间隔秒数（docker version + image inspect；请求路径只读最近一次结果） */
        private int availabilityCacheSeconds = 30;
    }

    @Data
    public static class Judge0 {
        /** 自托管 Judge0 基址，如 http://judge0.internal:2358；空 = 不可用 */
        private volatile String baseUrl = "";
        /** Judge0 AUTHN_TOKEN（请求头 X-Auth-Token）；加密存于 sys_config */
        private volatile String apiKey = "";
        /** Java 的 language_id（Judge0 CE 1.13：62 = OpenJDK 13） */
        private int languageId = 62;
        /** 单次 HTTP 读超时秒数（wait=true 同步等待判题） */
        private int requestTimeoutSeconds = 30;
        /** 可用性后台刷新间隔秒数（GET /about；请求路径只读最近一次结果） */
        private int availabilityCacheSeconds = 30;
    }
}
