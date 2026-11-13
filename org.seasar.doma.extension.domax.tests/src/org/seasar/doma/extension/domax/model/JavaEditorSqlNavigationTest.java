/* SPDX-License-Identifier: Apache-2.0 */
package org.seasar.doma.extension.domax.model;

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
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jface.text.TextSelection;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.seasar.doma.extension.domax.handler.JavaEditorToResourceEditorHandler;

/** Exercises the actual editor handler and SQL factory with real JDT projects. */
public class JavaEditorSqlNavigationTest {
    private static IVMInstall vm;
    private final List<IProject> projects = new ArrayList<>();
    private final TestHandler handler = new TestHandler();
    private final ResourceFileFactory factory = new ResourceFileFactory();
    private IJavaProject project;
    private ICompilationUnit dao;

    @BeforeClass public static void registerVm() {
        var type = JavaRuntime.getVMInstallType("org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType");
        vm = type.createVMInstall("doma-tools-test-vm");
        vm.setName("Doma Tools test JRE");
        vm.setInstallLocation(new File(System.getProperty("java.home")));
    }

    @AfterClass public static void unregisterVm() {
        if (vm != null) vm.getVMInstallType().disposeVMInstall(vm.getId());
    }

    @Before public void setUp() throws Exception {
        project = javaProject();
        unit(project, "org.seasar.doma", "Dao.java", "public @interface Dao {}");
        unit(project, "org.seasar.doma", "Select.java", "public @interface Select {}");
        unit(project, "org.seasar.doma", "Script.java", "public @interface Script {}");
        dao = unit(project, "example", "EmployeeDao.java", """
                import org.seasar.doma.*;
                @Dao public interface EmployeeDao {
                    @Select int selectById(int id);
                    @Script void setup();
                }
                """);
    }

    @After public void tearDown() throws Exception {
        for (int i = projects.size() - 1; i >= 0; i--) projects.get(i).delete(true, true, null);
    }

    @Test public void resolvesCallInsteadOfEnclosingServiceMethod() throws Exception {
        var sql = sql("EmployeeDao", "selectById.sql");
        var caller = caller("return dao.selectById(1);");
        int offset = caller.getSource().indexOf("selectById") + 4;
        assertEquals("find", caller.getElementAt(offset).getElementName());
        IMethod selected = (IMethod) handler.resolve(caller, offset, 0);
        assertEquals("selectById", selected.getElementName());
        assertEquals("example.EmployeeDao", selected.getDeclaringType().getFullyQualifiedName());
        assertEquals(sql, resource(selected).file);
    }

    @Test public void resolvesSelectedCallName() throws Exception {
        var sql = sql("EmployeeDao", "selectById.sql");
        assertEquals(sql, resource(select(caller("return dao.selectById(1);"), "selectById")).file);
    }

    @Test public void preservesNavigationFromDaoDeclaration() throws Exception {
        var sql = sql("EmployeeDao", "selectById.sql");
        assertEquals(sql, resource(select(dao, "selectById")).file);
    }

    @Test public void preservesDeclarationWhitespaceFallback() throws Exception {
        var sql = sql("EmployeeDao", "selectById.sql");
        int offset = dao.getSource().indexOf("@Select int") + "@Select".length();
        var selected = (IMethod) handler.resolve(dao, offset, 0);
        assertEquals(sql, resource(selected).file);
    }

    @Test public void resolvesMethodReference() throws Exception {
        var sql = sql("EmployeeDao", "selectById.sql");
        var caller = unit(project, "example", "Service.java", """
                class Service {
                    java.util.function.IntUnaryOperator query(EmployeeDao dao) { return dao::selectById; }
                }
                """);
        assertEquals(sql, resource(select(caller, "selectById")).file);
    }

    @Test public void resolvesUnsavedWorkingCopy() throws Exception {
        var sql = sql("EmployeeDao", "selectById.sql");
        var caller = caller("return 0;");
        caller.becomeWorkingCopy(null);
        try {
            caller.getBuffer().setContents("package example; class Service { "
                    + "int find(EmployeeDao dao) { return dao.selectById(1); } }");
            assertEquals(sql, resource(select(caller, "selectById")).file);
            try (var in = ((IFile) caller.getResource()).getContents()) {
                assertFalse(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("selectById"));
            }
        } finally {
            caller.discardWorkingCopy();
        }
    }

    @Test public void resolvesDaoInReferencedProject() throws Exception {
        var sql = sql("EmployeeDao", "selectById.sql");
        var app = javaProject();
        var entries = new ArrayList<IClasspathEntry>(List.of(app.getRawClasspath()));
        entries.add(JavaCore.newProjectEntry(project.getPath()));
        app.setRawClasspath(entries.toArray(IClasspathEntry[]::new), null);
        var caller = unit(app, "example", "Service.java", """
                class Service { int find(EmployeeDao dao) { return dao.selectById(1); } }
                """);
        file(app.getProject(), "src/main/resources/META-INF/example/EmployeeDao/selectById.sql", "select 2");
        assertEquals(sql, resource(select(caller, "selectById")).file);
    }

    @Test public void preservesScriptNavigation() throws Exception {
        var script = sql("EmployeeDao", "setup.script");
        sql("EmployeeDao", "setup.sql");
        var selected = select(caller("dao.setup(); return 0;"), "setup");
        assertTrue(resource(selected) instanceof ScriptFile);
        assertEquals(script, resource(selected).file);
    }

    @Test public void doesNotTreatServiceMethodAsDao() throws Exception {
        sql("EmployeeDao", "selectById.sql");
        var caller = unit(project, "example", "Service.java", """
                class Service {
                    int selectById(int id) { return id; }
                    int find() { return selectById(1); }
                }
                """);
        var selected = (IMethod) handler.resolve(caller, caller.getSource().lastIndexOf("selectById"), 10);
        assertEquals("selectById", selected.getElementName());
        assertNull(resource(selected));
    }

    @Test public void distinguishesDaosWithSameMethodName() throws Exception {
        var expected = sql("EmployeeDao", "selectById.sql");
        sql("OtherDao", "selectById.sql");
        unit(project, "example", "OtherDao.java", """
                @org.seasar.doma.Dao public interface OtherDao { int selectById(int id); }
                """);
        assertEquals(expected, resource(select(caller("return dao.selectById(1);"), "selectById")).file);
    }

    @Test public void missingSqlStillProducesWizardTargetWithoutWritingFile() throws Exception {
        var resource = resource(select(caller("return dao.selectById(1);"), "selectById"));
        assertTrue(resource instanceof SqlFile);
        assertFalse(resource.exists());
        assertFalse(project.getProject().getFolder("src/main/resources/META-INF").exists());
    }

    private ResourceFile resource(IMethod method) {
        return factory.createResourceFile(method.getDeclaringType(), method);
    }

    private IMethod select(ICompilationUnit unit, String name) throws Exception {
        int offset = unit.getSource().indexOf(name);
        assertTrue(offset >= 0);
        return (IMethod) handler.resolve(unit, offset, name.length());
    }

    private ICompilationUnit caller(String body) throws Exception {
        return unit(project, "example", "Service.java",
                "class Service { int find(EmployeeDao dao) { " + body + " } }");
    }

    private IFile sql(String daoName, String name) throws Exception {
        return file(project.getProject(), "src/main/resources/META-INF/example/" + daoName + "/" + name, "select 1");
    }

    private ICompilationUnit unit(IJavaProject project, String pkg, String name, String source) throws Exception {
        var root = project.getPackageFragmentRoot(project.getProject().getFolder("src/main/java"));
        return root.createPackageFragment(pkg, true, null)
                .createCompilationUnit(name, "package " + pkg + ";\n" + source, true, null);
    }

    private IJavaProject javaProject() throws Exception {
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject("doma-test-" + UUID.randomUUID());
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

    private IFile file(IProject project, String path, String source) throws Exception {
        var file = project.getFile(path);
        if (file.getParent() instanceof IFolder parent) folder(parent);
        try (var in = new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8))) {
            file.create(in, true, null);
        }
        return file;
    }

    private void folder(IFolder folder) throws Exception {
        if (folder.exists()) return;
        if (folder.getParent() instanceof IFolder parent) folder(parent);
        folder.create(true, true, null);
    }

    private static class TestHandler extends JavaEditorToResourceEditorHandler {
        IJavaElement resolve(ICompilationUnit unit, int offset, int length) {
            return getSelectedJavaElement(unit, new TextSelection(offset, length));
        }
    }
}
