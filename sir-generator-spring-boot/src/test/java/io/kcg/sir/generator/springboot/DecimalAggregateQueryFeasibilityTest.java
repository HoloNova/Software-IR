package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.junit.jupiter.api.Test;

/** A dependency experiment, not an implementation or database verification of SIR SUM. */
class DecimalAggregateQueryFeasibilityTest {
    @TableName("aggregate_probe")
    public static class AmountRow {
        @TableId private Long id;
        private Long ownerId;
        private BigDecimal amount;
        public Long getId() { return id; }
        public Long getOwnerId() { return ownerId; }
        public BigDecimal getAmount() { return amount; }
    }
    public interface AmountMapper extends BaseMapper<AmountRow> {}

    @Test void existingSelectObjsCarriesAnAggregateAndLambdaPredicatesInTheSameStatement() throws Exception {
        var query=new QueryWrapper<>(AmountRow.class).select("COALESCE(SUM(`amount`), 0)");
        var predicate=query.lambda();
        predicate.eq(AmountRow::getOwnerId,42L).ge(AmountRow::getAmount,new BigDecimal("0.01"));
        assertSame(query.getParamNameValuePairs(),predicate.getParamNameValuePairs());
        var result=bind(query);
        assertTrue(result.sql().contains("SELECT COALESCE(SUM(`amount`), 0) FROM aggregate_probe"),result.sql());
        assertTrue(result.sql().contains("owner_id = ?") && result.sql().contains("amount >= ?"),result.sql());
        assertFalse(result.sql().contains("42"),result.sql());
        assertEquals(List.of(42L,new BigDecimal("0.01")),result.parameters());
    }
    @Test void absentOptionalFilterDoesNotRemoveTheOwnersRequiredPredicate() throws Exception {
        var query=new QueryWrapper<>(AmountRow.class).select("COALESCE(SUM(`amount`), 0)");
        BigDecimal minimum=null;
        query.lambda().eq(AmountRow::getOwnerId,7L).ge(minimum!=null,AmountRow::getAmount,minimum);
        var result=bind(query);
        assertTrue(result.sql().contains("owner_id = ?"),result.sql());
        assertFalse(result.sql().contains("amount >="),result.sql());
        assertEquals(List.of(7L),result.parameters());
    }
    private record Bound(String sql,List<Object> parameters) {}
    private static Bound bind(QueryWrapper<AmountRow> query) throws Exception {
        var configuration=new MybatisConfiguration();
        var assistant=new MapperBuilderAssistant(configuration,"aggregate-dependency-probe");
        assistant.setCurrentNamespace(AmountMapper.class.getName());
        new DefaultSqlInjector().inspectInject(assistant,AmountMapper.class);
        var mapped=configuration.getMappedStatement(AmountMapper.class.getName()+".selectObjs");
        var parameter=Map.of("ew",query);
        var sql=mapped.getBoundSql(parameter);
        var values=new ArrayList<Object>();
        var statement=(PreparedStatement)Proxy.newProxyInstance(DecimalAggregateQueryFeasibilityTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},(proxy,method,args)-> {
                    assertTrue(method.getName().startsWith("set"),method.getName());
                    values.add(args[1]); return null;
                });
        new DefaultParameterHandler(mapped,parameter,sql).setParameters(statement);
        return new Bound(sql.getSql().replaceAll("\\s+"," ").trim(),List.copyOf(values));
    }
}
