package com.geovannycode.corteya

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter
import kotlin.test.assertEquals

class ModularityTests {
    @Test
    fun `feature modules have no cycles or accesses to internals and generate their documentation`() {
        val modules = ApplicationModules.of(CorteyaApplication::class.java).verify()
        assertEquals(setOf("barbershop", "catalog", "scheduling", "booking", "customer", "identity", "shared"),
            modules.map { it.identifier.toString() }.toSet())
        Documenter(modules, Documenter.Options.defaults().withOutputFolder("build/modulith-docs"))
            .writeDocumentation()
    }
}
