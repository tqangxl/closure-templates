/*
 * Copyright 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.jssrc.internal;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.jssrc.SoyJsSrcOptions;
import com.google.template.soy.jssrc.internal.GenJsExprsVisitor.GenJsExprsVisitorFactory;
import com.google.template.soy.jssrc.restricted.JsExpr;
import com.google.template.soy.jssrc.restricted.JsExprUtils;
import com.google.template.soy.jssrc.restricted.SoyJsSrcPrintDirective;
import com.google.template.soy.shared.restricted.ApiCallScopeBindingAnnotations.IsUsingIjData;
import com.google.template.soy.soyparse.ErrorReporter;
import com.google.template.soy.soytree.CallBasicNode;
import com.google.template.soy.soytree.CallDelegateNode;
import com.google.template.soy.soytree.CallNode;
import com.google.template.soy.soytree.CallParamContentNode;
import com.google.template.soy.soytree.CallParamNode;
import com.google.template.soy.soytree.CallParamValueNode;

import java.util.Deque;
import java.util.Map;

import javax.annotation.Nullable;
import javax.inject.Inject;

/**
 * Utilities for generating JS code for calls.
 *
 */
class GenCallCodeUtils {


  /** All registered JS print directives. */
  private final Map<String, SoyJsSrcPrintDirective> soyJsSrcDirectivesMap;

  /** The options for generating JS source code. */
  private final SoyJsSrcOptions jsSrcOptions;

  /** Whether any of the Soy code uses injected data. */
  private final boolean isUsingIjData;

  /** Instance of JsExprTranslator to use. */
  private final JsExprTranslator jsExprTranslator;

  /** The IsComputableAsJsExprsVisitor used by this instance. */
  private final IsComputableAsJsExprsVisitor isComputableAsJsExprsVisitor;

  /** Factory for creating an instance of GenJsExprsVisitor. */
  private final GenJsExprsVisitorFactory genJsExprsVisitorFactory;

  /** For reporting errors. */
  private final ErrorReporter errorReporter;

  /**
   * @param jsSrcOptions The options for generating JS source code.
   * @param isUsingIjData Whether any of the Soy code uses injected data.
   * @param jsExprTranslator Instance of JsExprTranslator to use.
   * @param isComputableAsJsExprsVisitor The IsComputableAsJsExprsVisitor to be used.
   * @param genJsExprsVisitorFactory Factory for creating an instance of GenJsExprsVisitor.
   * @param errorReporter For reporting errors.
   */
  @Inject
  GenCallCodeUtils(
      Map<String, SoyJsSrcPrintDirective> soyJsSrcDirectivesMap, SoyJsSrcOptions jsSrcOptions,
      @IsUsingIjData boolean isUsingIjData, JsExprTranslator jsExprTranslator,
      IsComputableAsJsExprsVisitor isComputableAsJsExprsVisitor,
      GenJsExprsVisitorFactory genJsExprsVisitorFactory,
      ErrorReporter errorReporter) {
    this.jsSrcOptions = jsSrcOptions;
    this.isUsingIjData = isUsingIjData;
    this.jsExprTranslator = jsExprTranslator;
    this.isComputableAsJsExprsVisitor = isComputableAsJsExprsVisitor;
    this.genJsExprsVisitorFactory = genJsExprsVisitorFactory;
    this.soyJsSrcDirectivesMap = soyJsSrcDirectivesMap;
    this.errorReporter = errorReporter;
  }


  /**
   * Generates the JS expression for a given call (the version that doesn't pass a StringBuilder).
   *
   * <p> Important: If there are CallParamContentNode children whose contents are not computable as
   * JS expressions, then this function assumes that, elsewhere, code has been generated to define
   * their respective 'param<n>' temporary variables.
   *
   * @see #genCallExprHelper for code gen examples.
   *
   * @param callNode The call to generate code for.
   * @param localVarTranslations The current stack of replacement JS expressions for the local
   *     variables (and foreach-loop special functions) current in scope.
   * @return The JS expression for the call (the version that doesn't pass a StringBuilder).
   */
  public JsExpr genCallExpr(
      CallNode callNode, Deque<Map<String, JsExpr>> localVarTranslations) {
    return genCallExprHelper(callNode, localVarTranslations, null);
  }


  /**
   * Generates the JS statement for a given call (the version that passes a StringBuilder) and
   * appends it to the given jsCodeBuilder. This method is only applicable for code style
   * 'stringbuilder'.
   *
   * <p> Important: If there are CallParamContentNode children whose contents are not computable as
   * JS expressions, then this function assumes that, elsewhere, code has been generated to define
   * their respective 'param<n>' temporary variables.
   *
   * @see #genCallExprHelper for code gen examples.
   *
   * @param jsCodeBuilder The code builder to append the call to.
   * @param callNode The call to generate code for.
   * @param localVarTranslations The current stack of replacement JS expressions for the local
   *     variables (and foreach-loop special functions) current in scope.
   */
  public void genAndAppendCallStmt(
      JsCodeBuilder jsCodeBuilder, CallNode callNode,
      Deque<Map<String, JsExpr>> localVarTranslations) {

    if (jsSrcOptions.getCodeStyle() != SoyJsSrcOptions.CodeStyle.STRINGBUILDER) {
      throw new AssertionError();
    }

    JsExpr callExpr =
        genCallExprHelper(callNode, localVarTranslations, jsCodeBuilder.getOutputVarName());
    jsCodeBuilder.appendLine(callExpr.getText(), ";");
  }


  /**
   * Private helper for {@code genCallExpr()} and {@code genAndAppendCallStmt()}.
   *
   * <p> Important: If there are CallParamContentNode children whose contents are not computable as
   * JS expressions, then this function assumes that, elsewhere, code has been generated to define
   * their respective 'param<n>' temporary variables.
   *
   * <p> Here are five example calls:
   * <pre>
   *   {call some.func data="all" /}
   *   {call some.func data="$boo.foo" /}
   *   {call some.func}
   *     {param goo = $moo /}
   *   {/call}
   *   {call some.func data="$boo"}
   *     {param goo}Blah{/param}
   *   {/call}
   *   {call some.func}
   *     {param goo}
   *       {for $i in range(3)}{$i}{/for}
   *     {/param}
   *   {/call}
   * </pre>
   * Their respective generated calls might be the following:
   * <pre>
   *   some.func(opt_data)
   *   some.func(opt_data.boo.foo)
   *   some.func({goo: opt_data.moo})
   *   some.func(soy.$$augmentMap(opt_data.boo, {goo: 'Blah'}))
   *   some.func({goo: param65})
   * </pre>
   * Note that in the last case, the param content is not computable as JS expressions, so we assume
   * that code has been generated to define the temporary variable 'param<n>'.
   *
   * @param callNode The call to generate code for.
   * @param localVarTranslations The current stack of replacement JS expressions for the local
   *     variables (and foreach-loop special functions) current in scope.
   * @param outputVarNameForStringbuilder If set to null, then this method generates a call
   *     expression that returns the output. If nonnull, then this method generates a a call
   *     expression that passes this given stringbuilder object to receive the output. (Note that if
   *     this param is nonnull, then this method will assume the code style is stringbuilder without
   *     checking it.)
   * @return The JS expression for the call (the version that doesn't pass a StringBuilder).
   */
  private JsExpr genCallExprHelper(
      CallNode callNode, Deque<Map<String, JsExpr>> localVarTranslations,
      @Nullable String outputVarNameForStringbuilder) {

    JsExpr objToPass = genObjToPass(callNode, localVarTranslations);

    // Build the JS expr text for the callee.
    String calleeExprText;
    if (callNode instanceof CallBasicNode) {
      // Case 1: Basic call.
      calleeExprText = ((CallBasicNode) callNode).getCalleeName();
    } else {
      // Case 2: Delegate call.
      CallDelegateNode callDelegateNode = (CallDelegateNode) callNode;
      String calleeIdExprText =
          "soy.$$getDelTemplateId('" + callDelegateNode.getDelCalleeName() + "')";
      ExprRootNode variantSoyExpr = callDelegateNode.getDelCalleeVariantExpr();
      String variantJsExprText;
      if (variantSoyExpr == null) {
        // Case 2a: Delegate call with empty variant.
        variantJsExprText = "''";
      } else {
        // Case 2b: Delegate call with variant expression.
        JsExpr variantJsExpr = jsExprTranslator.translateToJsExpr(
            variantSoyExpr, variantSoyExpr.toSourceString(), localVarTranslations);
        variantJsExprText = variantJsExpr.getText();
      }
      calleeExprText =
          "soy.$$getDelegateFn(" +
              calleeIdExprText + ", " + variantJsExprText + ", " +
              (callDelegateNode.allowsEmptyDefault() ? "true" : "false") + ")";
    }

    // Generate the main call expression.
    String callExprText;
    if (outputVarNameForStringbuilder != null) {
      callExprText = calleeExprText + "(" +
          objToPass.getText() + ", " + outputVarNameForStringbuilder +
          (isUsingIjData ? ", opt_ijData" : "") + ")";
    } else {
      callExprText = calleeExprText + "(" +
          objToPass.getText() + (isUsingIjData ? ", null, opt_ijData" : "") + ")";
    }

    JsExpr result = new JsExpr(callExprText, Integer.MAX_VALUE);

    // In strict mode, escaping directives may apply to the call site.
    for (String directiveName : callNode.getEscapingDirectiveNames()) {
      SoyJsSrcPrintDirective directive = soyJsSrcDirectivesMap.get(directiveName);
      Preconditions.checkNotNull(
          directive, "Contextual autoescaping produced a bogus directive: %s", directiveName);
      result = directive.applyForJsSrc(result, ImmutableList.<JsExpr>of());
    }

    return result;
  }


  /**
   * Generates the JS expression for the object to pass in a given call.
   *
   * <p> Important: If there are CallParamContentNode children whose contents are not computable as
   * JS expressions, then this function assumes that, elsewhere, code has been generated to define
   * their respective 'param<n>' temporary variables.
   *
   * <p> Here are five example calls:
   * <pre>
   *   {call some.func data="all" /}
   *   {call some.func data="$boo.foo" /}
   *   {call some.func}
   *     {param goo = $moo /}
   *   {/call}
   *   {call some.func data="$boo"}
   *     {param goo}Blah{/param}
   *   {/call}
   *   {call some.func}
   *     {param goo}
   *       {for $i in range(3)}{$i}{/for}
   *     {/param}
   *   {/call}
   * </pre>
   * Their respective objects to pass might be the following:
   * <pre>
   *   opt_data
   *   opt_data.boo.foo
   *   {goo: opt_data.moo}
   *   soy.$$augmentMap(opt_data.boo, {goo: 'Blah'})
   *   {goo: param65}
   * </pre>
   * Note that in the last case, the param content is not computable as JS expressions, so we assume
   * that code has been generated to define the temporary variable 'param<n>'.
   *
   * @param callNode The call to generate code for.
   * @param localVarTranslations The current stack of replacement JS expressions for the local
   *     variables (and foreach-loop special functions) current in scope.
   * @return The JS expression for the object to pass in the call.
   */
  public JsExpr genObjToPass(CallNode callNode, Deque<Map<String, JsExpr>> localVarTranslations) {

    // ------ Generate the expression for the original data to pass ------
    JsExpr dataToPass;
    if (callNode.isPassingAllData()) {
      dataToPass = new JsExpr("opt_data", Integer.MAX_VALUE);
    } else if (callNode.isPassingData()) {
      dataToPass = jsExprTranslator.translateToJsExpr(
          callNode.getDataExpr(), null, localVarTranslations);
    } else {
      dataToPass = new JsExpr("null", Integer.MAX_VALUE);
    }

    // ------ Case 1: No additional params ------
    if (callNode.numChildren() == 0) {
      return dataToPass;
    }

    // ------ Build an object literal containing the additional params ------
    StringBuilder paramsObjSb = new StringBuilder();
    paramsObjSb.append('{');

    boolean isFirst = true;
    for (CallParamNode child : callNode.getChildren()) {

      if (isFirst) {
        isFirst = false;
      } else {
        paramsObjSb.append(", ");
      }

      String key = child.getKey();
      paramsObjSb.append(key).append(": ");

      if (child instanceof CallParamValueNode) {
        CallParamValueNode cpvn = (CallParamValueNode) child;
        JsExpr valueJsExpr = jsExprTranslator.translateToJsExpr(
            cpvn.getValueExprUnion().getExpr(), cpvn.getValueExprText(), localVarTranslations);
        paramsObjSb.append(valueJsExpr.getText());

      } else {
        CallParamContentNode cpcn = (CallParamContentNode) child;
        JsExpr valueJsExpr;
        if (isComputableAsJsExprsVisitor.exec(cpcn)) {
          valueJsExpr = JsExprUtils.concatJsExprsForceString(
              genJsExprsVisitorFactory.create(localVarTranslations).exec(cpcn));
        } else {
          // This is a param with content that cannot be represented as JS expressions, so we assume
          // that code has been generated to define the temporary variable 'param<n>'.
          String paramExpr = "param" + cpcn.getId();
          if (jsSrcOptions.getCodeStyle() == SoyJsSrcOptions.CodeStyle.STRINGBUILDER) {
            paramExpr += ".toString()";
          }
          valueJsExpr = new JsExpr(paramExpr, Integer.MAX_VALUE);
        }

        // If the param node had a content kind specified, it was autoescaped in the
        // corresponding context. Hence the result of evaluating the param block is wrapped
        // in a SanitizedContent instance of the appropriate kind.

        // The expression for the constructor of SanitizedContent of the appropriate kind (e.g.,
        // "new SanitizedHtml"), or null if the node has no 'kind' attribute.  This uses the
        // variant used in internal blocks.
        valueJsExpr = JsExprUtils.maybeWrapAsSanitizedContentForInternalBlocks(
            cpcn.getContentKind(), valueJsExpr);

        paramsObjSb.append(valueJsExpr.getText());
      }
    }

    paramsObjSb.append('}');

    // ------ Cases 2 and 3: Additional params with and without original data to pass ------
    if (callNode.isPassingData()) {
      return new JsExpr(
          "soy.$$augmentMap(" + dataToPass.getText() + ", " + paramsObjSb + ")", Integer.MAX_VALUE);
    } else {
      return new JsExpr(paramsObjSb.toString(), Integer.MAX_VALUE);
    }
  }

}
