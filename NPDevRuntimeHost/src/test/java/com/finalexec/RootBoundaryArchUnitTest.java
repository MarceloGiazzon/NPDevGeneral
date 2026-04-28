package com.finalexec;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class RootBoundaryArchUnitTest {

    @Test
    void runtimeHostDoesNotDependOnEditorOrGeneratorPackages() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.finalexec");

        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.npdev.editor..",
                        "com.npdev.generator.."
                )
                .because("NPDevRuntimeHost must not couple to editor or generator implementation packages.")
                .check(classes);
    }
}
