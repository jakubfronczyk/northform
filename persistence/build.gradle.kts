// :persistence — SQLDelight (D7, D12): schema v1, the chunk codec and `SqlRecordingStore`. Lane C owns
// migrations (.sqm) from here on.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.sqldelight.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        iosMain.dependencies { implementation(libs.sqldelight.native.driver) }
        jvmMain.dependencies { implementation(libs.sqldelight.sqlite.driver) } // in-memory tests on the JVM
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":testSupport")) // RecordingStoreContract
        }
    }
}

// The green bar (`jvmTest`) runs the migration verification too, so a schema edit without a `.sqm` is red locally and in CI.
tasks.named("jvmTest") { dependsOn("verifySqlDelightMigration") }

sqldelight {
    databases {
        create("NorthformDb") {
            packageName.set("com.jakubfronczyk.northform.persistence")
            // Migrations (persistence/03). The `.sq` files stay the source of truth for a FRESH database; a
            // `.sqm` (`migrations/<n>.sqm`, "from version n to n+1") carries an existing database forward.
            // `generateCommonMainNorthformDbSchema` snapshots the current schema as `databases/<version>.db`
            // (committed); `verifySqlDelightMigration` replays the `.sqm` chain on every snapshot and fails
            // the build when the result differs from the `.sq` end state — the CI migration test.
            // `deriveSchemaFromMigrations` is left off: with it the `.sq` CREATEs would be ignored and the
            // schema read from the migration chain alone, which hides the end state from the reader.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
