/*
 * Copyright 2004-2010 the Seasar Foundation and the Others.
 * Modified 2026: resolve DAO methods at Java call sites.
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
package org.seasar.doma.extension.domax.handler;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

public class JavaEditorToResourceEditorHandler extends
        AbstractToResourceEditorHandler {

    public JavaEditorToResourceEditorHandler() {
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (editor == null) {
            return null;
        }
        Shell shell = HandlerUtil.getActiveShell(event);
        if (shell == null) {
            return null;
        }
        IJavaElement selectedJavaElement = getSelectedJavaElement(editor);
        if (selectedJavaElement == null) {
            return null;
        }
        openFile(selectedJavaElement, shell);
        return null;
    }

    protected IJavaElement getSelectedJavaElement(IEditorPart editor) {
        if (!(editor instanceof ITextEditor)) {
            return null;
        }
        ITextEditor textEditor = (ITextEditor) editor;
        ISelectionProvider provider = textEditor.getSelectionProvider();
        if (provider == null) {
            return null;
        }
        ISelection selection = provider.getSelection();
        if (!(selection instanceof ITextSelection)) {
            return null;
        }
        ITextSelection textSelection = (ITextSelection) selection;
        ICompilationUnit compilationUnit = JavaUI.getWorkingCopyManager()
                .getWorkingCopy(editor.getEditorInput());
        if (compilationUnit == null) {
            IJavaElement javaElement = JavaUI.getEditorInputJavaElement(editor
                    .getEditorInput());
            if (javaElement == null) {
                return null;
            }
            compilationUnit = (ICompilationUnit) javaElement
                    .getAdapter(ICompilationUnit.class);
        }
        if (compilationUnit == null) {
            return null;
        }
        return getSelectedJavaElement(compilationUnit, textSelection);
    }

    protected IJavaElement getSelectedJavaElement(
            ICompilationUnit compilationUnit, ITextSelection textSelection) {
        try {
            IJavaElement[] elements = compilationUnit.codeSelect(
                    textSelection.getOffset(), textSelection.getLength());
            for (IJavaElement element : elements) {
                if (element instanceof IMethod) {
                    return element;
                }
            }
        } catch (JavaModelException ignored) {
        }
        // Preserve navigation from declarations and selections that do not
        // resolve to a method, such as whitespace within a declaration.
        try {
            return compilationUnit.getElementAt(textSelection.getOffset());
        } catch (JavaModelException ignored) {
            return null;
        }
    }

}
