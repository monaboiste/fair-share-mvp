import org.jspecify.annotations.NullMarked;

@NullMarked
module com.github.monaboiste.fairshare.app.domain {
    requires static org.jspecify;
    requires transitive com.github.monaboiste.fairshare.common;
    requires transitive com.github.monaboiste.fairshare.quantity;
    requires transitive com.github.monaboiste.fairshare.pricing;
    requires com.github.monaboiste.fairshare.graphs;
    requires transitive java.money;

    exports com.github.monaboiste.fairshare.settlement.domain;
    exports com.github.monaboiste.fairshare.settlement.domain.event;
    exports com.github.monaboiste.fairshare.settlement.domain.netting;
    exports com.github.monaboiste.fairshare.settlement.domain.valuation;
    exports com.github.monaboiste.fairshare.settlement.domain.model to
            com.github.monaboiste.fairshare.app.application,
            com.github.monaboiste.fairshare.app.infrastructure,
            com.github.monaboiste.fairshare.app.bootstrap;
}
