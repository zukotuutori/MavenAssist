# Maven Assist

A tool window for IntelliJ-based IDEs that takes the tedium out of `pom.xml` files.

Point it at a directory. If there is no pom, Maven Assist helps you create one: from a form, or from a
template you saved earlier. If a pom is already there, it lists every dependency with its version and lets
you upgrade, add or remove them without leaving the IDE.

---

## Features

### Create a pom

Fill in the coordinates, optionally choose a parent pom, and tick the dependencies you want from a
searchable catalog. **Select All** takes everything the current search turned up. Each dependency's
**Version** button lets you use the newest stable release or pin a custom version.

### Reuse a setup with templates

Save a whole configuration: coordinates, parent pom, the newest-version setting and the ticked
dependencies: under a name, then apply it in any project with two clicks. Templates are created and
deleted from the same window and persist across IDE restarts.

### Manage an existing pom

Every dependency is listed with its version, including versions resolved from `${properties}`. Search the
table, or filter it down to *Only newest version* or *Only auto update*. **Check for Updates** fills in the
newest version for every row; **Update All** bumps the entire pom in one go.

Press **Update** beside a single version to choose the newest stable release or type a specific one. Maven Assist
rewrites the pom, runs `mvn compile`, and reports the result as a notification.

### Keep dependencies fresh automatically

Flag any dependency and it is checked against Maven Central at the moments enabled under **Customize**,
then bumped if a newer stable release exists. Save events are scoped to the current project, and all
automatic work is disabled while the project is in IntelliJ safe mode.

### Add and remove dependencies

Adding is a two-step flow: pick what you need, then configure each dependency individually: newest
version, a pinned version, or auto-update. Removing is a red trash button per row, plus a multi-select mode
for clearing out several at once.

### Build your own catalog

Paste a `<dependency>…</dependency>` block from mvnrepository.com, or a plain `groupId:artifactId:version`
line, give it a name, and it appears in every dependency picker from then on: in all projects.

## Careful edits

Maven Assist rewrites your pom with serialized, atomic targeted replacements rather than reformatting the
file, so indentation and comments survive. A version property is updated at its definition when only one
place uses it; when it is shared, only the selected dependency is changed. Dependencies with
different Maven types/classifiers are handled independently. Entries inside `<dependencyManagement>`,
`<profiles>`, `<build>` or `<reporting>` are never modified when you update or delete a dependency from
the project's own `<dependencies>` block.

---

## Requirements

- IntelliJ IDEA, or another IntelliJ-based IDE, **2025.1 or newer**.
- Maven, but you do not have to install it yourself. Maven Assist uses your project's `mvnw` wrapper if
  there is one, otherwise `mvn` from your `PATH`, otherwise the copy bundled with your IDE. If none of
  those exist, it offers to install Maven for you.

---

## Privacy

Maven Assist itself makes direct network requests only for a feature you invoke or a dependency you
explicitly flag for automatic update:

- **`search.maven.org`**: when checking updates, choosing **Newest**, opening version details,
  creating/adding a dependency configured for newest, or running an enabled auto-update pass. The
  `groupId` and `artifactId` are sent; private coordinates can therefore reveal internal naming.
  Merely opening or refreshing the tool window sends nothing, and custom pinned versions are not checked.
- **`dlcdn.apache.org` / `archive.apache.org`**: to download Maven, if you accept the offer to install it.

Maven, Homebrew and winget are separate tools and may use their configured repositories when Maven Assist
launches them. There is no telemetry or analytics. Templates, the dependency catalog and layout remain
local to this IDE installation; project state stores relative paths rather than usernames/home paths.

---

## Licence

Released under the [MIT Licence](LICENSE).
