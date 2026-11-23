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
import java.util.List;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext;
import org.eclipse.ltk.core.refactoring.participants.MoveParticipant;

/**
 * Moves the {@code META-INF/<package>/<DAO name>} folder when a DAO moves to
 * another package of the same project. The folder stays in its source folder.
 */
public class DaoTypeMoveParticipant extends MoveParticipant {

    private IType type;

    private List<IFolder> folders;

    @Override
    protected boolean initialize(Object element) {
        if (!(element instanceof IType) || !DaoResources.isDao((IType) element)) {
            return false;
        }
        type = (IType) element;
        IPackageFragment destination = destinationPackage();
        if (destination == null) {
            return false;
        }
        // Compare projects first: a package of the same name in another
        // project still needs the warning about moving SQL files manually.
        if (isSameProject()
                && destination.getElementName().equals(oldPackageName())) {
            return false;
        }
        folders = DaoResources.daoFolders(type);
        return !folders.isEmpty();
    }

    @Override
    public String getName() {
        return "Move Doma SQL folder";
    }

    @Override
    public RefactoringStatus checkConditions(IProgressMonitor pm,
            CheckConditionsContext context) {
        RefactoringStatus status = new RefactoringStatus();
        if (!isSameProject()) {
            status.addWarning("Doma: " + type.getElementName()
                    + " moves to another project. Move its SQL files manually.");
            return status;
        }
        for (IFolder folder : folders) {
            DaoResources.checkDestination(status, destination(folder));
        }
        return status;
    }

    @Override
    public Change createChange(IProgressMonitor pm) {
        if (!isSameProject()) {
            return null;
        }
        List<Change> changes = new ArrayList<Change>();
        for (IFolder folder : folders) {
            changes.add(new MoveDaoResourceChange(folder.getFullPath(),
                    destination(folder), true));
        }
        return DaoResources.toChange(getName(), changes);
    }

    private IPackageFragment destinationPackage() {
        Object destination = getArguments().getDestination();
        return destination instanceof IPackageFragment ? (IPackageFragment) destination
                : null;
    }

    private boolean isSameProject() {
        return type.getJavaProject().equals(
                destinationPackage().getJavaProject());
    }

    private String oldPackageName() {
        return type.getPackageFragment().getElementName();
    }

    private IPath destination(IFolder folder) {
        return DaoResources.movedDaoFolderPath(folder, oldPackageName(),
                destinationPackage().getElementName());
    }
}
