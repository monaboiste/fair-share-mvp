package com.github.monaboiste.fairshare.settlement.infrastructure.runtime;

import com.github.monaboiste.fairshare.common.Result;
import com.github.monaboiste.fairshare.common.commands.Command;
import com.github.monaboiste.fairshare.common.commands.CommandDispatcher;
import com.github.monaboiste.fairshare.common.commands.CommandHandler;
import com.github.monaboiste.fairshare.common.commands.CommandInterceptor;
import com.github.monaboiste.fairshare.common.commands.RegisteredCommandDispatcher;
import com.github.monaboiste.fairshare.common.queries.Query;
import com.github.monaboiste.fairshare.common.queries.QueryDispatcher;
import com.github.monaboiste.fairshare.common.queries.QueryHandler;
import com.github.monaboiste.fairshare.common.queries.RegisteredQueryDispatcher;
import com.github.monaboiste.fairshare.settlement.application.command.SettlementCommand;
import com.github.monaboiste.fairshare.settlement.application.query.SettlementQuery;
import com.github.monaboiste.fairshare.settlement.infrastructure.PostgresEventStore;
import com.github.monaboiste.fairshare.settlement.infrastructure.PostgresSettlementProjection;
import com.github.monaboiste.fairshare.settlement.infrastructure.SettlementLogConsumer;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Import({SettlementStorageConfiguration.class, SettlementHandlersConfiguration.class})
public class SettlementRuntimeConfiguration {
    @Bean(initMethod = "start", destroyMethod = "stop")
    SettlementLogConsumer consumer(
            PostgresEventStore events, PostgresSettlementProjection views, Environment environment) {
        return new SettlementLogConsumer(
                events,
                views,
                environment.getProperty("fairshare.consumer.batch-limit", Integer.class, 64),
                environment.getProperty("fairshare.consumer.poll-millis", Long.class, 100L),
                environment.getProperty("fairshare.consumer.retry-millis", Long.class, 1000L),
                (2L * SettlementStorageConfiguration.databaseTimeout(environment) + 5L) * 1000L);
    }

    @Bean
    CommandDispatcher commandDispatcher(
            List<CommandHandler<?, ?, ?>> handlers,
            ObjectProvider<CommandInterceptor> interceptors,
            SettlementLogConsumer consumer) {
        var builder = RegisteredCommandDispatcher.builder();
        handlers.forEach(handler -> register(builder, handler));
        interceptors.orderedStream().forEach(builder::intercept);
        var registered = builder.requireHandlersFor(SettlementCommand.class).build();
        return new CommandDispatcher() {
            @Override
            public <F, S> Result<F, S> dispatch(Command<F, S> command) {
                requireReady(consumer);
                return registered.dispatch(command);
            }
        };
    }

    @Bean
    QueryDispatcher queryDispatcher(List<QueryHandler<?, ?, ?>> handlers, SettlementLogConsumer consumer) {
        var builder = RegisteredQueryDispatcher.builder();
        handlers.forEach(handler -> register(builder, handler));
        var registered = builder.requireHandlersFor(SettlementQuery.class).build();
        return new QueryDispatcher() {
            @Override
            public <F, S> Result<F, S> dispatch(Query<F, S> query) {
                requireReady(consumer);
                return registered.dispatch(query);
            }
        };
    }

    private static void requireReady(SettlementLogConsumer consumer) {
        if (!consumer.ready()) {
            throw new IllegalStateException("Settlement dispatch is not ready", consumer.failure());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(RegisteredCommandDispatcher.Builder builder, CommandHandler<?, ?, ?> handler) {
        Class type = Objects.requireNonNull(ResolvableType.forClass(handler.getClass())
                .as(CommandHandler.class)
                .getGeneric(0)
                .resolve());
        builder.register(type, (CommandHandler) handler);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(RegisteredQueryDispatcher.Builder builder, QueryHandler<?, ?, ?> handler) {
        Class type = Objects.requireNonNull(ResolvableType.forClass(handler.getClass())
                .as(QueryHandler.class)
                .getGeneric(0)
                .resolve());
        builder.register(type, (QueryHandler) handler);
    }
}
