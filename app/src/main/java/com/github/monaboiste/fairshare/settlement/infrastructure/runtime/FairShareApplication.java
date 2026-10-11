package com.github.monaboiste.fairshare.settlement.infrastructure.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.Profiles;

public final class FairShareApplication {
    private FairShareApplication() {}

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(FairShareConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        var context = application.run(args);
        if (context.getEnvironment().acceptsProfiles(Profiles.of("rebuild"))) {
            context.close();
        }
    }
}
