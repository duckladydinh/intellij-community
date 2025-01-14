/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.abstraction;

import com.intellij.psi.*;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.psiutils.MethodUtils;
import com.siyeh.ig.psiutils.TypeUtils;
import org.jetbrains.annotations.NotNull;

/**
 * @author Bas Leijdekkers
 */
public class OptionalUsedAsFieldOrParameterTypeInspection extends BaseInspection {

  @NotNull
  @Override
  protected String buildErrorString(Object... infos) {
    final PsiTypeElement typeElement = (PsiTypeElement)infos[0];
    final PsiElement parent = typeElement.getParent();
    if (parent instanceof PsiField) {
      final PsiField field = (PsiField)parent;
      return InspectionGadgetsBundle.message("optional.used.as.field.type.problem.descriptor", field.getName());
    }
    else if (parent instanceof PsiParameter) {
      final PsiParameter parameter = (PsiParameter)parent;
      return InspectionGadgetsBundle.message("optional.used.as.parameter.type.problem.descriptor", parameter.getName());
    }
    throw new AssertionError();
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new OptionUsedAsFieldOrParameterTypeVisitor();
  }

  private static class OptionUsedAsFieldOrParameterTypeVisitor extends BaseInspectionVisitor {

    @Override
    public void visitField(@NotNull PsiField field) {
      super.visitField(field);
      checkTypeElement(field.getTypeElement());
    }

    @Override
    public void visitParameter(@NotNull PsiParameter parameter) {
      super.visitParameter(parameter);
      final PsiElement scope = parameter.getDeclarationScope();
      if (!(scope instanceof PsiMethod)) {
        return;
      }
      final PsiMethod method = (PsiMethod)scope;
      if (MethodUtils.hasSuper(method)) {
        return;
      }
      checkTypeElement(parameter.getTypeElement());
    }

    private void checkTypeElement(PsiTypeElement typeElement) {
      if (typeElement == null || !TypeUtils.isOptional(typeElement.getType())) {
        return;
      }
      registerError(typeElement, typeElement);
    }
  }
}
