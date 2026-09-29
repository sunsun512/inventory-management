# Database migrations

- Migration files are managed under `src/main/resources`.
- Never modify a migration file that has already been committed; add a new migration instead.
- **Before writing or running any destructive change — `DROP TABLE`, `DROP COLUMN`, `TRUNCATE`, or anything else that deletes tables, columns, or data — stop and get explicit confirmation from the user.** Explain what will be dropped and what data could be lost, and proceed only after the user approves.
