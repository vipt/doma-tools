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

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext;
import org.eclipse.ltk.core.refactoring.participants.RenameParticipant;

/** Renames {@code <method>.sql} files when a DAO method is renamed. */
public class DaoMethodRenameParticipant extends RenameParticipant {

    private IMethod method;

    private List<IFile> files;

    @Override
    protected boolean initialize(Object element) {
        if (!(element instanceof IMethod)) {
            return false;
        }
        method = (IMethod) element;
        if (!DaoResources.isDao(method.getDeclaringType())) {
            return false;
        }
        files = DaoResources.methodFiles(method.getDeclaringType(),
                method.getElementName());
        return !files.isEmpty();
    }

    @Override
    public String getName() {
        return "Rename Doma SQL files";
    }

    @Override
    public RefactoringStatus checkConditions(IProgressMonitor pm,
            CheckConditionsContext context) {
        RefactoringStatus status = new RefactoringStatus();
        for (IFile file : files) {
            DaoResources.checkDestination(status, destination(file));
        }
        return status;
    }

    @Override
    public Change createChange(IProgressMonitor pm) {
        List<Change> changes = new ArrayList<Change>();
        for (IFile file : files) {
            changes.add(new MoveDaoResourceChange(file.getFullPath(),
                    destination(file), false));
        }
        return DaoResources.toChange(getName(), changes);
    }

    private IPath destination(IFile file) {
        String suffix = file.getName().substring(
                method.getElementName().length());
        return file.getFullPath().removeLastSegments(1).append(
                getArguments().getNewName() + suffix);
    }
}
