import org.jspecify.annotations.NullMarked;

@NullMarked
module com.github.monaboiste.fairshare.app.infrastructure {
    requires static org.jspecify;
    requires transitive com.github.monaboiste.fairshare.app.application;
    requires transitive com.github.monaboiste.fairshare.app.domain;
    requires transitive com.github.monaboiste.fairshare.common;
    requires com.github.monaboiste.fairshare.quantity;
    requires com.github.monaboiste.fairshare.pricing;
    requires java.money;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;
    requires transitive spring.context;
    requires transitive spring.jdbc;
    requires transitive spring.tx;
    requires java.sql;
    requires org.slf4j;

    exports com.github.monaboiste.fairshare.settlement.infrastructure to
            com.github.monaboiste.fairshare.app.bootstrap;

    opens com.github.monaboiste.fairshare.settlement.infrastructure to
            spring.core,
            spring.beans;
    opens com.github.monaboiste.fairshare.settlement.infrastructure.codecs to
            com.fasterxml.jackson.databind;
}
