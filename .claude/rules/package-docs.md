# Package docs: read before changing code, keep them current

## When this applies

When implementing or changing code (production Java code, SQL migrations, `application*.yml`) in the
`common`, `product` or `stock` packages. It does not apply to docs-only work (README, CLAUDE.md,
code comments, Swagger/OpenAPI text) or `.claude` / tooling changes.

## Rules

- Before changing code in a package, read `__workspace/README.md` and the matching
  `__workspace/<package>.md` (`common.md`, `product.md`, `stock.md`). Follow its 수정 패턴 and 실패 함정.
- When a change alters anything a package doc states (a new recipe or pitfall, a dependency change,
  a renamed or moved key file), update that doc in the same change so it never goes stale.
- If a doc contradicts the code, trust the code, fix the doc, and tell the user.
