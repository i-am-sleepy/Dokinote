# Instructions for Claude (read first)

This repo holds two independent tracks, developed in separate chats on separate branches. Do not mix them until the user says to integrate.

| Branch | Track | Read |
|---|---|---|
| `track/pdf-notes` | PDF reader/editor + Obsidian-style notes | `docs/tracks/PDF_NOTES.md` |
| `track/office` | Office suite, Writer (.docx) first | `docs/tracks/OFFICE.md` |
| `main` | shared trunk (docs, CI, module shells) | `docs/DECISIONS.md` |

## Start-up checklist
1. `git branch --show-current`; read `docs/DECISIONS.md`, then your track file, then `status/<track>.md`.
2. Work only in your track's folders (listed in the track file). Shared files (root Gradle files, `gradle/libs.versions.toml`, `.github/**`, `README.md`, `docs/DECISIONS.md`, this file) change only when really needed, additively, and say so in your status file. Add dependencies in a block with your track's name in a comment to keep merges easy.
3. Build ONLY through GitHub Actions (the user is phone-only and the sandbox has no Android SDK; Maven/Google repos are not reachable from the sandbox, GitHub is). Push, then `GH_TOKEN=... tools/ci_wait.sh` prints the run result and the log tail from branch `ci-log-<branch with / replaced by ->`.
4. Dev APKs appear on the pre-release `nightly-<branch with / replaced by ->` (e.g. `nightly-track-office`). The PDF and Office apps have different applicationIds so both can be installed together.
5. Keep `status/<track>.md` current at every checkpoint: done, verified on the phone, untested, next.

## Rules
- Evidence first: label verified / inferred / untested. Never say a fix works until the user has tested it on the phone. Keep usage efficient.
- The user supplies a fine-grained GitHub token per chat. Never write it into files, commits, memory or logs. Push with `git -c http.extraHeader="Authorization: Basic $(printf 'x-access-token:%s' "$GH_TOKEN" | base64 -w0)" push ...`.
- Sandbox network allowlist includes github.com, api.github.com, raw.githubusercontent.com, pypi, npm; web_search/web_fetch work for research.
- Keep third-party attributions in `docs/THIRD_PARTY.md` (create it when you add the first dependency beyond MuPDF/AndroidX).
