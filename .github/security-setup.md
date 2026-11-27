# GitHub security setup notes (TASK-13)

Recorded 2026-11-27. "Verified" means the state was read back with `gh api`;
items without an API toggle are listed as manual steps below.

## Verified live via `gh api`

| Setting | Verified state | Read-back command |
| --- | --- | --- |
| Secret scanning | `enabled` | `gh api repos/lenucksi/TinyWeatherForecastGermany --jq .security_and_analysis.secret_scanning.status` |
| Secret scanning push protection | `enabled` | `gh api repos/lenucksi/TinyWeatherForecastGermany --jq .security_and_analysis.secret_scanning_push_protection.status` |
| Dependabot security updates | `enabled` | `gh api repos/lenucksi/TinyWeatherForecastGermany --jq .security_and_analysis.dependabot_security_updates.status` |
| Dependency graph / review API | available | `gh api repos/lenucksi/TinyWeatherForecastGermany/dependency-graph/compare/master...master` returned `[]` |
| `release` environment | exists | `gh api repos/lenucksi/TinyWeatherForecastGermany/environments/release` |
| Required reviewer on `release` | `lenucksi` | `.protection_rules[] | select(.type == "required_reviewers")` |
| Deployment branch policy on `release` | `master` only | `gh api repos/lenucksi/TinyWeatherForecastGermany/environments/release/deployment-branch-policies` |
| Renovate GitHub App | installed, not yet activated | `gh pr view 1 --repo lenucksi/TinyWeatherForecastGermany --jq .author.login` -> `app/renovate` |
| Renovate manager discovery | confirmed in onboarding PR | `gh pr view 1 --repo ... --jq .body` lists `build.gradle`, `app/build.gradle`, `settings.gradle`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties` and `build.yml` |

## Enforced by files in this change

- `.github/renovate.json`: weekly Renovate updates (`before 5am on monday`,
  UTC) for Gradle and GitHub Actions, digest pinning for Actions, dependency
  dashboard, `dependencies` label and `chore(deps):` commits. Validated with
  `npx --yes --package renovate@latest renovate-config-validator --strict`.
- `.github/workflows/codeql.yml`: CodeQL `java-kotlin` on push to `master`,
  pull requests to `master`, and a weekly schedule. Top-level permissions are
  `contents: read`; `security-events: write` is granted only to the CodeQL job.
- Same workflow runs `actions/dependency-review-action` on every pull request
  (the action fails the check when a PR introduces vulnerable dependencies).

## Manual TODOs (not verified as live by this task)

1. **Activate Renovate.** The Renovate App is installed but its onboarding PR
   #1 (`https://github.com/lenucksi/TinyWeatherForecastGermany/pull/1`) is still
   open, so no update PRs are being created yet. Renovate searches config
   filenames in order and stops at the first match, with root `renovate.json`
   ahead of `.github/renovate.json`. Therefore, **delete the root
   `renovate.json` from PR #1 before merging it** (or merge and then delete the
   root file), so this repo's `.github/renovate.json` is the single active
   config. Do not close PR #1 unmerged: that disables Renovate.
2. **Make dependency review blocking.** GitHub has no REST toggle for this.
   In the web UI: Settings -> Branches -> branch protection for `master` (or a
   ruleset) -> Require status checks to pass before merging -> add the
   `dependency-review` check. The check becomes selectable only after a PR has
   run the workflow at least once.
3. **Optional secret-scanning hardening.** Validity checks and non-provider
   patterns are currently `disabled`; enable them under Settings -> Advanced
   Security if the project wants them.
4. **Optional reviewer hardening.** The `release` environment has one reviewer
   (the repo owner) and `prevent_self_review: false`; add a second reviewer and
   enable `Prevent self-review` so releases cannot be self-approved.
5. **TASK-12 hand-off.** The release workflow must declare `environment:
   release` on its release/deploy jobs to inherit the protection rules above.

No tokens, secrets or credentials are stored in this file.
