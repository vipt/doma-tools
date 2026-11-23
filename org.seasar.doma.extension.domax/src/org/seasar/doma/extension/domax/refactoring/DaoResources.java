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

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.seasar.doma.extension.domax.ClassConstants;
import org.seasar.doma.extension.domax.Constants;
import org.seasar.doma.extension.domax.Logger;
import org.seasar.doma.extension.domax.model.ResourceFile;
import org.seasar.doma.extension.domax.util.ClassUtil;
import org.seasar.doma.extension.domax.util.JavaProjectUtil;

/**
 * Locates the SQL and script files of a DAO:
 * {@code <source folder>/META-INF/<package path>/<DAO name>/<method>[-<suffix>].sql}.
 */
final class DaoResources {

    private DaoResources() {
    }

    static boolean isDao(IType type) {
        if (type == null || !type.exists() || type.getDeclaringType() != null) {
            return false;
        }
        try {
            return type.isInterface() && isAnnotatedWithDomaDao(type);
        } catch (JavaModelException e) {
            return false;
        }
    }

    /**
     * Whether the type is annotated with {@code org.seasar.doma.Dao}, resolved
     * through the imports so that another library's {@code @Dao} is ignored.
     */
    private static boolean isAnnotatedWithDomaDao(IType type)
            throws JavaModelException {
        for (IAnnotation annotation : type.getAnnotations()) {
            String name = annotation.getElementName();
            if (ClassConstants.Dao.equals(name)) {
                return true;
            }
            if (!name.equals(ClassUtil.getSimpleName(ClassConstants.Dao))
                    && !name.endsWith("." + ClassUtil.getSimpleName(ClassConstants.Dao))) {
                continue;
            }
            String[][] resolved = type.resolveType(name);
            if (resolved == null) {
                continue;
            }
            for (String[] candidate : resolved) {
                String qualifiedName = candidate[0].length() == 0 ? candidate[1]
                        : candidate[0] + "." + candidate[1];
                if (ClassConstants.Dao.equals(qualifiedName)) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<IType> daoTypes(IPackageFragment fragment) {
        List<IType> result = new ArrayList<IType>();
        try {
            for (ICompilationUnit unit : fragment.getCompilationUnits()) {
                for (IType type : unit.getTypes()) {
                    if (isDao(type)) {
                        result.add(type);
                    }
                }
            }
        } catch (JavaModelException e) {
            Logger.error(e);
        }
        return result;
    }

    /** Path of a DAO folder relative to a source folder. */
    static IPath daoPath(String packageName, String typeName) {
        IPath path = new Path(Constants.META_INF);
        if (packageName.length() > 0) {
            path = path.append(packageName.replace('.', '/'));
        }
        return path.append(typeName);
    }

    /** The existing DAO folders in the source folders of the DAO's project. */
    static List<IFolder> daoFolders(IType type) {
        IJavaProject project = type.getJavaProject();
        IPath daoPath = daoPath(type.getPackageFragment().getElementName(),
                type.getElementName());
        List<IFolder> result = new ArrayList<IFolder>();
        for (IResource sourceFolder : JavaProjectUtil.getSourceFolders(project)) {
            if (sourceFolder instanceof IContainer) {
                IFolder folder = ((IContainer) sourceFolder).getFolder(daoPath);
                if (folder.exists()) {
                    result.add(folder);
                }
            }
        }
        return result;
    }

    /**
     * The SQL and script files of a DAO method, including suffixed ones such
     * as {@code selectById-oracle.sql}.
     */
    static List<IFile> methodFiles(IType type, String methodName) {
        List<IFile> result = new ArrayList<IFile>();
        for (IFolder folder : daoFolders(type)) {
            try {
                for (IResource member : folder.members()) {
                    if (member instanceof IFile
                            && isMethodFile((IFile) member, methodName)) {
                        result.add((IFile) member);
                    }
                }
            } catch (CoreException e) {
                Logger.error(e);
            }
        }
        return result;
    }

    private static boolean isMethodFile(IFile file, String methodName) {
        if (!ResourceFile.isResourceFileExtension(file.getFileExtension())) {
            return false;
        }
        String baseName = file.getFullPath().removeFileExtension()
                .lastSegment();
        return baseName.equals(methodName)
                || baseName.startsWith(methodName + "-");
    }

    /**
     * The path of a DAO folder after its DAO moves to another package, in the
     * same source folder.
     */
    static IPath movedDaoFolderPath(IFolder daoFolder, String oldPackageName,
            String newPackageName) {
        int segments = daoPath(oldPackageName, daoFolder.getName())
                .segmentCount();
        return daoFolder.getFullPath().removeLastSegments(segments).append(
                daoPath(newPackageName, daoFolder.getName()));
    }

    /**
     * Reports a fatal error when the destination exists, or when a file is in
     * the way of a folder that must be created for it. Fatal errors stop the
     * refactoring before any change, so Java and SQL never get out of step.
     */
    static void checkDestination(RefactoringStatus status, IPath destination) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        if (root.findMember(destination) != null) {
            status.addFatalError("Doma: '" + destination
                    + "' already exists, so the SQL files cannot be moved there.");
            return;
        }
        for (IPath parent = destination.removeLastSegments(1); parent
                .segmentCount() > 0; parent = parent.removeLastSegments(1)) {
            IResource member = root.findMember(parent);
            if (member instanceof IContainer) {
                return;
            }
            if (member != null) {
                status.addFatalError("Doma: '" + parent
                        + "' is a file, so the SQL folder '" + destination
                        + "' cannot be created.");
                return;
            }
        }
    }

    static Change toChange(String name, List<Change> changes) {
        if (changes.isEmpty()) {
            return null;
        }
        if (changes.size() == 1) {
            return changes.get(0);
        }
        return new CompositeChange(name, changes.toArray(new Change[changes
                .size()]));
    }
}
