# JEdit

Lightweight Java scratchpad for macOS. Type code, hit **⌘R** to compile and run.

## Install

Download `JEdit-<version>.dmg` from [Releases](../../releases).

> **macOS Gatekeeper warning** - "Apple could not verify JEdit is free of malware"
>
> JEdit is not notarized. To open it, run this once in Terminal after installing:
>
> ```bash
> xattr -rd com.apple.quarantine /Applications/JEdit.app
> ```
>
> Alternatively: right-click `JEdit.app` in Finder and choose **Open**, then click **Open** in the dialog.

## Build locally

Requires JDK 21+ and Maven:

```bash
bash build-dmg.sh
```
