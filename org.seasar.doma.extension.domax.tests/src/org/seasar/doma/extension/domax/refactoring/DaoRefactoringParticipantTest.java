/* SPDX-License-Identifier: Apache-2.0 */
package org.seasar.doma.extension.domax.refactoring;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.MoveDescriptor;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.TypeNameRequestor;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CheckConditionsOperation;
import org.eclipse.ltk.core.refactoring.CreateChangeOperation;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringContribution;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Runs real JDT refactorings and checks that the DAO's SQL files follow. */
public class DaoRefactoringParticipantTest {
    private static final String SQL_ROOT = "src/main/resources/META-INF/";
    private static final String JDT_UI = "org.eclipse.jdt.ui";

    private static IVMInstall vm;
    private final List<IProject> projects = new ArrayList<>();
    private IProject project;
    private IJavaProject javaProject;
    private ICompilationUnit dao;
    private ICompilationUnit service;

    @BeforeClass public static void setUpJdt() {
        // The code style that import rewriting reads is normally provided by the
        // JDT UI plug-in, which does not start without a display.
        if (JavaManipulation.getPreferenceNodeId() == null) {
            JavaManipulation.setPreferenceNodeId(JDT_UI);
        }
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(JavaManipulation.getPreferenceNodeId());
        putIfAbsent(defaults, "org.eclipse.jdt.ui.importorder", "java;javax;org;com");
        putIfAbsent(defaults, "org.eclipse.jdt.ui.ondemandthreshold", "99");
        putIfAbsent(defaults, "org.eclipse.jdt.ui.staticondemandthreshold", "99");

        var type = JavaRuntime.getVMInstallType("org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType");
        vm = type.createVMInstall("doma-tools-refactoring-test-vm");
        vm.setName("Doma Tools refactoring test JRE");
        vm.setInstallLocation(new File(System.getProperty("java.home")));
    }

    @AfterClass public static void unregisterVm() {
        if (vm != null) vm.getVMInstallType().disposeVMInstall(vm.getId());
    }

    @Before public void setUp() throws Exception {
        javaProject = javaProject();
        project = javaProject.getProject();
        unit(javaProject, "org.seasar.doma", "Dao.java", "public @interface Dao {}");
        unit(javaProject, "org.seasar.doma", "Select.java", "public @interface Select {}");
        dao = unit(javaProject, "example.dao", "EmployeeDao.java", """
                import org.seasar.doma.*;
                @Dao public interface EmployeeDao {
                    @Select int selectById(int id);
                    @Select int selectAll();
                }
                """);
        service = unit(javaProject, "example.service", "EmployeeService.java", """
                import example.dao.EmployeeDao;
                class EmployeeService { int find(EmployeeDao dao) { return dao.selectById(1); } }
                """);
        file(SQL_ROOT + "example/dao/EmployeeDao/selectById.sql", "select 1");
        file(SQL_ROOT + "example/dao/EmployeeDao/selectById-oracle.sql", "select 1 from dual");
        file(SQL_ROOT + "example/dao/EmployeeDao/selectByIdAndName.sql", "select 2");
        file(SQL_ROOT + "example/dao/EmployeeDao/selectAll.sql", "select 3");
        waitForIndexer();
    }

    @After public void tearDown() throws Exception {
        for (int i = projects.size() - 1; i >= 0; i--) projects.get(i).delete(true, true, null);
    }

    @Test public void renamingDaoMethodRenamesItsSqlFiles() throws Exception {
        perform(renameMethod(dao, "findById"));

        assertExists("example/dao/EmployeeDao/findById.sql");
        assertExists("example/dao/EmployeeDao/findById-oracle.sql");
        assertMissing("example/dao/EmployeeDao/selectById.sql");
        assertMissing("example/dao/EmployeeDao/selectById-oracle.sql");
        // a longer method name that only starts with the old name is untouched
        assertExists("example/dao/EmployeeDao/selectByIdAndName.sql");
        assertExists("example/dao/EmployeeDao/selectAll.sql");
        assertTrue(service.getSource().contains("dao.findById(1)"));
    }

    @Test public void renamingDaoRenamesItsSqlFolder() throws Exception {
        var descriptor = (RenameJavaElementDescriptor) descriptor(IJavaRefactorings.RENAME_TYPE);
        descriptor.setJavaElement(dao.findPrimaryType());
        descriptor.setNewName("StaffDao");
        descriptor.setUpdateReferences(true);
        perform(descriptor);

        assertExists("example/dao/StaffDao/selectById.sql");
        assertExists("example/dao/StaffDao/selectAll.sql");
        assertMissing("example/dao/EmployeeDao");
        assertTrue(service.getSource().contains("import example.dao.StaffDao;"));
    }

    @Test public void movingDaoToAnotherPackageMovesItsSqlFolder() throws Exception {
        IPackageFragment destination = sourceRoot(javaProject).createPackageFragment("example.repository", true, null);
        perform(moveUnit(dao, destination));

        assertExists("example/repository/EmployeeDao/selectById.sql");
        assertExists("example/repository/EmployeeDao/selectAll.sql");
        // the emptied old package folder is removed; "example" still holds the new one
        assertMissing("example/dao");
        assertExists("example");
        assertTrue(service.getSource().contains("import example.repository.EmployeeDao;"));
    }

    @Test public void renamingPackageMovesSqlFoldersAndUndoRestoresThem() throws Exception {
        Change undo = perform(renamePackage("example.dao", "example.persistence"));

        assertExists("example/persistence/EmployeeDao/selectById.sql");
        assertMissing("example/dao");
        assertTrue(service.getSource().contains("import example.persistence.EmployeeDao;"));

        undo(undo);

        assertExists("example/dao/EmployeeDao/selectById.sql");
        assertExists("example/dao/EmployeeDao/selectById-oracle.sql");
        assertMissing("example/persistence");
    }

    @Test public void undoKeepsFoldersThatExistedBeforeTheRefactoring() throws Exception {
        folder(project.getFolder(SQL_ROOT + "example/persistence"));
        Change undo = perform(renamePackage("example.dao", "example.persistence"));
        assertExists("example/persistence/EmployeeDao/selectById.sql");

        undo(undo);

        assertExists("example/dao/EmployeeDao/selectById.sql");
        assertExists("example/persistence");
        assertMissing("example/persistence/EmployeeDao");
    }

    @Test public void renameIsRejectedWhenTheTargetSqlFileExists() throws Exception {
        file(SQL_ROOT + "example/dao/EmployeeDao/findById.sql", "select 9");
        var status = checkAllConditions(renameMethod(dao, "findById"));

        assertTrue(status.toString(), status.hasFatalError());
        assertTrue(status.toString(), status.toString().contains("findById.sql"));
    }

    @Test public void refactoringIsRejectedWhenAFileBlocksTheDestinationFolder() throws Exception {
        file(SQL_ROOT + "example/persistence", "not a folder");
        var status = checkAllConditions(renamePackage("example.dao", "example.persistence"));

        assertTrue(status.toString(), status.hasFatalError());
        assertTrue(status.toString(), status.toString().contains("is a file"));
        // nothing was changed
        assertExists("example/dao/EmployeeDao/selectById.sql");
        assertTrue(sourceRoot(javaProject).getPackageFragment("example.dao").exists());
    }

    @Test public void warnsWhenMovingDaoToSamePackageNameInAnotherProject() throws Exception {
        var other = javaProject();
        IPackageFragment destination = sourceRoot(other).createPackageFragment("example.dao", true, null);
        var status = checkAllConditions(moveUnit(dao, destination));

        assertTrue(status.toString(), status.hasWarning());
        assertTrue(status.toString(), status.toString().contains("Move its SQL files manually"));
        assertExists("example/dao/EmployeeDao/selectById.sql");
    }

    @Test public void ignoresInterfacesWithoutDaoAnnotation() throws Exception {
        var other = unit(javaProject, "example.dao", "PlainRepository.java",
                "public interface PlainRepository { int selectById(int id); }");
        file(SQL_ROOT + "example/dao/PlainRepository/selectById.sql", "select 1");
        waitForIndexer();
        perform(renameMethod(other, "findById"));

        assertExists("example/dao/PlainRepository/selectById.sql");
        assertMissing("example/dao/PlainRepository/findById.sql");
    }

    @Test public void ignoresDaoAnnotationOfAnotherLibrary() throws Exception {
        unit(javaProject, "example.custom", "Dao.java", "public @interface Dao {}");
        var other = unit(javaProject, "example.dao", "CustomRepository.java", """
                import example.custom.Dao;
                @Dao public interface CustomRepository { int selectById(int id); }
                """);
        file(SQL_ROOT + "example/dao/CustomRepository/selectById.sql", "select 1");
        waitForIndexer();
        perform(renameMethod(other, "findById"));

        assertExists("example/dao/CustomRepository/selectById.sql");
        assertMissing("example/dao/CustomRepository/findById.sql");
    }

    private RenameJavaElementDescriptor renameMethod(ICompilationUnit unit, String newName) {
        var descriptor = (RenameJavaElementDescriptor) descriptor(IJavaRefactorings.RENAME_METHOD);
        descriptor.setJavaElement(unit.findPrimaryType().getMethod("selectById", new String[] {"I"}));
        descriptor.setNewName(newName);
        descriptor.setUpdateReferences(true);
        return descriptor;
    }

    private RenameJavaElementDescriptor renamePackage(String oldName, String newName) {
        var descriptor = (RenameJavaElementDescriptor) descriptor(IJavaRefactorings.RENAME_PACKAGE);
        descriptor.setJavaElement(sourceRoot(javaProject).getPackageFragment(oldName));
        descriptor.setNewName(newName);
        descriptor.setUpdateReferences(true);
        return descriptor;
    }

    private MoveDescriptor moveUnit(ICompilationUnit unit, IPackageFragment destination) {
        var descriptor = (MoveDescriptor) descriptor(IJavaRefactorings.MOVE);
        descriptor.setMoveResources(new IFile[0], new IFolder[0], new ICompilationUnit[] {unit});
        descriptor.setDestination(destination);
        descriptor.setUpdateReferences(true);
        return descriptor;
    }

    private RefactoringDescriptor descriptor(String id) {
        RefactoringContribution contribution = RefactoringCore.getRefactoringContribution(id);
        assertNotNull(id, contribution);
        RefactoringDescriptor descriptor = contribution.createDescriptor();
        descriptor.setProject(project.getName());
        return descriptor;
    }

    private Change perform(RefactoringDescriptor descriptor) throws Exception {
        var status = new RefactoringStatus();
        Refactoring refactoring = descriptor.createRefactoring(status);
        assertTrue(status.toString(), status.isOK());
        var create = new CreateChangeOperation(
                new CheckConditionsOperation(refactoring, CheckConditionsOperation.ALL_CONDITIONS),
                RefactoringStatus.FATAL);
        ResourcesPlugin.getWorkspace().run(create, null);
        assertFalse(create.getConditionCheckingStatus().toString(),
                create.getConditionCheckingStatus().hasError());
        useJdtCoreBuffers();
        var perform = new PerformChangeOperation(create.getChange());
        ResourcesPlugin.getWorkspace().run(perform, null);
        assertNotNull(perform.getUndoChange());
        return perform.getUndoChange();
    }

    private static void undo(Change undo) throws Exception {
        var monitor = new NullProgressMonitor();
        undo.initializeValidationData(monitor);
        assertTrue(undo.isValid(monitor).isOK());
        useJdtCoreBuffers();
        undo.perform(monitor);
    }

    /**
     * Activating the JDT UI plug-in, which happens lazily while a refactoring
     * is created or checked, installs working copy buffers backed by the
     * editor's document adapter. Without a display that class cannot be loaded
     * (NoClassDefFoundError on Linux CI), so changes use JDT core buffers.
     */
    private static void useJdtCoreBuffers() {
        WorkingCopyOwner.setPrimaryBufferProvider(null);
    }

    private RefactoringStatus checkAllConditions(RefactoringDescriptor descriptor) throws Exception {
        var status = new RefactoringStatus();
        Refactoring refactoring = descriptor.createRefactoring(status);
        var operation = new CheckConditionsOperation(refactoring, CheckConditionsOperation.ALL_CONDITIONS);
        ResourcesPlugin.getWorkspace().run(operation, null);
        return operation.getStatus();
    }

    private void assertExists(String path) {
        assertNotNull(path, project.findMember(SQL_ROOT + path));
    }

    private void assertMissing(String path) {
        assertNull(path, project.findMember(SQL_ROOT + path));
    }

    private static void putIfAbsent(IEclipsePreferences preferences, String key, String value) {
        if (preferences.get(key, null) == null) preferences.put(key, value);
    }

    private static void waitForIndexer() throws Exception {
        new SearchEngine().searchAllTypeNames(null, 0, null, 0, IJavaSearchConstants.TYPE,
                SearchEngine.createWorkspaceScope(), new TypeNameRequestor() {},
                IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, null);
    }

    private static IPackageFragmentRoot sourceRoot(IJavaProject javaProject) {
        return javaProject.getPackageFragmentRoot(javaProject.getProject().getFolder("src/main/java"));
    }

    private static ICompilationUnit unit(IJavaProject javaProject, String pkg, String name, String source)
            throws Exception {
        return sourceRoot(javaProject).createPackageFragment(pkg, true, null)
                .createCompilationUnit(name, "package " + pkg + ";\n" + source, true, null);
    }

    private IJavaProject javaProject() throws Exception {
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject("doma-refactoring-test-" + UUID.randomUUID());
        project.create(null);
        project.open(null);
        projects.add(project);
        var description = project.getDescription();
        description.setNatureIds(new String[] {JavaCore.NATURE_ID});
        project.setDescription(description, null);
        folder(project.getFolder("src/main/java"));
        folder(project.getFolder("src/main/resources"));
        folder(project.getFolder("bin"));
        var javaProject = JavaCore.create(project);
        var options = new HashMap<String, String>();
        JavaCore.setComplianceOptions(JavaCore.VERSION_17, options);
        javaProject.setOptions(options);
        javaProject.setRawClasspath(new IClasspathEntry[] {
                JavaCore.newSourceEntry(project.getFolder("src/main/java").getFullPath()),
                JavaCore.newSourceEntry(project.getFolder("src/main/resources").getFullPath()),
                JavaCore.newContainerEntry(JavaRuntime.newJREContainerPath(vm))
        }, project.getFolder("bin").getFullPath(), null);
        return javaProject;
    }

    private IFile file(String path, String source) throws Exception {
        var file = project.getFile(path);
        if (file.getParent() instanceof IFolder parent) folder(parent);
        try (var in = new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8))) {
            file.create(in, true, null);
        }
        return file;
    }

    private static void folder(IFolder folder) throws Exception {
        if (folder.exists()) return;
        if (folder.getParent() instanceof IFolder parent) folder(parent);
        folder.create(true, true, null);
    }
}
