package com.github.monaboiste.fairshare.settlement.application;

import com.github.monaboiste.fairshare.settlement.config.FairShareConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication(proxyBeanMethods = false)
@Import(FairShareConfiguration.class)
public class FairShareApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(FairShareApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }
}
