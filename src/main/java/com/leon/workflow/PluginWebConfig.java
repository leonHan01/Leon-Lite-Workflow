package com.leon.workflow;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

@Configuration
public class PluginWebConfig implements WebMvcConfigurer {
    private final ApiTokenInterceptor tokenInterceptor;

    public PluginWebConfig(ApiTokenInterceptor tokenInterceptor) {
        this.tokenInterceptor = tokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenInterceptor).addPathPatterns("/api/**", "/alfred/**").excludePathPatterns("/api/ai-executor/**");
    }
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        for (String path : new String[]{"/api/web-collections/**", "/api/notes/**", "/api/todos/**",
                "/api/toolbox/**", "/api/llm-sessions/**", "/api/app-settings/**", "/api/todo-digest/**"}) {
            registry.addMapping(path)
                    .allowedOriginPatterns("chrome-extension://*", "http://localhost:*", "http://127.0.0.1:*")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .maxAge(3600);
        }
    }
}
