# Vosk model — "Hey Acazen" wake word

This folder needs the actual **Vosk small English model** files, which aren't committed here
(they're ~40MB of binary acoustic-model data and don't belong in source control via this route —
see note at the bottom on an alternative if you'd rather commit them directly).

## Get the model

1. Download **`vosk-model-small-en-us-0.15.zip`** from https://alphacephei.com/vosk/models
   (look for "vosk-model-small-en-us-0.15", ~40MB, Apache 2.0 license).
2. Unzip it. You'll get a folder like `vosk-model-small-en-us-0.15/` containing subfolders such
   as `am/`, `conf/`, `graph/`, `ivector/` and a few top-level files.
3. Copy **the contents of that folder** (not the folder itself) directly into this directory
   (`AcazenApp/models/src/main/assets/model-en-us/`), so you end up with:

   ```
   models/src/main/assets/model-en-us/
     am/
     conf/
     graph/
     ivector/           (if present in that model version)
     README              (Vosk's own model README — fine to keep)
     ...
   ```

   Do **not** keep this file's name (`README.md`) once the real model is in place — Vosk's own
   model archive includes its own `README` (no extension) which is harmless to leave.

4. Delete this placeholder `README.md` once the real files are in place (optional, but avoids
   confusion — it has no effect on the build either way since it isn't referenced by anything).

## Why no `uuid` file to add yourself

Unlike some setups, you don't need to hand-create a `uuid` file — `models/build.gradle`'s
`genUUID` task generates one automatically into the build output on every build, and Vosk's
`StorageService` (used in `WakeWordService.kt`) uses it purely to detect "has the model on disk
changed since last unpack" — not something you manage by hand.

## If the whole model folder won't fit as a single commit

GitHub rejects individual files over 100MB, but no single file in the small model should hit
that (the largest is typically `am/final.mdl`, a few MB to low tens of MB depending on version).
If you hit size limits anyway, use [Git LFS](https://git-lfs.github.com/) for this folder, or
host the zip externally and have CI download + unzip it into this path before the Gradle build
runs (a `curl`/`unzip` step in your GitHub Actions workflow, right before `./gradlew assemble...`).
