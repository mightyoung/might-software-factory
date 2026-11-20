package com.mightyoung.softwarefactory;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleArchitectureTest {

    private static final Set<String> EXPECTED_MODULES = Set.of(
            "Identity and authentication",
            "Tenancy",
            "Directory",
            "Authorization",
            "Application registry",
            "Workflow",
            "Support services",
            "Audit and operations",
            "File evidence access",
            "Dual-prevention business sample",
            "Platform administration",
            "Entity ledger",
            "Tooling catalog"
    );

    private static final Map<String, Set<String>> EXPECTED_ALLOWED_DEPENDENCIES = Map.ofEntries(
            Map.entry("Identity and authentication", Set.of()),
            Map.entry("Tenancy", Set.of("Identity and authentication")),
            Map.entry("Directory", Set.of("Identity and authentication", "Tenancy")),
            Map.entry("Authorization", Set.of("Identity and authentication", "Tenancy", "Directory")),
            Map.entry("Application registry", Set.of("Authorization", "Tenancy", "Identity and authentication")),
            Map.entry("Workflow", Set.of("Authorization", "Identity and authentication", "Tenancy")),
            Map.entry("Support services", Set.of("Authorization", "Tenancy", "Identity and authentication")),
            Map.entry("Audit and operations", Set.of("Authorization", "Identity and authentication", "Tenancy")),
            Map.entry("File evidence access", Set.of()),
            Map.entry("Dual-prevention business sample", Set.of(
                    "Application registry", "Audit and operations", "Authorization", "Directory",
                    "File evidence access", "Identity and authentication", "Support services", "Tenancy", "Workflow")),
            Map.entry("Platform administration", Set.of("Application registry", "Authorization", "Identity and authentication", "Tenancy")),
            Map.entry("Entity ledger", Set.of("Application registry", "Authorization", "Identity and authentication", "Audit and operations")),
            Map.entry("Tooling catalog", Set.of("Workflow", "Entity ledger", "Authorization", "Identity and authentication", "Application registry", "Audit and operations"))
    );

    @Test
    void modulesHaveExplicitBoundariesAndNoForbiddenDependencies() throws ClassNotFoundException {
        ApplicationModules modules = ApplicationModules.of(SoftwareFactoryApplication.class);

        modules.verify();

        org.springframework.modulith.ApplicationModule identityAnnotation = Class.forName(
                "com.mightyoung.softwarefactory.identity.package-info")
                .getAnnotation(org.springframework.modulith.ApplicationModule.class);
        assertThat(identityAnnotation).isNotNull();
        assertThat(identityAnnotation.allowedDependencies()).isEmpty();

        org.springframework.modulith.ApplicationModule dualPreventionAnnotation = Class.forName(
                "com.mightyoung.softwarefactory.dualprevention.package-info")
                .getAnnotation(org.springframework.modulith.ApplicationModule.class);
        assertThat(dualPreventionAnnotation).isNotNull();
        assertThat(dualPreventionAnnotation.allowedDependencies())
                .contains("applicationregistry::query")
                .doesNotContain("applicationregistry");

        Set<String> actual = modules.stream()
                .map(ApplicationModule::getDisplayName)
                .collect(Collectors.toSet());

        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED_MODULES);

        Map<String, Set<String>> actualAllowedDependencies = modules.stream()
                .collect(Collectors.toMap(
                        ApplicationModule::getDisplayName,
                        module -> module.getAllowedDependencies(modules).stream()
                                .map(dependency -> dependency.getTargetModule().getDisplayName())
                                .collect(Collectors.toSet())));
        assertThat(actualAllowedDependencies).containsExactlyInAnyOrderEntriesOf(EXPECTED_ALLOWED_DEPENDENCIES);
    }
}
