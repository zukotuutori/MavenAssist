# Maven Assist

A tool window for IntelliJ-based IDEs that takes the tedium out of `pom.xml` files.

Point it at a directory. If there is no pom, Maven Assist helps you create one: from a form, or from a
template you saved earlier. If a pom is already there, it lists every dependency with its version and lets
you upgrade, add or remove them without leaving the IDE.

---

## Features

### Create a pom

Fill in the coordinates, optionally choose a parent pom, and tick the dependencies you want from a
searchable catalog. **Select All** takes everything the current search turned up. Leave *Always use the
newest version* checked and each dependency is written with its latest release from Maven Central.

### Reuse a setup with templates

Save a whole configuration: coordinates, parent pom, the newest-version setting and the ticked
dependencies: under a name, then apply it in any project with two clicks. Templates are created and
deleted from the same window and persist across IDE restarts.

### Manage an existing pom

Every dependency is listed with its version, including versions resolved from `${properties}`. Search the
table, or filter it down to *Only newest version* or *Only auto update*. **Check for Updates** fills in the
newest version for every row; **Update All** bumps the entire pom in one go.

Press **Update** beside a single version to choose the newest release or type a specific one. Maven Assist
rewrites the pom, runs `mvn compile`, and reports the result as a notification.

### Keep dependencies fresh automatically

Flag any dependency and it is checked against Maven Central every time you save the project, then bumped
if it has fallen behind.

### Add and remove dependencies

Adding is a two-step flow: pick what you need, then configure each dependency individually: newest
version, a pinned version, or auto-update. Removing is a red trash button per row, plus a multi-select mode
for clearing out several at once.

### Build your own catalog

Paste a `<dependency>…</dependency>` block from mvnrepository.com, or a plain `groupId:artifactId:version`
line, give it a name, and it appears in every dependency picker from then on: in all projects.

## Careful edits

Maven Assist rewrites your pom with targeted text replacements rather than reformatting the file, so your
indentation and comments survive untouched. `${property}` versions are updated at their definition, and
entries inside `<dependencyManagement>` or `<profiles>` are never modified when you update or delete a
dependency from the project's own `<dependencies>` block.

---

## Requirements

- IntelliJ IDEA, or another IntelliJ-based IDE, **2025.1 or newer**.
- Maven, but you do not have to install it yourself. Maven Assist uses your project's `mvnw` wrapper if
  there is one, otherwise `mvn` from your `PATH`, otherwise the copy bundled with your IDE. If none of
  those exist, it offers to install Maven for you.

---

## Privacy

Maven Assist contacts exactly two places, and only when you ask it to:

- **`search.maven.org`** — to look up the newest version of a dependency. The only data sent is the
  `groupId` and `artifactId` being looked up.
- **`dlcdn.apache.org` / `archive.apache.org`** — to download Maven, if you accept the offer to install it.

There is no telemetry and no analytics. Your templates, dependency catalog and layout are stored locally in
your IDE's configuration directory.

---

## Licence

Released under the [MIT Licence](LICENSE).
