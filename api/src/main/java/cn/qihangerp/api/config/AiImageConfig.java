package cn.qihangerp.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI生图相关配置：受限并发的生图线程池 + 本地结果图静态映射
 */
@Configuration
public class AiImageConfig implements WebMvcConfigurer {

    /** 本地结果图存储目录（与 AiImageService 一致） */
    private static final String LOCAL_STORE_DIR =
            System.getProperty("user.home") + "/qihang-oms/ai-images";

    /**
     * 生图线程池：并发 2，队列 20，满则由调用线程提示稍后再试。
     * 上游生图服务慢且按张计费，限制并发防止连点打爆。
     */
    @Bean("aiImageExecutor")
    public Executor aiImageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("ai-image-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 七牛云未配置时的兜底：/ai-images/** 映射本地磁盘文件
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/ai-images/**")
                .addResourceLocations(Paths.get(LOCAL_STORE_DIR).toUri().toString());
    }
}
