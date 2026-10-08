package com.zhihu.dongcha.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.config.CorsRegistry;

/** CORS：允许前端 dev（http://localhost:5173）直连兜底 */
@Configuration
public class CorsConfig implements WebFluxConfigurer {

    @Value("${app.cors-origins}")
    private String[] origins;

    /** 从 app.cors-origins 读来源白名单为 /api/** 放行方法/头并允许携带凭证，暴露 Content-Disposition，预检缓存 3600 秒。*/
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
