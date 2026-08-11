package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ActorIdentityRequirement;
import io.kcg.sir.lowering.springboot.model.ActorIdentityTransportPlan;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.ScalarKind;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.ApplicationMain;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class ApplicationRenderer {
   private static final String SPRING_BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication";
   private static final String MAPPER_SCAN = "org.mybatis.spring.annotation.MapperScan";
   private static final String SPRING_APPLICATION = "org.springframework.boot.SpringApplication";
   private static final String BEST_MATCHING_PATTERN_ATTRIBUTE = "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

   private ApplicationRenderer() {
   }

   static GeneratedFile render(ApplicationMain app) {
      String packageName = app.packageName();
      ImportSorter imports = new ImportSorter();
      imports.add("org.springframework.boot.autoconfigure.SpringBootApplication");
      imports.add("org.mybatis.spring.annotation.MapperScan");
      imports.add("org.springframework.boot.SpringApplication");
      Optional<ActorIdentityTransportPlan> planOpt = app.actorIdentityTransportPlan();
      String body;
      if (planOpt.isEmpty()) {
         body = renderLegacyBody(app);
      } else {
         ActorIdentityTransportPlan plan = planOpt.get();
         addActorTransportImports(imports, app, plan);
         body = renderActorTransportBody(app, plan);
      }

      String content = GenerationContext.assembleSource(packageName, imports, body);
      return new GeneratedFile(app.path(), content, app.id(), Optional.empty());
   }

   private static String renderLegacyBody(ApplicationMain app) {
      StringBuilder out = new StringBuilder();
      out.append("@SpringBootApplication\n");
      out.append("@MapperScan(").append(StringEscape.javaString(app.mapperScanPackage())).append(")\n");
      out.append("public class ").append(app.simpleName()).append(" {\n\n");
      out.append("    public static void main(String[] args) {\n");
      out.append("        SpringApplication.run(").append(app.simpleName()).append(".class, args);\n");
      out.append("    }\n");
      out.append("}\n");
      return out.toString();
   }

   private static void addActorTransportImports(ImportSorter imports, ApplicationMain app, ActorIdentityTransportPlan plan) {
      imports.add("jakarta.servlet.http.HttpServletRequest");
      imports.add("jakarta.servlet.http.HttpServletResponse");
      imports.add("org.springframework.beans.factory.ListableBeanFactory");
      imports.add("org.springframework.beans.factory.SmartInitializingSingleton");
      imports.add("org.springframework.context.annotation.Bean");
      imports.add("org.springframework.core.Ordered");
      imports.add("org.springframework.core.env.Environment");
      imports.add("org.springframework.core.env.Profiles");
      imports.add("org.springframework.web.servlet.HandlerInterceptor");
      imports.add("org.springframework.web.servlet.config.annotation.InterceptorRegistration");
      imports.add("org.springframework.web.servlet.config.annotation.InterceptorRegistry");
      imports.add("org.springframework.web.servlet.config.annotation.WebMvcConfigurer");
      imports.add("java.util.HashSet");
      imports.add("java.util.List");
      imports.add("java.util.Map");
      imports.add("java.util.Objects");
      imports.add("java.util.Optional");
      imports.add("java.util.Set");
      String domainPackage = app.packageName() + ".domain";
      Set<String> entityJavaNames = new LinkedHashSet<>();

      for (ActorIdentityRequirement requirement : plan.requirements()) {
         entityJavaNames.add(requirement.actorEntityJavaName());
      }

      for (String javaName : entityJavaNames) {
         imports.add(domainPackage + "." + javaName);
      }

      for (ActorIdentityRequirement requirement : plan.requirements()) {
         String importFor = scalarBoxedImport(requirement.identityStorageType().kind());
         if (importFor != null) {
            imports.add(importFor);
         }
      }
   }

   private static String scalarBoxedImport(ScalarKind kind) {
      return switch (kind) {
         case BIG_DECIMAL -> "java.math.BigDecimal";
         case UUID -> "java.util.UUID";
         case LOCAL_DATE -> "java.time.LocalDate";
         case INSTANT -> "java.time.Instant";
         default -> null;
      };
   }

   private static String scalarBoxedName(ScalarKind kind) {
      return switch (kind) {
         case BIG_DECIMAL -> "BigDecimal";
         case UUID -> "UUID";
         case LOCAL_DATE -> "LocalDate";
         case INSTANT -> "Instant";
         case BOOLEAN -> "Boolean";
         case INTEGER -> "Integer";
         case LONG -> "Long";
         case STRING -> "String";
         case VOID -> "Void";
      };
   }

   private static String scalarBoxedClassLiteral(ScalarKind kind) {
      String name = scalarBoxedName(kind);
      return name + ".class";
   }

   private static String renderActorTransportBody(ApplicationMain app, ActorIdentityTransportPlan plan) {
      StringBuilder out = new StringBuilder();
      String simpleName = app.simpleName();
      out.append("@SpringBootApplication\n");
      out.append("@MapperScan(").append(StringEscape.javaString(app.mapperScanPackage())).append(")\n");
      out.append("public class ").append(simpleName).append(" {\n\n");
      renderMainMethod(out, simpleName);
      renderRequirementsField(out, plan);
      renderActorIdentityAdapter(out);
      renderActorIdentityContext(out);
      renderRequirementEntry(out);
      renderActorIdentityRuntime(out, plan);
      renderActorIdentityInterceptor(out, plan);
      renderActorIdentityWebMvcConfigurer(out, plan);
      renderBeanMethods(out);
      out.append("}\n");
      return out.toString();
   }

   private static void renderMainMethod(StringBuilder out, String simpleName) {
      out.append("    public static void main(String[] args) {\n");
      out.append("        SpringApplication.run(").append(simpleName).append(".class, args);\n");
      out.append("    }\n\n");
   }

   private static void renderRequirementsField(StringBuilder out, ActorIdentityTransportPlan plan) {
      out.append("    private static final List<RequirementEntry> REQUIREMENTS = List.of(\n");
      List<ActorIdentityRequirement> reqs = plan.requirements();

      for (int i = 0; i < reqs.size(); i++) {
         ActorIdentityRequirement req = reqs.get(i);
         String sep = i == reqs.size() - 1 ? ");\n\n" : ",\n";
         out.append("            new RequirementEntry(\n");
         out.append("                    ").append(StringEscape.javaString(req.httpMethod().name())).append(",\n");
         out.append("                    ").append(StringEscape.javaString(req.route())).append(",\n");
         out.append("                    ").append(StringEscape.javaString(req.attributeName())).append(",\n");
         out.append("                    ").append(req.actorEntityJavaName()).append(".class,\n");
         out.append("                    ").append(scalarBoxedClassLiteral(req.identityStorageType().kind())).append(")");
         out.append(sep);
      }
   }

   private static void renderActorIdentityAdapter(StringBuilder out) {
      out.append("    public interface ActorIdentityAdapter {\n");
      out.append("        Optional<?> resolveAuthenticatedIdentity(\n");
      out.append("                HttpServletRequest request,\n");
      out.append("                ActorIdentityContext context);\n");
      out.append("    }\n\n");
   }

   private static void renderActorIdentityContext(StringBuilder out) {
      out.append("    public record ActorIdentityContext(\n");
      out.append("            String httpMethod,\n");
      out.append("            String route,\n");
      out.append("            Class<?> actorEntityType,\n");
      out.append("            Class<?> identityType) {\n");
      out.append("    }\n\n");
   }

   private static void renderRequirementEntry(StringBuilder out) {
      out.append("    record RequirementEntry(\n");
      out.append("            String httpMethod,\n");
      out.append("            String route,\n");
      out.append("            String attributeName,\n");
      out.append("            Class<?> actorEntityType,\n");
      out.append("            Class<?> identityType) {\n");
      out.append("    }\n\n");
   }

   private static void renderActorIdentityRuntime(StringBuilder out, ActorIdentityTransportPlan plan) {
      String modeProperty = plan.modeProperty();
      String externalBean = plan.externalAdapterBeanName();
      String localProfile = plan.localProfile();
      String localIdProperty = plan.localIdentityProperty();
      String msg001 = "KCG-ACTOR-STARTUP-001: property " + modeProperty + " is missing or blank";
      String msg003 = "KCG-ACTOR-STARTUP-003: external mode requires exactly one ActorIdentityAdapter bean named '" + externalBean + "'";
      String msg004a = "KCG-ACTOR-STARTUP-004: local-fixed mode requires profile '" + localProfile + "' to be active";
      String msg004b = "KCG-ACTOR-STARTUP-004: local-fixed mode must not coexist with an external ActorIdentityAdapter bean";
      String msg005a = "KCG-ACTOR-STARTUP-005: local-fixed mode requires a single actor entity domain and identity type";
      String msg005b = "KCG-ACTOR-STARTUP-005: property '" + localIdProperty + "' is missing or blank";
      out.append("    public static class ActorIdentityRuntime implements SmartInitializingSingleton {\n");
      out.append("\n");
      out.append("        private final Environment env;\n");
      out.append("        private final ListableBeanFactory beanFactory;\n");
      out.append("        private volatile ActorIdentityAdapter adapter;\n");
      out.append("        private volatile Object localFixedIdentity;\n");
      out.append("\n");
      out.append("        public ActorIdentityRuntime(Environment env, ListableBeanFactory beanFactory) {\n");
      out.append("            this.env = Objects.requireNonNull(env, \"env\");\n");
      out.append("            this.beanFactory = Objects.requireNonNull(beanFactory, \"beanFactory\");\n");
      out.append("        }\n");
      out.append("\n");
      out.append("        @Override\n");
      out.append("        public void afterSingletonsInstantiated() {\n");
      out.append("            String mode = env.getProperty(").append(StringEscape.javaString(modeProperty)).append(");\n");
      out.append("            if (mode == null || mode.isBlank()) {\n");
      out.append("                throw new IllegalStateException(\n");
      out.append("                        ").append(StringEscape.javaString(msg001)).append(");\n");
      out.append("            }\n");
      out.append("            if (!\"external\".equals(mode) && !\"local-fixed\".equals(mode)) {\n");
      out.append("                throw new IllegalStateException(\n");
      out.append("                        \"KCG-ACTOR-STARTUP-002: unknown actor identity mode: \" + mode);\n");
      out.append("            }\n");
      out.append("            Map<String, ActorIdentityAdapter> adapterBeans =\n");
      out.append("                    beanFactory.getBeansOfType(ActorIdentityAdapter.class);\n");
      out.append("            boolean adapterPresent = !adapterBeans.isEmpty();\n");
      out.append("            if (\"external\".equals(mode)) {\n");
      out.append("                if (adapterBeans.size() != 1\n");
      out.append("                        || !adapterBeans.containsKey(").append(StringEscape.javaString(externalBean)).append(")) {\n");
      out.append("                    throw new IllegalStateException(\n");
      out.append("                            ").append(StringEscape.javaString(msg003)).append(");\n");
      out.append("                }\n");
      out.append("                this.adapter = adapterBeans.get(\"").append(externalBean).append("\");\n");
      out.append("            } else {\n");
      out.append("                if (!env.acceptsProfiles(Profiles.of(\"").append(localProfile).append("\"))) {\n");
      out.append("                    throw new IllegalStateException(\n");
      out.append("                            ").append(StringEscape.javaString(msg004a)).append(");\n");
      out.append("                }\n");
      out.append("                if (adapterPresent) {\n");
      out.append("                    throw new IllegalStateException(\n");
      out.append("                            ").append(StringEscape.javaString(msg004b)).append(");\n");
      out.append("                }\n");
      out.append("                Set<Class<?>> entityTypes = new HashSet<>();\n");
      out.append("                Set<Class<?>> identityTypes = new HashSet<>();\n");
      out.append("                for (RequirementEntry entry : REQUIREMENTS) {\n");
      out.append("                    entityTypes.add(entry.actorEntityType());\n");
      out.append("                    identityTypes.add(entry.identityType());\n");
      out.append("                }\n");
      out.append("                if (entityTypes.size() != 1 || identityTypes.size() != 1) {\n");
      out.append("                    throw new IllegalStateException(\n");
      out.append("                            ").append(StringEscape.javaString(msg005a)).append(");\n");
      out.append("                }\n");
      out.append("                String localId = env.getProperty(\"").append(localIdProperty).append("\");\n");
      out.append("                if (localId == null || localId.isBlank()) {\n");
      out.append("                    throw new IllegalStateException(\n");
      out.append("                            ").append(StringEscape.javaString(msg005b)).append(");\n");
      out.append("                }\n");
      out.append("                Class<?> identityType = identityTypes.iterator().next();\n");
      out.append("                try {\n");
      out.append("                    this.localFixedIdentity = parseLocalId(localId, identityType);\n");
      out.append("                } catch (RuntimeException ex) {\n");
      out.append("                    throw new IllegalStateException(\n");
      out.append("                            \"KCG-ACTOR-STARTUP-005: cannot parse property '")
         .append(localIdProperty)
         .append("' as \" + identityType.getName(), ex);\n");
      out.append("                }\n");
      out.append("                if (!identityType.equals(this.localFixedIdentity.getClass())) {\n");
      out.append("                    throw new IllegalStateException(\n");
      out.append("                            \"KCG-ACTOR-STARTUP-005: parsed local identity runtime type does \"\n");
      out.append("                                    + \"not exactly match \" + identityType.getName());\n");
      out.append("                }\n");
      out.append("            }\n");
      out.append("        }\n");
      out.append("\n");
      out.append("        ActorIdentityAdapter adapter() {\n");
      out.append("            return adapter;\n");
      out.append("        }\n");
      out.append("\n");
      out.append("        Object localFixedIdentity() {\n");
      out.append("            return localFixedIdentity;\n");
      out.append("        }\n");
      out.append("\n");
      out.append("        private static Object parseLocalId(String value, Class<?> type) {\n");
      out.append("            if (type == Long.class) return Long.valueOf(value);\n");
      out.append("            if (type == Integer.class) return Integer.valueOf(value);\n");
      out.append("            if (type == String.class) return value;\n");
      out.append("            if (type == java.util.UUID.class) return java.util.UUID.fromString(value);\n");
      out.append("            if (type == java.math.BigDecimal.class) return new java.math.BigDecimal(value);\n");
      out.append("            throw new IllegalStateException(\"unsupported identity type: \" + type);\n");
      out.append("        }\n");
      out.append("    }\n\n");
   }

   private static void renderActorIdentityInterceptor(StringBuilder out, ActorIdentityTransportPlan plan) {
      out.append("    public static class ActorIdentityInterceptor implements HandlerInterceptor {\n");
      out.append("\n");
      out.append("        private final ActorIdentityRuntime runtime;\n");
      out.append("\n");
      out.append("        public ActorIdentityInterceptor(ActorIdentityRuntime runtime) {\n");
      out.append("            this.runtime = Objects.requireNonNull(runtime, \"runtime\");\n");
      out.append("        }\n");
      out.append("\n");
      out.append("        @Override\n");
      out.append("        public boolean preHandle(\n");
      out.append("                HttpServletRequest request,\n");
      out.append("                HttpServletResponse response,\n");
      out.append("                Object handler) {\n");
      out.append("            String method = request.getMethod();\n");
      out.append("            Object patternAttr = request.getAttribute(\"")
         .append("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern")
         .append("\");\n");
      out.append("            String pattern = patternAttr == null ? null : patternAttr.toString();\n");
      out.append("            RequirementEntry entry = lookup(method, pattern);\n");
      out.append("            if (entry == null) {\n");
      out.append("                return true;\n");
      out.append("            }\n");
      out.append("            request.removeAttribute(entry.attributeName());\n");
      out.append("            ActorIdentityAdapter adapter = runtime.adapter();\n");
      out.append("            Object identity;\n");
      out.append("            if (adapter != null) {\n");
      out.append("                Optional<?> resolved;\n");
      out.append("                try {\n");
      out.append("                    resolved = adapter.resolveAuthenticatedIdentity(\n");
      out.append("                            request,\n");
      out.append("                            new ActorIdentityContext(\n");
      out.append("                                    entry.httpMethod(),\n");
      out.append("                                    entry.route(),\n");
      out.append("                                    entry.actorEntityType(),\n");
      out.append("                                    entry.identityType()));\n");
      out.append("                } catch (RuntimeException ex) {\n");
      out.append("                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);\n");
      out.append("                    return false;\n");
      out.append("                }\n");
      out.append("                if (resolved == null || resolved.isEmpty() || resolved.get() == null) {\n");
      out.append("                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);\n");
      out.append("                    return false;\n");
      out.append("                }\n");
      out.append("                identity = resolved.get();\n");
      out.append("            } else {\n");
      out.append("                identity = runtime.localFixedIdentity();\n");
      out.append("            }\n");
      out.append("            if (identity == null || !entry.identityType().equals(identity.getClass())) {\n");
      out.append("                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);\n");
      out.append("                return false;\n");
      out.append("            }\n");
      out.append("            request.setAttribute(entry.attributeName(), identity);\n");
      out.append("            return true;\n");
      out.append("        }\n");
      out.append("\n");
      out.append("        private static RequirementEntry lookup(String method, String pattern) {\n");
      out.append("            if (method == null || pattern == null) {\n");
      out.append("                return null;\n");
      out.append("            }\n");
      out.append("            for (RequirementEntry entry : REQUIREMENTS) {\n");
      out.append("                if (entry.httpMethod().equals(method) && entry.route().equals(pattern)) {\n");
      out.append("                    return entry;\n");
      out.append("                }\n");
      out.append("            }\n");
      out.append("            return null;\n");
      out.append("        }\n");
      out.append("    }\n\n");
   }

   private static void renderActorIdentityWebMvcConfigurer(StringBuilder out, ActorIdentityTransportPlan plan) {
      out.append("    public static class ActorIdentityWebMvcConfigurer implements WebMvcConfigurer {\n");
      out.append("\n");
      out.append("        private final ActorIdentityInterceptor interceptor;\n");
      out.append("\n");
      out.append("        public ActorIdentityWebMvcConfigurer(ActorIdentityInterceptor interceptor) {\n");
      out.append("            this.interceptor = Objects.requireNonNull(interceptor, \"interceptor\");\n");
      out.append("        }\n");
      out.append("\n");
      out.append("        @Override\n");
      out.append("        public void addInterceptors(InterceptorRegistry registry) {\n");
      List<String> routes = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();

      for (ActorIdentityRequirement requirement : plan.requirements()) {
         if (seen.add(requirement.route())) {
            routes.add(requirement.route());
         }
      }

      out.append("            InterceptorRegistration registration = registry.addInterceptor(interceptor);\n");
      if (routes.size() == 1) {
         out.append("            registration.addPathPatterns(").append(StringEscape.javaString(routes.get(0))).append(");\n");
      } else {
         out.append("            String[] routes = {\n");

         for (int i = 0; i < routes.size(); i++) {
            String suffix = i == routes.size() - 1 ? "\n" : ",\n";
            out.append("                    ").append(StringEscape.javaString(routes.get(i))).append(suffix);
         }

         out.append("            };\n");
         out.append("            registration.addPathPatterns(routes);\n");
      }

      out.append("            registration.order(Ordered.HIGHEST_PRECEDENCE);\n");
      out.append("        }\n");
      out.append("    }\n\n");
   }

   private static void renderBeanMethods(StringBuilder out) {
      out.append("    @Bean\n");
      out.append("    ActorIdentityRuntime actorIdentityRuntime(Environment env, ListableBeanFactory beanFactory) {\n");
      out.append("        return new ActorIdentityRuntime(env, beanFactory);\n");
      out.append("    }\n\n");
      out.append("    @Bean\n");
      out.append("    ActorIdentityInterceptor actorIdentityInterceptor(ActorIdentityRuntime runtime) {\n");
      out.append("        return new ActorIdentityInterceptor(runtime);\n");
      out.append("    }\n\n");
      out.append("    @Bean\n");
      out.append("    ActorIdentityWebMvcConfigurer actorIdentityWebMvcConfigurer(ActorIdentityInterceptor interceptor) {\n");
      out.append("        return new ActorIdentityWebMvcConfigurer(interceptor);\n");
      out.append("    }\n");
   }
}
