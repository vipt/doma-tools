/*
 * Copyright 2004-2010 the Seasar Foundation and the Others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.seasar.doma.extension.domax;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.seasar.doma.extension.domax.factory.Factory;
import org.seasar.doma.extension.domax.model.DaoMethod;
import org.seasar.doma.extension.domax.model.DaoMethodFactory;
import org.seasar.doma.extension.domax.model.ResourceFile;

/**
 * Recompiles DAO interfaces when their SQL or script files change, so that the
 * Doma annotation processor validates them again.
 * <p>
 * Changed files are collected and handled by a single, coalescing job: a
 * branch switch that changes thousands of SQL files results in one job that
 * touches each affected DAO once, instead of one job (and one worker thread)
 * per SQL file.
 */
public class ResourceFileChangeListener implements IResourceChangeListener {

    /** Job family of the job that touches the DAOs. */
    public static final Object JOB_FAMILY = new Object();

    /** Wait a little so that bursts of changes are handled together. */
    private static final long DELAY_MILLIS = 500L;

    private static final int RELEVANT_CHANGE_FLAGS = IResourceDelta.CONTENT
            | IResourceDelta.REPLACED | IResourceDelta.ENCODING;

    /** Guarded by itself. */
    private final Set<IFile> pendingFiles = new LinkedHashSet<IFile>();

    private final Job job = new TouchDaoJob();

    @Override
    public void resourceChanged(IResourceChangeEvent event) {
        if (event.getType() != IResourceChangeEvent.POST_CHANGE) {
            return;
        }
        IResourceDelta delta = event.getDelta();
        if (delta == null) {
            return;
        }
        SqlFileDeltaVisitor visitor = new SqlFileDeltaVisitor();
        try {
            delta.accept(visitor);
        } catch (CoreException e) {
            Logger.error(e);
        }
        if (visitor.files.isEmpty()) {
            return;
        }
        synchronized (pendingFiles) {
            pendingFiles.addAll(visitor.files);
        }
        // A running job is rescheduled after it finishes, and a waiting or
        // sleeping job is not duplicated, so pending files are never lost.
        job.schedule(DELAY_MILLIS);
    }

    private static class SqlFileDeltaVisitor implements IResourceDeltaVisitor {

        private final List<IFile> files = new ArrayList<IFile>();

        @Override
        public boolean visit(IResourceDelta delta) {
            IResource resource = delta.getResource();
            if (resource.isDerived()) {
                // build output such as target/classes
                return false;
            }
            if (resource.getType() != IResource.FILE) {
                return true;
            }
            if (!ResourceFile.isResourceFileExtension(resource
                    .getFileExtension())) {
                return false;
            }
            switch (delta.getKind()) {
            case IResourceDelta.ADDED:
            case IResourceDelta.REMOVED:
                files.add((IFile) resource);
                break;
            case IResourceDelta.CHANGED:
                // ignore marker-only and similar changes
                if ((delta.getFlags() & RELEVANT_CHANGE_FLAGS) != 0) {
                    files.add((IFile) resource);
                }
                break;
            }
            return false;
        }
    }

    private class TouchDaoJob extends WorkspaceJob {

        TouchDaoJob() {
            super("Rebuilding Doma DAOs for changed SQL files");
            setPriority(Job.BUILD);
            // Conflicting jobs wait in the queue instead of each blocking a
            // worker thread on the workspace lock.
            setRule(ResourcesPlugin.getWorkspace().getRoot());
        }

        @Override
        public boolean belongsTo(Object family) {
            return family == JOB_FAMILY;
        }

        @Override
        public IStatus runInWorkspace(IProgressMonitor monitor)
                throws CoreException {
            List<IFile> files;
            synchronized (pendingFiles) {
                files = new ArrayList<IFile>(pendingFiles);
                pendingFiles.clear();
            }

            Set<IFile> daoFiles = findDaoFiles(files);
            Set<IProject> projects = new LinkedHashSet<IProject>();

            monitor.beginTask(getName(), daoFiles.size() + 1);
            try {
                for (IFile daoFile : daoFiles) {
                    if (monitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                    monitor.subTask(daoFile.getName());
                    // A content change makes the Java builder recompile the
                    // DAO, which runs the annotation processor again.
                    daoFile.touch(null);
                    projects.add(daoFile.getProject());
                    monitor.worked(1);
                }

                IWorkspace workspace = ResourcesPlugin.getWorkspace();
                if (!workspace.isAutoBuilding()) {
                    // With auto build on, Eclipse builds once after this job.
                    for (IProject project : projects) {
                        if (monitor.isCanceled()) {
                            return Status.CANCEL_STATUS;
                        }
                        project.build(
                                IncrementalProjectBuilder.INCREMENTAL_BUILD,
                                null);
                    }
                }
                monitor.worked(1);
                return Status.OK_STATUS;
            } finally {
                monitor.done();
            }
        }

        private Set<IFile> findDaoFiles(List<IFile> files) {
            DaoMethodFactory daoMethodFactory = Factory.getDaoMethodFactory();
            Set<String> seenClasses = new LinkedHashSet<String>();
            Set<IFile> daoFiles = new LinkedHashSet<IFile>();
            for (IFile file : files) {
                DaoMethod daoMethod = daoMethodFactory.createDaoMethod(file);
                if (daoMethod == null) {
                    continue;
                }
                String key = daoMethod.getJavaProject().getElementName() + "/"
                        + daoMethod.getClassName();
                if (!seenClasses.add(key)) {
                    continue;
                }
                try {
                    IType type = daoMethod.getJavaProject().findType(
                            daoMethod.getClassName());
                    if (type == null) {
                        continue;
                    }
                    ICompilationUnit compilationUnit = type
                            .getCompilationUnit();
                    if (compilationUnit == null) {
                        continue;
                    }
                    IResource resource = compilationUnit.getResource();
                    if (resource instanceof IFile && resource.exists()) {
                        daoFiles.add((IFile) resource);
                    }
                } catch (JavaModelException e) {
                    Logger.error(e);
                }
            }
            return daoFiles;
        }
    }

}
