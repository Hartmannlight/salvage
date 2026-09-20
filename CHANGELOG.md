# Changes

## Backup safety audit

- Scope global (`g:`) volume mappings independently of Compose; reject ambiguous unscoped mappings.
- Treat configured exit-code ranges as failures; accept both documented `stop` and legacy `fail`.
- Restore every prepared container, continue after individual rollback errors, and report post-hook failures.
- Track Docker mutations before sending them, so a lost response still allows rollback.
- On shutdown, cancel queued backups and wait for active cranes to stop before restarting applications.
- Collect crane exit status before removal; always clean up cranes and callback streams.
- Reject zero/negative worker counts and restore the compatible SLF4J 2 logging backend.
- Build and test the exact Docker candidate natively on amd64 and arm64 before publishing a shared index.

Run `./gradlew test` for unit regressions. On a disposable Linux Docker daemon, build with
`./gradlew jibDockerBuild -Djib.from.platforms=linux/arm64 -Djib.to.image=salvage:test`
(use `amd64` on x86), then run
`SALVAGE_TEST_DOCKER=isolated python3 tests/docker_safety.py salvage:test`.
The Docker suite uses generated fixtures, including failing hooks, immediate crane exit,
read-only sources, exact restoration, multi-container rollback and shutdown during a backup.

Use `ghcr.io/hartmannlight/salvage:master` to receive these fixes. Existing labels remain compatible.
Allow sufficient Docker stop grace time for crane cleanup and application restart; abrupt host failure
or SIGKILL cannot execute cleanup. With Borg, also upgrade the crane and read its retention migration notes.
