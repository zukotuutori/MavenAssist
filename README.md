# Maven Assist (IntelliJ Plugin)

A tool window plugin for IntelliJ-based IDEs that helps you manage `pom.xml` files.

## Features

- **Tool window** ("Maven Assist", right side of the IDE) with a directory chooser 
  pick any directory with the **folder button** beside the field (or type a path and
  press Enter); the whole workflow runs in that directory.
- **No pom.xml found?** Create one:
  - enter group id / artifact id / version,
  - optionally choose a **parent pom via path** ("Choose Path..."),
  - pick dependencies from the manager in a **searchable list** (rows show only the
    **name**; a **Details** button reveals coordinates and the newest version),
  - **"Always use the newest version"** is pre-selected versions are fetched live from Maven Central while creating.
- **pom.xml found?** You see **every dependency with its version**, and **Open** buttons
  next to the pom path (and the parent pom, when it resolves to a file) open them in the editor:
  - **Search bar and filters** above the table: type to search, tick **Only newest
    version** (already up to date) or **Only auto update** (flagged rows) to filter.
  - **Update** button next to each version: choose **Newest version** (from Maven Central)
    or **Custom version...** (type your own) the pom is rewritten (formatting preserved,
    `${property}` versions supported) and `mvn compile` runs.
  - **Red trash button** at the far right of every row deletes the dependency from the
    pom (with an "are you sure" confirmation). The trash button **next to the search bar**
    enters multi-select mode: tick several dependencies and delete them all at once.
  - **Check for Updates** fills the "Newest Version" column for all dependencies;
    **Update All** bumps every dependency in the pom to its newest version at once.
  - **Add Dependencies** is a **two-step flow**: step 1 is a **search bar** and a list
    showing **only the dependency names** each row has a **Details** button
    (group id, artifact id, catalog/pinned version, newest version). **Next** leads to
    step 2 with one block **per selected dependency**: use newest version, a specific
    version, and auto update, then **Add** inserts them into the pom and a build runs.
  - **Add Own to Manager...** (bottom of the Add Dependencies window): paste dependency
    XML (`<dependency>...</dependency>`, e.g. from mvnrepository.com) or plain
    `groupId:artifactId:version` lines, click **Add** they are stored **permanently**
    (application-level, survives IDE restarts), appear in all dependency lists from then
    on, and the picker list refreshes immediately. Each picker row also has a **red trash
    button** that deletes the dependency from the manager entirely (with confirmation),
    built-in catalog entries included.
- **Visually customizable plugin page** click the blue **Customize...** link in the
  bottom-left corner of the tool window and
  a **separate customize window** opens with a **live preview** of the whole page (top
  bar, dependency table with sample data, details-dialog buttons):
  - **Drag & drop** any button, it lifts off and sticks to the mouse as a ghost while an
    animated gap glides to the landing spot. Buttons can be moved **anywhere**: reordered
    within a section or across sections (top bar ↔ details dialog), and
    they keep working in their new home (e.g. *Update to Newest Version* in the main
    window acts on the selected table row).
  - **Resize by dragging**: grab a button's right edge to pull it wider, the bottom edge
    to make it taller, or the corner for both like resizing a window. Resized buttons
    keep their size; untouched ones keep filling the row.
  - **Click a button** in the preview to open a small floating editor for its **text,
    background/text color, width, height and font size** every change shows in the
    preview instantly.
  - **Visible fields** checkboxes decide for each dependency field (name, group id,
    artifact id, version, newest version, auto update) whether it appears **directly in
    the table** or **under "Details"**.
  - The real plugin window stays untouched while you experiment: **Save** applies the
    design to it and persists it across IDE restarts, **Cancel** discards, **Reset All
    to Defaults** restores the factory layout.
