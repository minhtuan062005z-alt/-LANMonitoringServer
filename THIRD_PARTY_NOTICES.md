# Third-party dependencies

The binary dependencies in `lib/` are included to keep the NetBeans/Ant project reproducible.

| Component | Version | License | Purpose |
|---|---:|---|---|
| OSHI (`oshi-core`, `oshi-common`) | 7.7.2 | MIT | Cross-platform hardware/OS metrics |
| JNA (`jna-jpms`, `jna-platform-jpms`) | 5.19.1 | LGPL-2.1-or-later or Apache-2.0 | Native access used by OSHI |
| JFreeChart | 1.5.6 | LGPL-2.1-or-later | Swing performance charts |
| SLF4J API + NOP provider | 2.0.20 | MIT | Logging facade used by OSHI |

Downloaded artifacts were verified with the publisher-provided SHA-256 checksum where available. The OSHI distribution archive was verified against the digest in its GitHub release metadata.
