package com.zhihu.dongcha;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用唯一启动类：Web 层只有 WebFlux（无 MVC），扫描范围为 com.zhihu.dongcha 全包。
 * 开启 @EnableScheduling，供 DbStore 的用户镜像按 TTL 重载等定时任务使用。
 */
@SpringBootApplication
@EnableScheduling
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
