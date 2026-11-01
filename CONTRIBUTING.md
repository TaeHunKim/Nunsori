# Contributing

1. Set up and build: [docs/getting-started.md](docs/getting-started.md). Tests and scripts: [docs/development.md](docs/development.md).
2. Run `./gradlew assembleDebug testDebugUnitTest` before sending a change; run `connectedDebugAndroidTest` for UI, DB or pipeline changes (it uninstalls the app).
3. UI strings go in both `values/strings.xml` and `values-ko/strings.xml` with the same keys.
4. **Update the docs in the same change.** Map of change to document:

| Change | Update |
|---|---|
| New feature, screen, setting | `README.md`, `README.ko.md`, `docs/user-guide.md` |
| Data model, pipeline, packages | `docs/architecture.md`, `docs/indexing.md` |
| Ranking, thresholds, weights | `docs/search.md` |
| Models, dependencies, licenses | `docs/models.md`, `THIRD_PARTY_NOTICES.md` |
| Permissions, network, stored data | `docs/privacy.md` |
| Build, scripts, tests | `docs/getting-started.md`, `docs/development.md` |
| Plans, limits | `docs/roadmap.md` |

5. Never commit models, test photos/audio/video, databases or build output. Generate fixtures with the scripts.
6. Commit messages: short imperative summary line, then a body explaining why.

Contributions are licensed under Apache-2.0 ([LICENSE](LICENSE)).
