# Data Source Boundary

This repository keeps application UI, local assets, domain models, Agent orchestration, and public data-source interfaces in source control.

The public `oss` flavor does not include:

- third-party data dumps
- private SCAPI request implementation
- batch synchronization scripts for protected data
- backend secrets, long-lived tokens, or private endpoint-specific security logic

The `full` flavor may install private data-source implementations at build time. Private implementations must preserve these rules:

- request online data only from user-triggered actions
- do not crawl or prefetch full datasets
- cache detail responses with a short local cooldown
- keep session, ticket, nonce, and sequence handling inside the private implementation
- never expose backend credentials or long-lived secrets in the APK
