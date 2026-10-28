package com.unicarona.privacy.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class PrivacyDownloadConfiguration implements WebMvcConfigurer {
    @Bean public ThreadPoolTaskExecutor privacyDownloadExecutor(){
        var executor=new ThreadPoolTaskExecutor();executor.setThreadNamePrefix("unicarona-private-download-");executor.setCorePoolSize(1);executor.setMaxPoolSize(2);executor.setQueueCapacity(4);return executor;
    }
    @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer){configurer.setTaskExecutor(privacyDownloadExecutor());configurer.setDefaultTimeout(300000);}
}
