package com.wenjin.service.code;

/**
 * {@link DefaultProcessRunnerTest} 用的子进程入口（只在测试 classpath 上；不是学生代码）。
 * 用法：print &lt;bytes&gt; | sleep &lt;ms&gt; | exit &lt;code&gt; | stderr &lt;text&gt;
 */
public final class ProcessRunnerFixture {

    private ProcessRunnerFixture() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "print" -> {
                int n = Integer.parseInt(args[1]);
                byte[] chunk = "0123456789".repeat(100).getBytes();
                int left = n;
                while (left > 0) {
                    int k = Math.min(left, chunk.length);
                    System.out.write(chunk, 0, k);
                    left -= k;
                }
                System.out.flush();
            }
            case "sleep" -> Thread.sleep(Long.parseLong(args[1]));
            case "exit" -> System.exit(Integer.parseInt(args[1]));
            case "stderr" -> {
                System.err.print(args[1]);
                System.out.print("out");
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
