package com.npdev.dsl.v1;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class RootBoundaryArchUnitTest {

    @Test
    void dslModuleDoesNotDependOnRuntimeEditorGeneratorOrGeneratedAppPackages() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.npdev.dsl.v1");

        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.finalexec..",
                        "com.npdev.editor..",
                        "com.npdev.generator..",
                        "com.npdev.generated.."
                )
                .because("NPDevContract must remain isolated from runtime, editor, generator, and generated-app code.")
                .check(classes);
    }
}
