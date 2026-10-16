# Installing the HAI preview, version 0.5.87-dev

HAI is a rule engine for Hubitat. This folder holds everything a preview tester needs. You do not need
Python, a command line or a copy of the source code: only a web browser and your hub's own pages.

What is in this folder:

- `apps/` - three pieces of app code: `hai_engine_app.groovy` (HAI Engine), `hai_rule_app.groovy`
  (HAI Rule Container, one per rule, added by HAI itself) and `hai_migration_app.groovy` (HAI Migration,
  optional, for moving Rule Machine rules).
- `file-manager/` - five files HAI's pages need, already named as the hub must hold them.
- `VERSION` - the version these files belong to. Every file here must come from the same version.

You need a Hubitat hub on firmware 2.4 or later. On a preview build the app names end in "(Preview)".

## The easy way: Hubitat Package Manager

If you have Hubitat Package Manager installed:

1. Open **Hubitat Package Manager**, then **Package Manager Settings**.
2. Under **Add a Custom Repository**, enter
   `https://raw.githubusercontent.com/GordonThelander/HAI-Engine/preview/repository.json` and press **Save**.
3. Back on the main page, press **Install**, then **Browse by Tags** or **Search**, and choose **HAI Engine (Preview)**.
4. Tick **HAI Migration** too if you will move Rule Machine rules. Package Manager installs the apps, turns
   on OAuth and puts the five files in File Manager.
5. Carry on at step 5 below (Add HAI Engine). Updates then come through Package Manager like any other package.

Without Package Manager, install by hand with steps 1 to 5.

## 1. Download

1. Open the preview repository on GitHub and switch to the **preview** branch.
2. Press **Code**, then **Download ZIP**.
3. Unzip it on your computer and open the folder `HAI-Engine-preview`.

## 2. Add the app code

Do this three times, once for each file in `apps/`.

1. On your hub, open **Apps Code** in the menu (under **Developer tools** on some firmware).
2. Press **New App**.
3. Open the `.groovy` file in a text editor, select all of it, copy it, and paste it into the hub's page.
4. Press **Save**.

## 3. Turn on OAuth for HAI Engine

1. In **Apps Code**, open **HAI Engine**.
2. Press **OAuth**, then **Enable OAuth in App**, then **Update**. Press **Save** again.

HAI Engine needs this for its editor page. Do the same for **HAI Migration** if you will move Rule Machine
rules. HAI Rule Container needs no OAuth.

## 4. Upload the five files

1. Open **Settings**, then **File Manager**.
2. Upload each file from `file-manager/`. Keep the names exactly as they are:
   - `hai-editor.html`
   - `hai-migration.html`
   - `hai-author-guide.txt`
   - `hai-editor-specs.json`
   - `hai-am-capabilities.json`

HAI checks each file against the app code it came with. A file from another version is refused, and the
page that needs it will not open.

## 5. Add HAI Engine

1. Open **Apps**, then press **Add User App**.
2. Choose **HAI Engine**.
3. Look through **Approved devices**: a new install selects every device. Untick any device HAI must not use.
4. Press **Done**.

HAI Engine now appears under Apps. Open it and press **Open the rule editor** to write your first rule.

## 6. Optional: moving rules from Rule Machine

1. Install Automation Map 2.4.36 or later, and let it scan your hub.
2. Open **HAI Engine** and press **Add HAI Migration**.
3. Open **Rule Machine Migration Facility** from the HAI Engine page.

## 7. Optional: the AI Rule Assistant

The AI Rule Assistant drafts a rule from your own words, using Claude. It needs your own Claude API key,
and each draft costs a little.

1. Get a key at platform.claude.com.
2. On the HAI Engine page, open **AI token and cost management**.
3. Paste the key, set a spending limit, and press **Test the API key**.

Everything else in HAI works without a key.

## Updating to a new preview

1. Download the new preview as in step 1.
2. In **Apps Code**, open each of the three apps, select all the old code, paste the new code over it, and press **Save**.
3. Upload the five files again in **File Manager**, replacing the old ones.

Your rules, their history and your settings are kept.

## Uninstalling

1. Open **Apps**, open **HAI Engine**, and press **Remove**. This removes every HAI rule and HAI Migration with it.
2. In **Apps Code**, delete HAI Migration, HAI Rule Container and HAI Engine.
3. In **File Manager**, delete the five `hai-` files above.

HAI's nightly rule backups (`hai-backup-<date>.json`) stay in File Manager until you delete them.

## Help

Report a problem, feedback or a question at https://github.com/GordonThelander/HAI-Engine/issues/new/choose, with the version from `VERSION`. Report security problems privately, as `SECURITY.md` describes. Turning on **Turn diagnostics
on for an hour** on the HAI Engine page, then repeating what went wrong, records more detail for the report.
