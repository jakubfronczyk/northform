---
status: accepted
date: 2026-10-08
---

# The Kotlin framework is dynamic and Xcode embeds and signs it

The Kotlin side ships as a dynamic framework. The Xcode build phase only builds it with Gradle and
copies it to a fixed path declared as the script's output; Xcode then links, embeds and signs it like
any other framework.

With a static framework, Xcode's linker records no dependency on the archive, so a change in Kotlin
alone did not trigger a relink and the phone ran the previous Kotlin until some Swift file changed.
Gradle's `embedAndSignAppleFrameworkForXcode` task avoids that but swaps the binary behind Xcode's
back and leaves the code signature stale.

## Consequences

- The app bundle carries a ~49 MB `Northform.framework`.

Revisit if: Kotlin's Swift export or Xcode's build system makes Kotlin-only changes relink a static
framework reliably.
