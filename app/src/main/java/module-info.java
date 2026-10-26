import org.jspecify.annotations.NullMarked;

@NullMarked
module com.github.monaboiste.fairshare.app {
    requires static org.jspecify;
    requires transitive com.github.monaboiste.fairshare.common;
    requires transitive com.github.monaboiste.fairshare.quantity;
    requires transitive com.github.monaboiste.fairshare.pricing;
    requires com.github.monaboiste.fairshare.graphs;
    requires transitive java.money;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.context;
    requires spring.core;
    requires spring.beans;
    requires spring.jdbc;
    requires spring.tx;
    requires java.sql;
    requires org.slf4j;

    opens com.github.monaboiste.fairshare.settlement.application to
            spring.core,
            spring.beans,
            spring.context;
    opens com.github.monaboiste.fairshare.settlement.infrastructure.codecs to
            com.fasterxml.jackson.databind;
    opens com.github.monaboiste.fairshare.settlement.infrastructure to
            spring.core,
            spring.beans;
    opens com.github.monaboiste.fairshare.settlement.config to
            spring.core,
            spring.beans,
            spring.context;

    exports com.github.monaboiste.fairshare.settlement.domain.netting;
    exports com.github.monaboiste.fairshare.settlement.application.command;
    exports com.github.monaboiste.fairshare.settlement.application.query;
    exports com.github.monaboiste.fairshare.settlement.domain;
    exports com.github.monaboiste.fairshare.settlement.domain.event;
    exports com.github.monaboiste.fairshare.settlement.domain.valuation;
}
