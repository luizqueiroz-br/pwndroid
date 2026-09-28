plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
}

// Agregadora: os subprojects penduram seus detekt aqui (task criada antes
// da configuração dos subprojects).
tasks.register("detektAll") {
    group = "verification"
    description = "Agrega o detekt de todos os módulos."
}

// Aplica detekt em todos os módulos e pendura em detektAll.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    the<io.gitlab.arturbosch.detekt.extensions.DetektExtension>().apply {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    }
}

rootProject.tasks.named("detektAll") {
    dependsOn(subprojects.map { it.tasks.named("detekt") })
}