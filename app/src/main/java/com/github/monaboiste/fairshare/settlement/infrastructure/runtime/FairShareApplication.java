package com.github.monaboiste.fairshare.settlement.infrastructure.runtime;

import com.github.monaboiste.fairshare.settlement.config.FairShareConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

public final class FairShareApplication {
    private FairShareApplication() {}

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(FairShareConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }
}
