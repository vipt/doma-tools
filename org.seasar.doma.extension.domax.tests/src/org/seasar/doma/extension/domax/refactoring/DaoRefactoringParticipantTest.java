/* SPDX-License-Identifier: Apache-2.0 */
package org.seasar.doma.extension.domax.refactoring;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.JavaCore;
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
import org.eclipse.ltk.core.refactoring.PerformRefactoringOperation;
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

    private static IVMInstall vm;
    private IProject project;
    private IJavaProject javaProject;
    private ICompilationUnit dao;

    @BeforeClass public static void registerVm() {
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
        unit("org.seasar.doma", "Dao.java", "public @interface Dao {}");
        unit("org.seasar.doma", "Select.java", "public @interface Select {}");
        dao = unit("example.dao", "EmployeeDao.java", """
                import org.seasar.doma.*;
                @Dao public interface EmployeeDao {
                    @Select int selectById(int id);
                    @Select int selectAll();
                }
                """);
        unit("example.service", "EmployeeService.java", """
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
        if (project != null) project.delete(true, true, null);
    }

    @Test public void renamingDaoMethodRenamesItsSqlFiles() throws Exception {
        var descriptor = (RenameJavaElementDescriptor) descriptor(IJavaRefactorings.RENAME_METHOD);
        descriptor.setJavaElement(dao.findPrimaryType().getMethod("selectById", new String[] {"I"}));
        descriptor.setNewName("findById");
        descriptor.setUpdateReferences(true);
        perform(descriptor);

        assertExists("example/dao/EmployeeDao/findById.sql");
        assertExists("example/dao/EmployeeDao/findById-oracle.sql");
        assertMissing("example/dao/EmployeeDao/selectById.sql");
        assertMissing("example/dao/EmployeeDao/selectById-oracle.sql");
        // a longer method name that only starts with the old name is untouched
        assertExists("example/dao/EmployeeDao/selectByIdAndName.sql");
        assertExists("example/dao/EmployeeDao/selectAll.sql");
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
    }

    @Test public void movingDaoToAnotherPackageMovesItsSqlFolder() throws Exception {
        IPackageFragment destination = sourceRoot().createPackageFragment("example.repository", true, null);
        var descriptor = (MoveDescriptor) descriptor(IJavaRefactorings.MOVE);
        descriptor.setMoveResources(new IFile[0], new IFolder[0], new ICompilationUnit[] {dao});
        descriptor.setDestination(destination);
        descriptor.setUpdateReferences(true);
        perform(descriptor);

        assertExists("example/repository/EmployeeDao/selectById.sql");
        assertExists("example/repository/EmployeeDao/selectAll.sql");
        // the emptied old package folder is removed; "example" still holds the new one
        assertMissing("example/dao");
        assertExists("example");
    }

    @Test public void renamingPackageMovesSqlFoldersAndUndoRestoresThem() throws Exception {
        var descriptor = (RenameJavaElementDescriptor) descriptor(IJavaRefactorings.RENAME_PACKAGE);
        descriptor.setJavaElement(sourceRoot().getPackageFragment("example.dao"));
        descriptor.setNewName("example.persistence");
        // Updating imports needs the JDT UI preferences, which a headless test
        // does not initialize; the participant does not depend on it.
        descriptor.setUpdateReferences(false);
        Change undo = perform(descriptor);

        assertExists("example/persistence/EmployeeDao/selectById.sql");
        assertMissing("example/dao");

        var monitor = new NullProgressMonitor();
        undo.initializeValidationData(monitor);
        assertTrue(undo.isValid(monitor).isOK());
        undo.perform(monitor);

        assertExists("example/dao/EmployeeDao/selectById.sql");
        assertExists("example/dao/EmployeeDao/selectById-oracle.sql");
        assertMissing("example/persistence");
    }

    @Test public void renameIsRejectedWhenTheTargetSqlFileExists() throws Exception {
        file(SQL_ROOT + "example/dao/EmployeeDao/findById.sql", "select 9");
        var descriptor = (RenameJavaElementDescriptor) descriptor(IJavaRefactorings.RENAME_METHOD);
        descriptor.setJavaElement(dao.findPrimaryType().getMethod("selectById", new String[] {"I"}));
        descriptor.setNewName("findById");
        descriptor.setUpdateReferences(true);
        var status = checkAllConditions(descriptor);

        assertTrue(status.toString(), status.hasError());
        assertTrue(status.toString(), status.toString().contains("findById.sql"));
    }

    @Test public void ignoresInterfacesWithoutDaoAnnotation() throws Exception {
        var other = unit("example.dao", "PlainRepository.java", "public interface PlainRepository { int selectById(int id); }");
        file(SQL_ROOT + "example/dao/PlainRepository/selectById.sql", "select 1");
        waitForIndexer();
        var descriptor = (RenameJavaElementDescriptor) descriptor(IJavaRefactorings.RENAME_METHOD);
        descriptor.setJavaElement(other.findPrimaryType().getMethod("selectById", new String[] {"I"}));
        descriptor.setNewName("findById");
        descriptor.setUpdateReferences(true);
        perform(descriptor);

        assertExists("example/dao/PlainRepository/selectById.sql");
        assertMissing("example/dao/PlainRepository/findById.sql");
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
        var operation = new PerformRefactoringOperation(refactoring, CheckConditionsOperation.ALL_CONDITIONS);
        ResourcesPlugin.getWorkspace().run(operation, null);
        assertFalse(operation.getConditionStatus().toString(), operation.getConditionStatus().hasError());
        assertNotNull(operation.getUndoChange());
        return operation.getUndoChange();
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

    private static void waitForIndexer() throws Exception {
        new SearchEngine().searchAllTypeNames(null, 0, null, 0, IJavaSearchConstants.TYPE,
                SearchEngine.createWorkspaceScope(), new TypeNameRequestor() {},
                IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, null);
    }

    private org.eclipse.jdt.core.IPackageFragmentRoot sourceRoot() {
        return javaProject.getPackageFragmentRoot(project.getFolder("src/main/java"));
    }

    private ICompilationUnit unit(String pkg, String name, String source) throws Exception {
        return sourceRoot().createPackageFragment(pkg, true, null)
                .createCompilationUnit(name, "package " + pkg + ";\n" + source, true, null);
    }

    private IJavaProject javaProject() throws Exception {
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject("doma-refactoring-test-" + UUID.randomUUID());
        project.create(null);
        project.open(null);
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
