# Contributing

Use [README](README.md) for prerequisites and [development](docs/development/README.md) for build and ownership details. There is no stable public API yet. Discuss a change that alters serialized meaning, authority, supported platforms or licensing before depending on it.

Implement against the pinned artifacts. Keep common/core signatures free of client and optional-mod types. Introduce public extension contracts through independently compiled consumers; do not require private implementation access. Preserve existing consumer settings and native callbacks during extraction.

Before a commit:

1. Run `mise run format` for changed Java/Markdown.
2. Run `mise run check` and relevant native checks for affected claims.
3. Review the diff, generated metadata and distribution contents.
4. Update the owning documentation and verification limits.

Never commit game runs, secrets, downloaded dependency binaries or decompiled sources. The checksum-verified Gradle wrapper JAR is intentionally tracked. Use conventional commit subjects with proportional detail.

Reports should identify the tested version, physical side, relevant settings, reproduction and expected/observed behavior. Report sensitive vulnerabilities through [private reporting](SECURITY.md).
