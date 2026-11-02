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
            "Dual-prevention business sample"
    );

    private static final Map<String, Set<String>> EXPECTED_ALLOWED_DEPENDENCIES = Map.of(
            "Identity and authentication", Set.of(),
            "Tenancy", Set.of("Identity and authentication"),
            "Directory", Set.of("Identity and authentication", "Tenancy"),
            "Authorization", Set.of("Identity and authentication", "Tenancy", "Directory"),
            "Application registry", Set.of("Authorization", "Tenancy", "Identity and authentication"),
            "Workflow", Set.of("Authorization", "Identity and authentication", "Tenancy"),
            "Support services", Set.of("Authorization", "Tenancy"),
            "Audit and operations", Set.of("Authorization", "Identity and authentication", "Tenancy"),
            "File evidence access", Set.of(),
            "Dual-prevention business sample", Set.of(
                    "Application registry", "Audit and operations", "Authorization", "Directory",
                    "File evidence access", "Identity and authentication", "Support services", "Tenancy", "Workflow")
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
