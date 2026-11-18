# Third-Party Notices

This documents the source vendored into `target_teavm_263/vendor/` for the
TeaVM browser build (`./gradlew :target_teavm_263:build`), plus the patched
TeaVM classlib sources the build depends on the same way. None of these
directories are full upstream checkouts except `vendor/joml`; `vendor/guava`
and `vendor/gson` hold only the handful of files that replace what's stripped
out of the real jars (see `build.gradle.kts` — `patchGuava`, `patchGson`,
`patchClasslib`).

## vendor/joml — JOML (Java OpenGL Math Library)

- **Version:** 1.10.8 — matches `org.joml:joml:1.10.8` pinned in
  `game263/build.gradle.kts`. Vendored as source here (rather than the binary
  jar) because `org.joml` is excluded from dependency resolution for this
  module, so this tree is the only copy on the TeaVM classpath.
- **License:** MIT. Per-file headers, e.g. `Copyright (c) 2015-2024 Richard
  Greenlees`, `Copyright (c) 2022-2024 Kai Burjack`.
- **Contents:** 87 `.java` + 21 `.kt` files under `org/joml/` (upstream JOML
  ships both; the `.kt` files aren't compiled by this Java-only Gradle
  source set and are inert here).
- **Local patches:** one found — `org/joml/Options.java:102` changes the
  scientific-notation `DecimalFormat` pattern to a form marked in-file as
  "TeaVM-safe", because TeaVM's classlib `DecimalFormat` doesn't support the
  pattern JOML uses upstream. No other modifications found.

## vendor/guava — Guava (patch-only, not a full vendor copy)

- **Version:** 33.6.0-jre — matches `com.google.guava:guava:33.6.0-jre` in
  `build.gradle.kts`.
- **License:** Apache License 2.0 (`Copyright (C) 2007 The Guava Authors`).
- **Contents:** one file, `com/google/common/util/concurrent/
  AbstractFutureState.java`. The `patchGuava` task strips the binary
  `AbstractFutureState*.class` entries out of the real Guava jar
  (`build/patched-guava/guava-33.6.0-jre-patched.jar`) and this source
  compiles in their place.
- **Local patches:** replaces Guava's `VarHandle`/`sun.misc.Unsafe`-based
  future state machinery with a TeaVM-compatible path — `Unsafe` is a stub
  in this build (`src/main/java/sun/misc/Unsafe.java`, everything throws).

## vendor/gson — Gson (patch-only, not a full vendor copy)

- **Version:** 2.14.0 — matches `com.google.code.gson:gson:2.14.0` in
  `build.gradle.kts`.
- **License:** Apache License 2.0 (`Copyright (C) 2011/2020 Google Inc.`).
- **Contents:** four files, replacing classes the `patchGson` task strips
  out of the real Gson jar (`build/patched-gson/gson-2.14.0-patched.jar`):
  - `com/google/gson/internal/UnsafeAllocator.java`
  - `com/google/gson/internal/bind/TypeAdapters.java`
  - `com/google/gson/internal/sql/SqlTypesSupport.java`
  - `com/google/gson/internal/reflect/ReflectionHelper.java`
- **Local patches:** same rationale as guava — these reach for
  `sun.misc.Unsafe` or `java.sql` (not meaningfully present in TeaVM's
  classlib), so they're replaced wholesale rather than patched inline.
  `ReflectionHelper` additionally skips the Java-16 record probe
  (`Class.getMethod` on record APIs): TeaVM has no records, and that
  one call materializes the whole-program method metadata table at
  title (~70 MB). The twin installs Gson's own not-supported helper
  unconditionally.

## TeaVM classlib sources (Apache-2.0) — outside vendor/, included per audit scope

- **Location:** `src/main/java/org/teavm/classlib/**` — the 16 classes
  listed in `replacedClasslibClasses` in `build.gradle.kts` (`TSystem`,
  `TClass`, `TClassLoader`, `TInteger`, `TLong`, `TMath`, `TRuntime`,
  `TConcurrentHashMap`, `TStreamSupport`, `TField`, `TSpliterators`,
  `TBase64`, `TElementType`, `TUUID`, `TDate`, `TCharacter`).
- **Version:** `org.teavm:teavm-classlib:0.12.1-EAGLER-R3`, an Eagler fork
  of upstream TeaVM 0.12.1.
- **License:** Apache License 2.0 (`Copyright 2013 Alexey Andreev` and TeaVM
  contributors, per file headers).
- **Nature of patches:** these classes can't be overridden from the app
  classpath — the classlib jar wins at link time (see the build script
  comment above `replacedClasslibClasses`) — so the `patchClasslib` task
  strips their `.class` entries out of the real classlib jar
  (`build/patched-classlib/teavm-classlib-0.12.1-EAGLER-R3-patched.jar`) and
  these sources compile in their place instead. This audit did not diff each
  file against stock TeaVM 0.12.1; do that against the unpatched jar in the
  Gradle cache if a per-class diff is needed.

## Related browser stubs (original code, not third-party — noted for completeness)

- `src/main/java/sun/misc/Unsafe.java` — stub; every method throws
  `UnsupportedOperationException` so JOML/Guava/Gson's `Unsafe` probes fail
  and fall back to their pure-Java paths.
- `src/main/java/oshi/SystemInfo.java` — stub; oshi hardware introspection
  is unavailable in-browser and throws `UnsupportedOperationException`.
  `getCurrentPlatform()` returns `PlatformEnum.UNKNOWN`; the enum retains
  OSHI 6.9.0's identifiers and order for mod platform switches.
