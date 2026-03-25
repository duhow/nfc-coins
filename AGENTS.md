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
- At release time, fastlane automatically copies `default.txt` to `<versionCode>.txt`. Do **not** create numbered files manually.
