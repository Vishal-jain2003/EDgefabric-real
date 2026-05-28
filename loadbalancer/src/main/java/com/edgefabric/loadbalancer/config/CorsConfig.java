package com.edgefabric.loadbalancer.config;



import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
//Configuration for frontend
// new configuration
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "PUT", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Type", "X-Tenant", "X-TTL-MS", "Content-Disposition", "X-Expires-At", "X-Quorum-Version")
                .allowCredentials(false)
                .maxAge(3600);
    }
}