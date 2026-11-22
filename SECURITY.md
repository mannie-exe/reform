# Security reporting

Report vulnerabilities through [GitHub private vulnerability reporting](https://github.com/mannie-exe/reform/security/advisories/new). Include the tested artifact, reproduction, affected authority/data boundary and expected behavior. Keep credentials, player data and exploit details out of public issues.

The supported release line is 0.1.x for Minecraft 1.21.1, NeoForge 21.1.265 and Java 21. Development snapshots are identified by their exact revision. Qualify a fix against the affected consumers and native versions.

## Automated checks and release policy

`mise run check:security` resolves separate Gradle inventories for the neutral core and every checked native target, retains the full HIGH/CRITICAL Trivy report, reviews dependency ownership, and scans source for secrets. The same policy runs for CI changes, release tags, the weekly schedule and explicit dispatch.

`security-policy.json` classifies exact reviewed Minecraft/loader package versions as provided-runtime advisories. Their findings remain in both the original scanner JSON and the ownership summary; they do not automatically block a re:form release. This is an accepted dependency-ownership boundary, not a claim that these vulnerabilities are unreachable or fixed. The library does not replace the host's dependencies.

Unclassified or re:form-owned HIGH/CRITICAL findings still block. Secrets and scanner, policy or incomplete-inventory failures also block. A new package/version cannot inherit a classification by group prefix or wildcard. Review the actual dependency owner when upgrading versions, adding an input boundary, changing supported targets or introducing a new use of an affected library. First-party vulnerabilities and deliberately selected host fixes retain their own engineering and verification requirements.

The GitHub security job retains the raw dependency report and classification summary, including on failure, and presents the dependency counts in its job summary. It does not upload raw secret matches as an artifact. `--exit-code 0` on the raw dependency scan permits collecting vulnerability findings; the subsequent ownership gate determines release failure. It does not waive scanner execution errors. Source secrets retain their own failure status. The local command stops at a failed check, so later checks are not implied to have run.

Renovate proposes dependency and SHA-pinned action updates for manual review. Changes to DFU, Gson or the native toolchain require the affected checks described in [publishing](docs/publishing.md). Keep build pins and runtime compatibility ranges separate.

## Guava applicability assessment

The Guava advisory concerns allocation while deserializing Java objects. re:form's input paths use explicit text formats and native codecs; its `readObject` hooks in local diagnostic exceptions immediately throw `NotSerializableException`. Source inspection found no Java object-deserialization entry point. This assessment covers re:form's inspected paths, not other code in the host process. [Guava advisory](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94).

The previous source policy suppressed the exact Guava 32.1.2-jre finding until 2027-02-20. The current `.trivyignore.yaml` is empty: both reviewed Guava versions are reported visibly under the provided-runtime policy. Their classification is separate from the source-based reachability assessment. Published 0.1.0 and its recorded policy remain immutable.

## Provided-runtime review

Assess the affected operation and actual input boundary before proposing an exception or dependency change. A source-based assessment for re:form does not certify other mods in the same JVM. Loader admission ranges and a successful native boot do not establish vulnerability remediation. Keep the scanner's original finding, package/version, primary advisory and tested runtime identity together; do not suppress an entire target or dependency family.

The native inventories include Netty compression, TLS and IP-filter findings, LZ4 decoder/native-extraction findings and Guava object-deserialization findings. These have different prerequisites. For example, Netty's [fragmented TLS ClientHello advisory](https://github.com/netty/netty/security/advisories/GHSA-c4c3-7fpv-j4q5) concerns SNI-based TLS routing, while its [LZ4 frame advisory](https://github.com/netty/netty/security/advisories/GHSA-mj4r-2hfc-f8p6) concerns a particular network decoder. re:form does not install either handler. That observation does not exclude host or third-party use.

Minecraft's packet compression uses zlib, but its region-file code also supports LZ4 block streams. Consequently, LZ4 findings cannot be dismissed because re:form does not directly import a decoder or because play packets use a different compression format. Review imported/saved-world input and the host's selected implementation separately. The [buffer-clearing advisory](https://github.com/yawkat/lz4-java/security/advisories/GHSA-cmp6-m4wj-q63q) and [native-library extraction advisory](https://github.com/yawkat/lz4-java/security/advisories/GHSA-mcr4-qmvw-px4g) also describe separate decoder and local-filesystem boundaries.

Keep host updates with their owner. Do not bundle a second Netty, Guava or LZ4 copy or force a game-wide version override. Further host remediation is deferred, separately selected work. A future change must qualify an actually compatible update or a bounded mitigation at the real use site. Preserve the advisory and its uncertainties until then.
