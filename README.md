# Dokinote

An offline-first, open-source Android document suite: PDF reader/editor, Office documents (Word first), and Obsidian-compatible notes. No ads, accounts, analytics or cloud lock-in.

**Status:** pre-alpha. Two independent tracks on separate branches (see `CLAUDE.md`):

| Branch | App | Dev builds |
|---|---|---|
| `track/pdf-notes` | `pdf-app` (`app.dokinote.pdf`) | pre-release `nightly-track-pdf-notes` |
| `track/office` | `office-app` (`app.dokinote.office`) | pre-release `nightly-track-office` |
| `main` | shared trunk | pre-release `nightly-main` |

Shared decisions: [docs/DECISIONS.md](docs/DECISIONS.md). Track handoffs: [docs/tracks/](docs/tracks/).

## Builds (no PC needed)
Every push builds with GitHub Actions. If a build fails, the log tail is on branch `ci-log-<branch>` (`latest.txt`). APKs are signed with the **public** development key `dev.keystore` so each build installs over the previous one; it is not a release key.

## Licence
AGPL-3.0-or-later. MuPDF is Copyright Artifex Software, Inc.
