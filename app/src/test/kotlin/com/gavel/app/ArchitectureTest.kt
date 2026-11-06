package com.gavel.app

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import kotlin.test.Test

/** Enforces the modular-monolith boundaries from requirement.md §7.3. */
class ArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.gavel")

    private val modules = listOf("onboarding", "catalog", "bidding", "settlement")

    @Test
    fun `domain code has no framework or persistence dependencies`() {
        noClasses().that().resideInAPackage("com.gavel.*.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("io.ktor..", "org.jetbrains.exposed..", "com.gavel.web..", "com.gavel..infrastructure..", "com.gavel..application..")
            .check(classes)
    }

    @Test
    fun `modules use only the api package of other modules`() {
        for (module in modules) {
            val others = modules.filter { it != module }
            val forbidden = others.flatMap { listOf("com.gavel.$it.domain..", "com.gavel.$it.application..", "com.gavel.$it.infrastructure..") }
            noClasses().that().resideInAPackage("com.gavel.$module..")
                .should().dependOnClassesThat().resideInAnyPackage(*forbidden.toTypedArray())
                .because("modules may depend only on another module's api package")
                .check(classes)
        }
    }

    @Test
    fun `api packages expose no implementation details`() {
        noClasses().that().resideInAPackage("com.gavel.*.api..")
            .should().dependOnClassesThat().resideInAnyPackage("io.ktor..", "org.jetbrains.exposed..", "com.gavel..infrastructure..", "com.gavel..application..", "com.gavel..domain..")
            .check(classes)
    }

    @Test
    fun `module dependencies are acyclic`() {
        slices().matching("com.gavel.(*)..").should().beFreeOfCycles().check(classes)
    }

    @Test
    fun `the shared kernel and web helpers stay free of business modules`() {
        noClasses().that().resideInAnyPackage("com.gavel.kernel..", "com.gavel.web..")
            .should().dependOnClassesThat().resideInAnyPackage(*(modules.map { "com.gavel.$it.." } + "com.gavel.app..").toTypedArray())
            .check(classes)
        noClasses().that().resideInAPackage("com.gavel.kernel..")
            .should().dependOnClassesThat().resideInAnyPackage("io.ktor..", "org.jetbrains.exposed..")
            .check(classes)
    }

    @Test
    fun `persistence stays inside each module's infrastructure`() {
        noClasses().that().resideInAnyPackage("com.gavel.*.api..", "com.gavel.*.application..", "com.gavel.*.domain..")
            .should().dependOnClassesThat().resideInAPackage("org.jetbrains.exposed..")
            .check(classes)
    }
}
