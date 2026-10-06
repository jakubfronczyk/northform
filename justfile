# Northform tasks. `just` with no argument lists them. Every recipe explains its flags inline.

default:
    @just --list

# One-time: install pinned tools, then generate the Gradle wrapper (writes gradlew + gradle-wrapper.jar).
bootstrap:
    mise install
    gradle wrapper --gradle-version 9.2.0 --distribution-type bin
    ./gradlew --version

# The green bar: pure-module tests on the JVM (what CI runs on ubuntu). Fast, no Xcode.
test:
    ./gradlew :core:jvmTest :engine:jvmTest

# Kotlin/Native tests in the iOS simulator (the boot-order test lives here, D9).
test-sim:
    ./gradlew :app:iosSimulatorArm64Test

# Link the iOS framework without Xcode — proves the Kotlin side builds for device.
link:
    ./gradlew :app:linkDebugFrameworkIosArm64

# Regenerate the Xcode project from iosApp/project.yml (the .xcodeproj is gitignored).
project:
    cd iosApp && xcodegen generate

# Open in Xcode. Then: select the Northform scheme + your iPhone 16e, ⌘R.
open: project
    open iosApp/Northform.xcodeproj

# D16 seam rule: Swift may reference only the three allowed Kotlin symbols and no platform decision APIs.
check-seam:
    tools/check-seam.sh
