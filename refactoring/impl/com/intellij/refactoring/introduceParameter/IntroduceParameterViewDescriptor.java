/*
 * Created by IntelliJ IDEA.
 * User: dsl
 * Date: 16.04.2002
 * Time: 15:54:37
 * To change template for new class use
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.refactoring.introduceParameter;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.ui.UsageViewDescriptorAdapter;

class IntroduceParameterViewDescriptor extends UsageViewDescriptorAdapter {

  private PsiMethod myMethodToSearchFor;

  public IntroduceParameterViewDescriptor(PsiMethod methodToSearchFor
  ) {
    super();
    myMethodToSearchFor = methodToSearchFor;

  }

  public PsiElement[] getElements() {
//    if(myMethodToReplaceIn.equals(myMethodToSearchFor)) {
//      return new PsiElement[] {myMethodToReplaceIn};
//    }
    return new PsiElement[]{myMethodToSearchFor};
  }


  public String getProcessedElementsHeader() {
    return RefactoringBundle.message("introduce.parameter.elements.header");
  }
}
