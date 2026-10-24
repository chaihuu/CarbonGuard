/*
 * CarbonGuard
 * Copyright (C) 2026 chaihuu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-FileCopyrightText: 2026 chaihuu
 * SPDX-License-Identifier: GPL-3.0-only
 */

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "xyz.chaihu"
version = "1.2.0"
description = "CarbonGuard - Paper 26.2 access control with GPL-3.0-only licensing"

repositories {
    // Vendored bStats artifacts (MIT) keep builds reproducible without adding
    // another public repository dependency. See THIRD_PARTY_NOTICES.md.
    maven { url = uri("libs/bstats-repo") }
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/maven-releases/") {
        content {
            includeGroup("org.geysermc")
            includeGroup("org.geysermc.floodgate")
            includeGroup("org.geysermc.geyser")
            includeGroup("org.geysermc.api")
            includeGroup("org.geysermc.cumulus")
            includeGroup("org.geysermc.event")
            includeGroup("org.cloudburstmc.math")
        }
    }
    maven("https://repo.opencollab.dev/maven-snapshots/") {
        content {
            includeGroup("org.geysermc")
            includeGroup("org.geysermc.floodgate")
            includeGroup("org.geysermc.geyser")
            includeGroup("org.geysermc.api")
            includeGroup("org.geysermc.cumulus")
            includeGroup("org.geysermc.event")
            includeGroup("org.cloudburstmc.math")
        }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.134-stable")

    // Optional integrations. Never bundled: the plugin guards every entry point
    // at runtime and degrades gracefully when neither plugin is installed.
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    compileOnly("org.geysermc.geyser:api:2.12.0-SNAPSHOT")

    // Paper 26.2 supplies SLF4J 2.x; do not shade a second copy into the plugin.
    implementation("com.zaxxer:HikariCP:7.1.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("org.xerial:sqlite-jdbc:3.53.4.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("com.sun.mail:jakarta.mail:2.0.2")
    // Anonymous metrics. Relocated below so it cannot clash with another plugin's copy.
    implementation("org.bstats:bstats-bukkit:2.2.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.14.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Only needed because the shaded plugin intentionally excludes Paper's SLF4J.
    testRuntimeOnly("org.slf4j:slf4j-api:2.0.17")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
    // The distributed jar carries the complete license and third-party notices.
    from(rootProject.file("LICENSE"))
    from(rootProject.file("COPYING"))
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("LICENSES")) {
        into("LICENSES")
    }
}

val licenseMarker = "SPDX-License-Identifier: GPL-3.0-only"

tasks.register("verifyLicenseHeaders") {
    group = "verification"
    description = "Verifies GPL-3.0-only SPDX headers and the complete license text."
    doLast {
        val missing = fileTree("src") {
            include("**/*.java")
        }.filter { file ->
            !file.readText(Charsets.UTF_8).contains(licenseMarker)
        }
        if (missing.files.isNotEmpty()) {
            throw GradleException("Missing ${licenseMarker} in: ${missing.files.joinToString()}")
        }
        val licenseFile = rootProject.file("LICENSE")
        if (!licenseFile.isFile || !licenseFile.readText(Charsets.UTF_8)
                .contains("GNU GENERAL PUBLIC LICENSE")) {
            throw GradleException("Complete GPLv3 text is missing from LICENSE")
        }
    }
}

tasks.check {
    dependsOn("verifyLicenseHeaders")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName = "CarbonGuard"
    archiveClassifier = ""
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    relocate("org.bstats", "xyz.chaihu.carbonguard.libs.bstats")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // Dependency license texts are shipped explicitly via LICENSE / LICENSES.
    exclude("META-INF/LICENSE*", "META-INF/NOTICE*")
    manifest {
        attributes(
            "Implementation-Title" to "CarbonGuard",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "chaihuu",
            "License" to "GPL-3.0-only",
            "Source" to "https://github.com/chaihuu/CarbonGuard"
        )
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
