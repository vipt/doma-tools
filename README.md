Doma Tools
========================================

Doma Tools is an Eclipse plugin for Doma. 

This fork adds navigation from DAO method calls to SQL using the existing
`Doma > Jump to SQL File` command.

Doma Tools does the following:

1. cross navigation between Java and SQL files.
2. triggering the annotation processing when saving SQL files.

Marketplace
--------

https://marketplace.eclipse.org/content/doma-tools

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

Use JDK 17 and Maven 3.8.6 or later:

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

Tests cover calls, caret and range selections, declarations and their fallback,
method references, unsaved working copies, referenced projects, scripts,
missing SQL, and unrelated methods. They exercise the actual handler and
existing SQL factory with real Java projects. Interactive acceptance checks:

1. On a service's DAO call, select `Doma > Jump to SQL File` and confirm the
   expected SQL opens. Repeat on the DAO method declaration and with an
   unsaved caller.
2. Repeat with a DAO in a referenced Java project and with a `@Script` method.
3. For a missing SQL, confirm the existing creation wizard opens and can be
   canceled. On a non-DAO service method, confirm no SQL is opened.
4. Save an SQL file and check the existing annotation-processing trigger;
   check `Doma > Jump to Dao` still opens the DAO.

License
-------

Apache License, Version 2.0
