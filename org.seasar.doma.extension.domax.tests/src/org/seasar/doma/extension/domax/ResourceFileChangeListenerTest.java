/* SPDX-License-Identifier: Apache-2.0 */
package org.seasar.doma.extension.domax;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/** Checks that SQL file changes touch their DAOs through one coalescing job. */
public class ResourceFileChangeListenerTest {
    private static final int DAO_COUNT = 3;
    private static final int SQL_PER_DAO = 20;

    private static IVMInstall vm;
    private final IWorkspace workspace = ResourcesPlugin.getWorkspace();
    private final List<IFile> daos = new ArrayList<>();
    private final List<IFile> sqls = new ArrayList<>();
    private ResourceFileChangeListener listener;
    private IProject project;
    private boolean autoBuilding;

    @BeforeClass public static void registerVm() {
        var type = JavaRuntime.getVMInstallType("org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType");
        vm = type.createVMInstall("doma-tools-listener-test-vm");
        vm.setName("Doma Tools listener test JRE");
        vm.setInstallLocation(new File(System.getProperty("java.home")));
    }

    @AfterClass public static void unregisterVm() {
        if (vm != null) vm.getVMInstallType().disposeVMInstall(vm.getId());
    }

    @Before public void setUp() throws Exception {
        autoBuilding = setAutoBuilding(false);
        project = javaProject().getProject();
        for (int d = 0; d < DAO_COUNT; d++) {
            String dao = "Dao" + d;
            daos.add(file("src/main/java/example/" + dao + ".java",
                    "package example; public interface " + dao + " {}"));
            for (int s = 0; s < SQL_PER_DAO; s++) {
                sqls.add(file("src/main/resources/META-INF/example/" + dao + "/select" + s + ".sql", "select 1"));
            }
        }
        listener = new ResourceFileChangeListener();
        workspace.addResourceChangeListener(listener, IResourceChangeEvent.POST_CHANGE);
    }

    @After public void tearDown() throws Exception {
        if (listener != null) workspace.removeResourceChangeListener(listener);
        Job.getJobManager().join(ResourceFileChangeListener.JOB_FAMILY, null);
        if (project != null) project.delete(true, true, null);
        setAutoBuilding(autoBuilding);
    }

    @Test public void touchesEachDaoWithOneJobForManySeparateChanges() throws Exception {
        Map<IFile, Long> stamps = stamps();
        int maxJobs = 0;
        for (IFile sql : sqls) {
            sql.setContents(stream("select 2"), true, false, null);
            maxJobs = Math.max(maxJobs, jobs());
        }
        assertEquals(1, maxJobs);
        Job.getJobManager().join(ResourceFileChangeListener.JOB_FAMILY, null);
        for (IFile dao : daos) {
            assertNotEquals(dao.getName(), stamps.get(dao).longValue(), dao.getModificationStamp());
        }
    }

    @Test public void touchesOnlyTheDaoOfTheChangedSql() throws Exception {
        Map<IFile, Long> stamps = stamps();
        sqls.get(0).setContents(stream("select 2"), true, false, null);
        Job.getJobManager().join(ResourceFileChangeListener.JOB_FAMILY, null);
        assertNotEquals(stamps.get(daos.get(0)).longValue(), daos.get(0).getModificationStamp());
        assertEquals(stamps.get(daos.get(1)).longValue(), daos.get(1).getModificationStamp());
        assertEquals(stamps.get(daos.get(2)).longValue(), daos.get(2).getModificationStamp());
    }

    @Test public void ignoresMarkerOnlyChanges() throws Exception {
        Map<IFile, Long> stamps = stamps();
        sqls.get(0).createMarker(IMarker.PROBLEM);
        assertEquals(0, jobs());
        Job.getJobManager().join(ResourceFileChangeListener.JOB_FAMILY, null);
        assertEquals(stamps.get(daos.get(0)).longValue(), daos.get(0).getModificationStamp());
    }

    private int jobs() {
        return Job.getJobManager().find(ResourceFileChangeListener.JOB_FAMILY).length;
    }

    private Map<IFile, Long> stamps() {
        var stamps = new HashMap<IFile, Long>();
        for (IFile dao : daos) stamps.put(dao, dao.getModificationStamp());
        return stamps;
    }

    private boolean setAutoBuilding(boolean value) throws Exception {
        IWorkspaceDescription description = workspace.getDescription();
        boolean previous = description.isAutoBuilding();
        description.setAutoBuilding(value);
        workspace.setDescription(description);
        return previous;
    }

    private IJavaProject javaProject() throws Exception {
        var project = workspace.getRoot().getProject("doma-listener-test-" + UUID.randomUUID());
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
        try (var in = stream(source)) {
            file.create(in, true, null);
        }
        return file;
    }

    private static ByteArrayInputStream stream(String source) {
        return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
    }

    private void folder(IFolder folder) throws Exception {
        if (folder.exists()) return;
        if (folder.getParent() instanceof IFolder parent) folder(parent);
        folder.create(true, true, null);
    }
}
