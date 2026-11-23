/*
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
package org.seasar.doma.extension.domax.refactoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.seasar.doma.extension.domax.Constants;

/**
 * Moves or renames a DAO folder or SQL file and creates missing destination
 * folders.
 * <p>
 * The refactoring itself may remove the source folders that the move leaves
 * empty below {@code META-INF} ({@code pruneEmptySourceFolders}). An undo (or
 * redo) change removes only the folders that the change it reverts created,
 * so folders that existed before the refactoring are kept.
 */
class MoveDaoResourceChange extends Change {

    private final IPath source;

    private final IPath destination;

    private final boolean pruneEmptySourceFolders;

    /** Folders created by the reverted change, removed again when empty. */
    private final List<IPath> createdFoldersToRemove;

    MoveDaoResourceChange(IPath source, IPath destination,
            boolean pruneEmptySourceFolders) {
        this(source, destination, pruneEmptySourceFolders, Collections
                .<IPath> emptyList());
    }

    private MoveDaoResourceChange(IPath source, IPath destination,
            boolean pruneEmptySourceFolders, List<IPath> createdFoldersToRemove) {
        this.source = source;
        this.destination = destination;
        this.pruneEmptySourceFolders = pruneEmptySourceFolders;
        this.createdFoldersToRemove = createdFoldersToRemove;
    }

    @Override
    public String getName() {
        if (source.removeLastSegments(1).equals(
                destination.removeLastSegments(1))) {
            return "Rename '" + source.makeRelative() + "' to '"
                    + destination.lastSegment() + "'";
        }
        return "Move '" + source.makeRelative() + "' to '"
                + destination.makeRelative() + "'";
    }

    @Override
    public void initializeValidationData(IProgressMonitor pm) {
    }

    @Override
    public RefactoringStatus isValid(IProgressMonitor pm) throws CoreException,
            OperationCanceledException {
        RefactoringStatus status = new RefactoringStatus();
        if (root().findMember(source) == null) {
            status.addFatalError("'" + source + "' does not exist.");
        }
        DaoResources.checkDestination(status, destination);
        return status;
    }

    @Override
    public Change perform(IProgressMonitor pm) throws CoreException {
        IResource resource = root().findMember(source);
        List<IPath> created = new ArrayList<IPath>();
        createFolders(destination.removeLastSegments(1), created, pm);
        resource.move(destination, IResource.KEEP_HISTORY, pm);
        if (pruneEmptySourceFolders) {
            pruneEmptyFolders(source.removeLastSegments(1), pm);
        }
        for (int i = createdFoldersToRemove.size() - 1; i >= 0; i--) {
            deleteIfEmpty(createdFoldersToRemove.get(i), pm);
        }
        return new MoveDaoResourceChange(destination, source, false, created);
    }

    @Override
    public Object getModifiedElement() {
        return root().findMember(source);
    }

    /** Creates the missing folders of a path, recording them top-down. */
    private static void createFolders(IPath path, List<IPath> created,
            IProgressMonitor pm) throws CoreException {
        if (path.segmentCount() < 2 || root().findMember(path) != null) {
            return;
        }
        createFolders(path.removeLastSegments(1), created, pm);
        root().getFolder(path).create(false, true, pm);
        created.add(path);
    }

    private static void pruneEmptyFolders(IPath path, IProgressMonitor pm)
            throws CoreException {
        IResource resource = root().findMember(path);
        while (resource instanceof IFolder
                && !Constants.META_INF.equals(resource.getName())
                && ((IFolder) resource).members().length == 0) {
            IContainer parent = resource.getParent();
            resource.delete(IResource.KEEP_HISTORY, pm);
            resource = parent;
        }
    }

    private static void deleteIfEmpty(IPath path, IProgressMonitor pm)
            throws CoreException {
        IResource resource = root().findMember(path);
        if (resource instanceof IFolder
                && ((IFolder) resource).members().length == 0) {
            resource.delete(IResource.KEEP_HISTORY, pm);
        }
    }

    private static IWorkspaceRoot root() {
        return ResourcesPlugin.getWorkspace().getRoot();
    }
}
