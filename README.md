# JEdit

Lightweight Java scratchpad for macOS. Type code, hit **⌘R** to compile and run - no save required.

## Features

- Instant compile + run from an in-memory buffer
- Import suggestions as you type: unambiguous imports are added on the fly, ambiguous ones are highlighted - press ⌥↵ to pick
- `//DEPS group:artifact:version` for Maven dependencies (downloaded lazily on first use)
- Undo/redo, comment toggle (⌘/), duplicate line (⌘D), delete line (⌘⌫)
- Native-feeling light UI: unified toolbar, line numbers, live console output

## Install

Download `JEdit-<version>.dmg` from [Releases](../../releases), open it, drag JEdit to Applications.

No separate Java installation required - JRE is bundled.

## Dependency example

```java
//DEPS com.google.guava:guava:33.4.0-jre

import com.google.common.collect.ImmutableList;

public class Main {
    public static void main(String[] args) {
        var list = ImmutableList.of("a", "b", "c");
        list.forEach(System.out::println);
    }
}
```

## Build locally

Requires JDK 21+ and Maven:

```bash
bash build-dmg.sh
# DMG lands at target/jpackage-out/JEdit-1.0.0.dmg
```

## Project structure

```
src/main/java/
  Editor.java   - Swing GUI + compile/run orchestration
  Fixer.java    - JavaCompiler wrapper
  Imports.java  - class index and import suggestions
pom.xml         - Maven build (fat JAR via shade plugin)
build-dmg.sh    - jpackage wrapper to produce self-contained DMG
.github/workflows/release.yml  - CI: build + publish DMG on push to main
```
