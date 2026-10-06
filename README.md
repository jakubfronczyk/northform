# Northform

A personal training app for the Polar Verity Sense — built in **Kotlin Multiplatform** with a
**Compose Multiplatform** UI, iOS first (Android for free).

> **Status:** early. The design and build are in progress. This README becomes the portfolio
> front page once v1.0 lands (headline number → 15-second proof → architecture → run it).

## Why Kotlin Multiplatform

One Kotlin codebase — business logic *and* UI — shipping to iPhone and Android, with a small,
deliberate native seam only where each platform demands it (the lock-screen Live Activity, the
Polar BLE bridge, signing). The split follows the pattern KMP shops ship in production: shared
Kotlin logic, platform UI where it pays.

## Stack

Kotlin · Kotlin Multiplatform · Compose Multiplatform (iOS + Android) · coroutines/Flow ·
SQLDelight · Gradle · a thin native-Swift seam (Live Activity, Polar `@objc` bridge, app shell).

<!-- Architecture, the measured headline number, and "run it in 60 seconds" land here as the
     build progresses. Private planning lives in docs/plans/ (gitignored). -->
