Doma Tools
========================================

Doma Tools is an Eclipse plugin for Doma. 

This fork adds navigation from DAO method calls to SQL using the existing
`Doma > Jump to SQL File` command, keeps SQL files in step with DAO rename and
move refactorings, and fixes an out-of-memory crash when many SQL files change
at once (see [Changes in this fork](#changes-in-this-fork)).

Doma Tools does the following:

1. cross navigation between Java and SQL files.
2. triggering the annotation processing when saving SQL files.
3. renaming and moving SQL files together with DAO refactorings.

Changes in this fork
--------

### DAO-call navigation

`Doma > Jump to SQL File` also works on DAO method calls and method references
in other Java classes. See [How to use](#how-to-use).

### SQL files follow DAO rename and move refactorings

Doma looks up SQL files by path:
`META-INF/<package path>/<DAO name>/<method name>.sql`. Previously, renaming a
DAO method or moving a DAO left its SQL files behind, and Doma then reported
the SQL as missing (and the old file as unmapped, DOMA4220) until you moved the
files by hand. The Eclipse refactorings now take the SQL files along:

| Refactoring (`Refactor > Rename...` / `Refactor > Move...`) | What happens to the SQL files |
|---|---|
| Rename a DAO method | `selectById.sql` → `findById.sql`, including suffixed files such as `selectById-oracle.sql` and `.script` files |
| Rename a DAO interface | `META-INF/.../EmployeeDao/` → `META-INF/.../StaffDao/` |
| Move a DAO to another package | `META-INF/example/dao/EmployeeDao/` → `META-INF/example/repository/EmployeeDao/` |
| Rename a package containing DAOs | each DAO folder moves to `META-INF/<new package path>/` |

Details:

- Only interfaces annotated with Doma's `@Dao` (`org.seasar.doma.Dao`,
  resolved through the imports) are handled; a `@Dao` annotation from another
  library is ignored. Files are matched by exact method name or `<method>-`
  prefix, so `selectByIdAndName.sql` is not touched when `selectById` is
  renamed.
- SQL files stay in the source folder they are in (for example
  `src/main/resources` or `src/main/java`). Folders under `META-INF` that a
  move leaves empty are removed.
- The changes appear in the refactoring preview and are undone together with
  the refactoring (`Edit > Undo`). Undo removes only the folders that the
  refactoring created; folders that existed before are kept.
- If a destination file or folder already exists, or a file is where a
  destination folder must be created, the refactoring stops with an error
  before changing anything, so the Java code and the SQL files never get out
  of step.
- Moving a DAO to another project is not handled, even to a package of the
  same name; the refactoring shows a warning and the SQL files must be moved
  manually.

### Fix: out-of-memory crash when many SQL files change at once

**Symptom.** After a Git branch switch, pull or refresh that changed hundreds
of SQL files, Eclipse showed *"An out of memory error has occurred"* and became
unstable. The workspace `.metadata/.log` contained
`java.lang.OutOfMemoryError: unable to create native thread`, and a thread dump
showed thousands of `Worker-N: building <DaoClass>` threads blocked on the
workspace lock. This is a thread exhaustion problem (macOS allows 4,096 threads
per process); increasing `-Xmx` does not help.

**Cause.** The original SQL-change trigger scheduled one job per changed SQL
file. The jobs had no scheduling rule, so Eclipse started a worker thread for
each of them, and each one re-saved the DAO through the editor buffer and
started its own project build.

**Fix.** `ResourceFileChangeListener` now:

- collects changed SQL and script files and handles them in a single,
  coalescing job (scheduled after 500 ms), so a burst of changes produces one
  job instead of one job per file;
- touches each affected DAO source file once, however many of its SQL files
  changed; the Java builder then recompiles the DAO and the Doma annotation
  processor validates it again;
- runs that job with the workspace-root scheduling rule, so it waits in the
  job queue rather than holding a thread blocked on the workspace lock;
- leaves the build to Eclipse when auto build is on, and builds each affected
  project once when auto build is off;
- ignores marker-only changes and derived resources such as `target/classes`.

The listener is registered only for `POST_CHANGE` events.

**Behavior change.** The DAO is no longer saved through its editor buffer, so
unsaved edits in an open DAO editor are no longer saved implicitly. If the DAO
editor has unsaved changes when its SQL changes, Eclipse may report that the
file changed on the file system.

Install this fork
--------

Update site: **https://vipt.github.io/doma-tools/**

1. In Eclipse, open `Help > Install New Software... > Add...`.
2. Enter `Doma Tools` as the name and the update-site URL above as the location.
3. Select **Doma > Doma Tools**, review the installation and license, then install
   and restart Eclipse.

Use `Help > Check for Updates` for subsequent updates. This fork uses the same
bundle and feature IDs as the original Doma Tools and updates that installation.

The [Marketplace listing](https://marketplace.eclipse.org/content/doma-tools)
is for the upstream project. Use the update-site URL above to install this fork,
including its DAO-call navigation enhancement.

Settings
--------

**Depending on the edition of Eclipse you are using, this setting may not be necessary.**

You must open the SQL file in Eclipse to use the Doma tools.
Therefore, it is necessary to set up file associations.

1. Select `Eclipse > Preference…` or `Window > Preference` from menu bar and open preference dialog.
2. Associate `.sql` to `Text Ecitor`.
3. Associate `.script` to `Text Ecitor`.

![sql-file-association](images/sql-file-association.png)
![script-file-association](images/script-file-association.png)

How to use
-------

### When calling a Dao from another Java class

- Place the cursor on the DAO method name, for example `selectById` in
  `employeeDao.selectById(id)`, then select `Doma > Jump to SQL File`.
  The command resolves the called method and opens its SQL file. Method
  references such as `employeeDao::selectById` also work.
- Unsaved Java editor changes and DAO source in referenced workspace projects
  are supported. SQL lookup and the missing-file creation wizard use the
  existing Doma Tools behavior.

### When a Dao interface is opened in the Java Editor or viewed in Outline View

- Select a method, right-click, and select `Doma > Jump to SQL File` to jump to an SQL file. 
  If the file does not exist, a dialog box will open for creating a new file.

### When an SQL file is opened in the Text Editor or viewed in Package Explorer View

- Right-click, and select `Doma > Jump to Dao` to jump to a Dao interface.

Build and install this fork
-------

Use JDK 17 and Maven 3.8.6 (the version verified with this Tycho 3 build):

```sh
mvn --batch-mode --no-transfer-progress verify
```

This compiles the original plug-in, runs navigation tests in a headless Eclipse
runtime, and builds an installable p2 repository. The build uses the pinned
Eclipse 2023-12 target; the plug-in retains its original Java 6 source level.

In Eclipse select `Help > Install New Software... > Add... > Archive...`, then
choose `org.seasar.doma.extension.updatesite/target/org.seasar.doma.extension.updatesite-2.0.0-SNAPSHOT.zip`.
Install **Doma Tools** and restart Eclipse. This fork uses the original bundle
and feature IDs, so it updates the existing Doma Tools installation. Its
version follows the upstream source's `2.0.0` version and has a build qualifier.
An additional Doma Result Generator plug-in is not required for this command.

The generated directory `org.seasar.doma.extension.updatesite/target/repository/`
can also be served directly by a static web host. It includes p2 metadata, the
feature and plug-in JARs, and an installation guide at `index.html`.

Publishing the update site
-------

The **Build and publish Eclipse update site** GitHub Actions workflow runs the
build and tests for pull requests and pushes to `master`. After a successful
build on `master`, it publishes only the generated p2 repository to GitHub Pages.
The workflow can also be started manually from the Actions tab on `master`.
CI pins Maven to 3.8.6 because the legacy Tycho 3.0.5 build fails to initialize
with the runner's Maven 3.10.0. The Maven download is verified with Apache's
published SHA-512 checksum.

The repository's `Settings > Pages > Build and deployment > Source` must be set
to **GitHub Actions**. The published URL for this repository is
`https://vipt.github.io/doma-tools/`. If you fork the repository again, update the
URLs in this README and in
`org.seasar.doma.extension.updatesite/src/main/resources/index.html` for your own
GitHub account and repository.

Validation
-------

Tests cover calls, caret and range selections, declarations and their fallback,
method references, unsaved working copies, referenced projects, scripts,
missing SQL, and unrelated methods. They exercise the actual handler and
existing SQL factory with real Java projects. `ResourceFileChangeListenerTest`
checks that 60 separate SQL changes across 3 DAOs produce at most one job and
touch every DAO, that only the DAO of a changed SQL file is touched, and that
marker-only changes schedule no job. `DaoRefactoringParticipantTest` runs the
real JDT refactorings with reference updates and checks DAO method rename
(with suffixed files and a similarly named file left alone), DAO rename, DAO
move, package rename and its undo, that undo keeps folders that existed
before, rejection when the destination SQL file exists or a file blocks the
destination folder, the warning for a move to another project, and that
non-DAO interfaces and another library's `@Dao` are ignored. The JDT UI
plug-in cannot start without a display, so the test uses JDT core working
copy buffers and sets the import-order defaults that import rewriting reads.
Interactive acceptance checks:

1. On a service's DAO call, select `Doma > Jump to SQL File` and confirm the
   expected SQL opens. Repeat on the DAO method declaration and with an
   unsaved caller.
2. Repeat with a DAO in a referenced Java project and with a `@Script` method.
3. For a missing SQL, confirm the existing creation wizard opens and can be
   canceled. On a non-DAO service method, confirm no SQL is opened.
4. Save an SQL file and check the existing annotation-processing trigger;
   check `Doma > Jump to Dao` still opens the DAO.
5. Switch to a Git branch that changes many SQL files and confirm the Progress
   view shows a single "Rebuilding Doma DAOs for changed SQL files" job and that
   Eclipse stays responsive.
6. Rename a DAO method with `Refactor > Rename`, check the preview lists the SQL
   file rename, then confirm no DOMA errors or DOMA4220 warnings appear. Undo
   it, and repeat for renaming and moving a DAO and renaming its package.

License
-------

Apache License, Version 2.0
