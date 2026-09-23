# Never commit or push to `develop` or `master`

- Never run `git commit` or `git push` while on `develop` or `master`, and never push to either branch from another branch (e.g. `git push origin HEAD:develop`).
- Before committing, check the current branch with `git branch --show-current`. If it is `develop` or `master`, create a feature branch first (existing convention: `feature/IM<n>-<short-description>`).
- Changes reach `develop`/`master` only through pull requests.
