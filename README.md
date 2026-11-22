# HAI Engine

HAI (Hubitat Automation Intelligence) is a modern rule engine for Hubitat that makes rules easier to build: start from prebuilt patterns for common automations, write rules in plain words, or (optionally) let AI draft one for you. Every rule is tested before it runs and keeps its own history.

- Rules read like sentences: triggers, conditions, actions. Drag lines to reorder them, add If / otherwise, and HAI checks the rule as you build it.
- Simulate a rule, or run a single line on the hub with **Run this step**. Rules go on the hub stopped and start when you say.
- Each rule runs as its own small app with its own run history.
- Nightly backups, plus **Back up now**. Restore one rule or all of them.
- Optional Rule Machine migration: copy a Rule Machine rule, run the copy alongside it to compare, and switch over or back in one click. Off until you turn it on.
- Works with Hubitat Automation Map (HAM), which shows HAI and Rule Machine rules side by side.
- Local by default. The optional AI Rule Assistant uses your own Claude API key with a spending limit.

## Install

Hubitat Package Manager: **Install**, then search for **HAI Engine**, or **From a URL**:

`https://raw.githubusercontent.com/GordonThelander/HAI-Engine/main/packageManifest.json`

Leave **Configure the installed package after installation completes** switched on. It takes 10 to 15 minutes while the hub compiles four apps. Requires firmware 2.4 or later. Full steps, including installing by hand, are in [INSTALL.md](INSTALL.md).

## Documentation

- [User guide](docs/user-guide.md)
- [Moving Rule Machine rules to HAI](docs/migration-guide.md)

## Help

Ask in the [community thread](https://community.hubitat.com/t/release-hubitat-automation-map/165524), use **More → Report a problem or give feedback** in the editor, or open an issue here. Report security problems privately, as [SECURITY.md](SECURITY.md) explains.
