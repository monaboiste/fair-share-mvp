import org.jspecify.annotations.NullMarked;

@NullMarked
module com.github.monaboiste.fairshare.app.bootstrap {
    requires static org.jspecify;
    requires com.github.monaboiste.fairshare.app.domain;
    requires com.github.monaboiste.fairshare.app.application;
    requires com.github.monaboiste.fairshare.app.infrastructure;
    requires com.github.monaboiste.fairshare.common;
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.context;
    requires spring.core;
    requires spring.beans;
    requires spring.jdbc;
    requires java.sql;

    opens com.github.monaboiste.fairshare.settlement.application to
            spring.core,
            spring.beans,
            spring.context;
    opens com.github.monaboiste.fairshare.settlement.config to
            spring.core,
            spring.beans,
            spring.context;
}
