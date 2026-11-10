import org.jspecify.annotations.NullMarked;

@NullMarked
module com.github.monaboiste.fairshare.app.application {
    requires static org.jspecify;
    requires transitive com.github.monaboiste.fairshare.app.domain;
    requires transitive com.github.monaboiste.fairshare.common;
    requires transitive com.github.monaboiste.fairshare.quantity;
    requires transitive com.github.monaboiste.fairshare.pricing;
    requires transitive java.money;

    exports com.github.monaboiste.fairshare.settlement.application.command;
    exports com.github.monaboiste.fairshare.settlement.application.query;
    exports com.github.monaboiste.fairshare.settlement.application.command.handler to
            com.github.monaboiste.fairshare.app.bootstrap;
    exports com.github.monaboiste.fairshare.settlement.application.query.handler to
            com.github.monaboiste.fairshare.app.bootstrap;
}
