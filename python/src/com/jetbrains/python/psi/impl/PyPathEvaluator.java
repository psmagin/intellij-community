package com.jetbrains.python.psi.impl;

import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.psi.PyCallExpression;
import com.jetbrains.python.psi.PyExpression;
import com.jetbrains.python.psi.PyReferenceExpression;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * @author yole
 */
public class PyPathEvaluator extends PyEvaluator {

  private final String myContainingFilePath;

  public PyPathEvaluator(String containingFilePath) {
    myContainingFilePath = containingFilePath;
  }

  @Nullable
  public static String evaluatePath(PyExpression expr) {
    if (expr == null) {
      return null;
    }
    VirtualFile vFile = expr.getContainingFile().getVirtualFile();
    Object result = new PyPathEvaluator(vFile == null ? null : vFile.getPath()).evaluate(expr);
    return result instanceof String ? (String) result : null;
  }

  @Override
  protected Object evaluateCall(PyCallExpression call) {
    final PyExpression[] args = call.getArguments();
    if (call.isCalleeText(PyNames.DIRNAME) && args.length == 1) {
      Object argValue = evaluate(args[0]);
      return argValue instanceof String ? new File((String) argValue).getParent() : null;
    }
    else if (call.isCalleeText(PyNames.JOIN) && args.length >= 1) {
      return evaluatePathInJoin(args, args.length);
    }
    else if (call.isCalleeText(PyNames.ABSPATH) && args.length == 1) {
      Object argValue = evaluate(args[0]);
      // relative to directory of 'containingFilePath', not file
      if (!(argValue instanceof String)) {
        return null;
      }
      if (FileUtil.isAbsolutePlatformIndependent((String)argValue)) {
        return argValue;
      }
      else {
        String path = new File(new File(myContainingFilePath).getParent(), (String)argValue).getPath();
        return path.replace("\\", "/");
      }
    }
    return super.evaluateCall(call);
  }

  @Override
  protected Object evaluateReferenceExpression(PyReferenceExpression expr) {
    if (PyNames.PARDIR.equals(expr.getName())) {
      return "..";
    }
    else if (PyNames.CURDIR.equals(expr.getName())) {
      return ".";
    }
    if (expr.getQualifier() == null && PyNames.FILE.equals(expr.getReferencedName())) {
      return myContainingFilePath;
    }
    return super.evaluateReferenceExpression(expr);
  }

  public String evaluatePathInJoin(PyExpression[] args, int endElement) {
    String result = null;
    for (int i = 0; i < endElement; i++) {
      Object arg = evaluate(args[i]);
      if (!(arg instanceof String)) {
        return null;
      }
      if (result == null) {
        result = (String)arg;
      }
      else {
        result = new File(result, (String)arg).getPath().replace("\\", "/");
      }
    }
    return result;
  }
}
