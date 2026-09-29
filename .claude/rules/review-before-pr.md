# Independent review before a pull request

## When this applies

Before opening (or asking the user to open) a pull request that changes code, SQL, or `application*.yml`.
Docs-only or tooling-only changes may skip step 2.

## Process

1. Self-check the change against `docs/review-checklist.md`.
2. Run the `code-reviewer` agent (`.claude/agents/code-reviewer.md`) against `develop` in a separate context —
   not the context that wrote the change — and fix or explicitly answer every finding.
3. Put the reviewer's output into the PR description's "code-reviewer 결과" section. The human CODEOWNERS
   review is the third round.

Never claim the review was done without the reviewer's actual output.
