package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import java.util.Optional;

/** Statement-local checks apply only to explicitly planned exact aggregate wrappers. */
final class ExactAggregateRenderer {
    private ExactAggregateRenderer() {}

    static GeneratedFile render(ProjectArtifact.ExactAggregate artifact) {
        String content = "package " + artifact.packageName() + ";\n\n" + """
                import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
                import java.math.BigDecimal;
                import java.sql.SQLDataException;
                import java.sql.Statement;
                import java.util.List;
                import java.util.Map;
                import org.apache.ibatis.executor.statement.StatementHandler;
                import org.apache.ibatis.plugin.Interceptor;
                import org.apache.ibatis.plugin.Intercepts;
                import org.apache.ibatis.plugin.Invocation;
                import org.apache.ibatis.plugin.Signature;
                import org.apache.ibatis.session.ResultHandler;
                import org.springframework.stereotype.Component;

                @Component
                @Intercepts(@Signature(type = StatementHandler.class, method = "query",
                    args = {Statement.class, ResultHandler.class}))
                public final class ExactAggregate implements Interceptor {
                    /** The root wrapper is also the parameter marker; lambda() shares its state. */
                    public static final class Query<T> extends QueryWrapper<T> {
                        private Query(Class<T> type, String projection) {
                            super(type);
                            select(projection);
                        }
                    }

                    public static <T> Query<T> query(Class<T> type, String projection) {
                        return new Query<>(type, projection);
                    }

                    public static <T> BigDecimal read(Query<T> query,
                            java.util.function.Consumer<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<T>> filter,
                            java.util.function.Function<Query<T>, ? extends List<?>> execute) {
                        filter.accept(query.lambda());
                        return value(execute.apply(query));
                    }

                    public static BigDecimal value(List<?> rows) {
                        if (rows == null || rows.size() != 1 || !(rows.getFirst() instanceof BigDecimal))
                            throw new IllegalStateException("Exact aggregate must return one Decimal value");
                        return (BigDecimal) rows.getFirst();
                    }

                    @Override
                    public Object intercept(Invocation invocation) throws Throwable {
                        StatementHandler handler = (StatementHandler) invocation.getTarget();
                        Object parameter = handler.getParameterHandler().getParameterObject();
                        if (!(parameter instanceof Map<?, ?> map) || !map.containsKey("ew") || !(map.get("ew") instanceof Query<?>))
                            return invocation.proceed();
                        Statement statement = (Statement) invocation.getArgs()[0];
                        statement.clearWarnings();
                        Object result = invocation.proceed();
                        var warning = statement.getWarnings();
                        if (warning != null)
                            throw new SQLDataException("Exact decimal aggregate rejected SQL warning", "22003",
                                warning.getErrorCode(), warning);
                        return result;
                    }
                }
                """;
        return new GeneratedFile(artifact.path(), content, artifact.id(), Optional.empty());
    }
}
