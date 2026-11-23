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
import org.eclipse.jdt.core.IType;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext;
import org.eclipse.ltk.core.refactoring.participants.RenameParticipant;

/** Renames the {@code META-INF/.../<DAO name>} folder when a DAO is renamed. */
public class DaoTypeRenameParticipant extends RenameParticipant {

    private List<IFolder> folders;

    @Override
    protected boolean initialize(Object element) {
        if (!(element instanceof IType) || !DaoResources.isDao((IType) element)) {
            return false;
        }
        folders = DaoResources.daoFolders((IType) element);
        return !folders.isEmpty();
    }

    @Override
    public String getName() {
        return "Rename Doma SQL folder";
    }

    @Override
    public RefactoringStatus checkConditions(IProgressMonitor pm,
            CheckConditionsContext context) {
        RefactoringStatus status = new RefactoringStatus();
        for (IFolder folder : folders) {
            DaoResources.checkDestination(status, destination(folder));
        }
        return status;
    }

    @Override
    public Change createChange(IProgressMonitor pm) {
        List<Change> changes = new ArrayList<Change>();
        for (IFolder folder : folders) {
            changes.add(new MoveDaoResourceChange(folder.getFullPath(),
                    destination(folder), false));
        }
        return DaoResources.toChange(getName(), changes);
    }

    private IPath destination(IFolder folder) {
        return folder.getFullPath().removeLastSegments(1).append(
                getArguments().getNewName());
    }
}
