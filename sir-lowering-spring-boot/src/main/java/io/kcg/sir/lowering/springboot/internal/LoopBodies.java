package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.lowering.springboot.model.PersistenceAction;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.semantic.api.SelfRelativeBinding;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The one rule for what a pass of a bounded loop may do, checked independently on the semantic model and on the
 * target model. A pass may read (validate, load, exists, count), write the row it is on or a row it loaded or created,
 * and delete such a row. A row from outside the loop is only accumulated into: an unguarded update whose every
 * binding changes its own column by an amount, followed by a plain persist. Repeated passes then never overwrite one
 * another, because the database applies each amount to the stored value.
 */
final class LoopBodies {
    private LoopBodies() {}

    static boolean validCore(SymbolId row, List<NormalizedStep> body) {
        Set<SymbolId> local = new HashSet<>(Set.of(row));
        Set<SymbolId> accumulated = new HashSet<>();
        for (NormalizedStep child : body) {
            declaredCore(child).ifPresent(local::add);
            switch (child) {
                case NormalizedStep.ValidateStep ignored -> { }
                case NormalizedStep.LoadStep ignored -> { }
                case NormalizedStep.ScalarQueryStep ignored -> { }
                case NormalizedStep.CreateStep ignored -> { }
                case NormalizedStep.UpdateStep update -> {
                    if (!local.contains(update.targetVariable())) {
                        boolean accumulates = update.guard().isEmpty() && !update.bindings().isEmpty()
                                && update.bindings().stream()
                                        .allMatch(b -> SelfRelativeBinding.of(b, update.targetVariable()).isPresent());
                        if (!accumulates) return false;
                        accumulated.add(update.targetVariable());
                    }
                }
                case NormalizedStep.PersistStep persist -> {
                    if (!local.contains(persist.targetVariable()) && !accumulated.contains(persist.targetVariable())) return false;
                }
                case NormalizedStep.DeleteStep delete -> {
                    if (!local.contains(delete.targetVariable())) return false;
                }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    static Optional<SymbolId> declaredCore(NormalizedStep step) {
        return switch (step) {
            case NormalizedStep.LoadStep s -> Optional.of(s.resultVariable());
            case NormalizedStep.CreateStep s -> Optional.of(s.resultVariable());
            case NormalizedStep.ScalarQueryStep s -> Optional.of(s.resultVariable());
            default -> Optional.empty();
        };
    }

    /** The same rule on the target model; an accumulated outer row must be written back with a plain update. */
    static boolean validTarget(SymbolId row, List<SpringBootWorkflow.Step> body) {
        Set<SymbolId> local = new HashSet<>(Set.of(row));
        Set<SymbolId> accumulated = new HashSet<>();
        for (SpringBootWorkflow.Step child : body) {
            declaredTarget(child).ifPresent(local::add);
            switch (child) {
                case SpringBootWorkflow.ValidateStep ignored -> { }
                case SpringBootWorkflow.LoadStep ignored -> { }
                case SpringBootWorkflow.ScalarQueryStep ignored -> { }
                case SpringBootWorkflow.CreateStep ignored -> { }
                case SpringBootWorkflow.UpdateStep update -> {
                    if (!local.contains(update.targetVariable())) {
                        boolean accumulates = update.guard().isEmpty() && !update.bindings().isEmpty()
                                && update.bindings().stream().allMatch(b -> b.relative().isPresent());
                        if (!accumulates) return false;
                        accumulated.add(update.targetVariable());
                    }
                }
                case SpringBootWorkflow.PersistStep persist -> {
                    boolean own = local.contains(persist.targetVariable());
                    boolean accumulation = accumulated.contains(persist.targetVariable()) && persist.action() == PersistenceAction.UPDATE;
                    if (!own && !accumulation) return false;
                }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    static Optional<SymbolId> declaredTarget(SpringBootWorkflow.Step step) {
        return switch (step) {
            case SpringBootWorkflow.LoadStep s -> Optional.of(s.result().symbol());
            case SpringBootWorkflow.CreateStep s -> Optional.of(s.result().symbol());
            case SpringBootWorkflow.ScalarQueryStep s -> Optional.of(s.result().symbol());
            default -> Optional.empty();
        };
    }
}
