# HAI Engine - preview

HAI is a rule engine for Hubitat: an alternative to Rule Machine, with a browser rule editor, an optional AI Rule Assistant, backups and restore, and an optional app (HAI Migration) that moves Rule Machine rules across one at a time.

**This is a preview build (`preview` branch).** It is for invited testers and security reviewers. Expect rough edges, and keep your Rule Machine rules until you trust the HAI copies.

- **Install:** [INSTALL.md](INSTALL.md). Through Hubitat Package Manager with this branch's manifest, or by hand from this branch (Code → Download ZIP).
- **User guide:** [docs/user-guide.md](docs/user-guide.md), with a short quick start in its appendix.
- **How HAI is built:** [docs/architecture-current.md](docs/architecture-current.md) (with a one-page picture, [docs/architecture-map.html](docs/architecture-map.html)), the Engine's internals in [docs/developer/architecture.md](docs/developer/architecture.md), every web endpoint and how it is protected in [docs/developer/api.md](docs/developer/api.md), and the rule and bundle formats in [docs/developer/rule-format.md](docs/developer/rule-format.md) and [docs/developer/bundle-format.md](docs/developer/bundle-format.md).
- **Moving rules from Rule Machine:** [docs/migration-guide.md](docs/migration-guide.md). Needs Automation Map 2.4.36 or later.
- **Reporting any problem, feedback or a question:** [open an issue](https://github.com/GordonThelander/HAI-Engine/issues/new/choose) and pick a form: Something is wrong, Feedback or a suggestion, or A question.
- **Reporting a security problem:** privately, never as a public issue. See [SECURITY.md](SECURITY.md).

Branches: `preview` now; `beta` and `prod` come later.
