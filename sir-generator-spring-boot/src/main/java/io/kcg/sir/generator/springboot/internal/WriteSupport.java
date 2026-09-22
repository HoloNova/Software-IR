package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import java.util.Optional;

/**
 * The write slice's supporting artifacts, resolved once for the renderers that need them.
 *
 * <p>The error envelope, the exception base, the advice, and the constraint helpers are emitted by
 * lowering only when a capability writes through a payload; every renderer therefore asks this class
 * instead of assuming they exist.
 */
final class WriteSupport {
    private final ProjectArtifact.ApiErrorResponse errorResponse;
    private final ProjectArtifact.ApiExceptionBase exceptionBase;
    private final ProjectArtifact.ApiExceptionAdvice advice;
    private final ProjectArtifact.ValidationSupport validationSupport;

    private WriteSupport(
            ProjectArtifact.ApiErrorResponse errorResponse,
            ProjectArtifact.ApiExceptionBase exceptionBase,
            ProjectArtifact.ApiExceptionAdvice advice,
            ProjectArtifact.ValidationSupport validationSupport
    ) {
        this.errorResponse = errorResponse;
        this.exceptionBase = exceptionBase;
        this.advice = advice;
        this.validationSupport = validationSupport;
    }

    static WriteSupport of(SpringBootLoweredModel model) {
        return new WriteSupport(
                first(model, ProjectArtifact.ApiErrorResponse.class, "the error envelope"),
                first(model, ProjectArtifact.ApiExceptionBase.class, "the declared failure base"),
                first(model, ProjectArtifact.ApiExceptionAdvice.class, "the failure advice"),
                // Candidate checks exist only where a payload writes an entity, so the constraint
                // primitives are the one artifact this slice may legitimately lack.
                model.projectArtifacts().stream()
                        .filter(ProjectArtifact.ValidationSupport.class::isInstance)
                        .map(ProjectArtifact.ValidationSupport.class::cast)
                        .findFirst()
                        .orElse(null));
    }

    private static <T extends ProjectArtifact> T first(SpringBootLoweredModel model, Class<T> type, String description) {
        return model.projectArtifacts().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the generated project requires " + description));
    }

    boolean present(SpringBootLoweredModel model) {
        return model.projectArtifacts().stream().anyMatch(ProjectArtifact.ApiErrorResponse.class::isInstance);
    }

    String errorResponseFqn() {
        return this.errorResponse.packageName() + "." + this.errorResponse.simpleName();
    }

    String fieldErrorFqn() {
        return this.errorResponseFqn() + "." + this.errorResponse.fieldErrorSimpleName();
    }

    String exceptionBaseFqn() {
        return this.exceptionBase.packageName() + "." + this.exceptionBase.simpleName();
    }

    String validationSupportFqn() {
        if (this.validationSupport == null) {
            throw new IllegalStateException("candidate checks require the validation support artifact");
        }

        return this.validationSupport.packageName() + "." + this.validationSupport.simpleName();
    }

    String invalidRequestCode() {
        return this.errorResponse.invalidRequestCode();
    }

    String errorResponseSimpleName() {
        return this.errorResponse.simpleName();
    }

    String fieldErrorSimpleName() {
        return this.errorResponse.fieldErrorSimpleName();
    }

    Optional<ProjectArtifact.ApiErrorResponse> errorResponse() {
        return Optional.of(this.errorResponse);
    }

    ProjectArtifact.ApiExceptionAdvice advice() {
        return this.advice;
    }
}
