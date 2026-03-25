# Agents Notes

## Commits

Use Conventional Commits in PR title. Common types are feat, fix, chore, refactor, perf, ci, docs.
If using scope, limit scope to two-word max, connected with dash.
Check historic commits to avoid making up new scopes every time.

## Development best practices

- Keep changes focused and minimal to the issue being solved.
- Reuse existing patterns and naming conventions in this codebase.
- Validate changes with available Gradle tasks before opening/merging (`assembleDebug` at minimum).
- If touching UI text, keep user-facing wording clear and consistent.
- Avoid introducing new dependencies unless absolutely necessary.

## IMPORTANT: Layout changes

- `activity_main.xml` exists in **both**:
  - `app/src/main/res/layout/activity_main.xml` (portrait/default)
  - `app/src/main/res/layout-land/activity_main.xml` (landscape/horizontal)
- Any layout modification to this screen **must be applied in both files**.
- Treat portrait and landscape as a paired change to avoid regressions/crashes in horizontal mode.

## Changelogs

- The active changelog lives at `fastlane/metadata/android/en-US/changelogs/default.txt`.
- **Update `default.txt` whenever you make user-facing changes** (features, fixes, improvements).
- Keep entries concise and written from the user's perspective (e.g. `- Fixed crash when tapping a reset card`).
- Numbered files (e.g. `1.txt`, `2.txt`) are permanent per-version snapshots committed to the repo and must not be edited after creation.

### Before creating a release tag

Since tags are created directly in GitHub, the changelog snapshot must be committed **before** the tag is pushed. Follow these steps:

1. Ensure `default.txt` contains all changes for the upcoming release.
2. Run `bundle exec fastlane prepare_release` — this copies `default.txt` to `<nextVersionCode>.txt` and resets `default.txt` to a placeholder for the next release.
3. Commit both files (`git add fastlane/metadata/android/en-US/changelogs/ && git commit`) and push.
4. Create the release tag in GitHub — the CI pipeline will pick up the versioned changelog automatically.
