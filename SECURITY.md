# Security reporting

Report vulnerabilities through [GitHub private vulnerability reporting](https://github.com/mannie-exe/reform/security/advisories/new). Include the tested artifact, reproduction, affected authority/data boundary and expected behavior. Keep credentials, player data and exploit details out of public issues.

The supported release line is 0.1.x for Minecraft 1.21.1, NeoForge 21.1.265 and Java 21. Development snapshots are identified by their exact revision. Qualify a fix against the affected consumers and native versions.

## Automated checks

`mise run check:security` runs Trivy against a disposable Gradle-generated inventory of the library's runtime and test dependencies, then scans source for secrets. The same checks run in CI for changes, release tags and a weekly schedule. HIGH and CRITICAL findings fail the check. These scans cover declared library dependencies and source, with native game and consumer integrations retaining their own security reviews.

Renovate proposes dependency and SHA-pinned action updates for review. Merges are manual. Changes to DFU, Gson or the native toolchain require the consumer and native checks described in [publishing](docs/publishing.md).

## Reviewed dependency exception

Trivy identifies CVE-2026-102554 in Guava 32.1.2-jre, inherited through DataFixerUpper and supplied by Minecraft 1.21.1. The advisory concerns allocation while deserializing Java objects. re:form's input paths use explicit text formats and codecs. Its `readObject` hooks in local diagnostic exceptions immediately throw `NotSerializableException`. Source inspection found no Java object-deserialization entry point. This is a source-based reachability assessment for re:form. Other code in the host process has its own input boundaries. [Guava advisory](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94).

The exact package/version exception in `.trivyignore.yaml` expires on 2027-02-20. Reassess it when changing serialization, DFU, Minecraft, or Guava. The generated inventory preserves the original resolved version, and new findings continue to fail CI.
