// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.editor.impl;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.ex.DocumentEx;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Key;
import com.intellij.util.BitUtil;
import com.intellij.util.Consumer;
import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;

/**
 * Implementation of the markup element for the editor and document.
 * @author max
 */
class RangeHighlighterImpl extends RangeMarkerImpl implements RangeHighlighterEx {
  @SuppressWarnings({"InspectionUsingGrayColors", "UseJBColor"})
  private static final Color NULL_COLOR = new Color(0, 0, 0); // must be new instance to work as a sentinel
  private static final Key<Boolean> VISIBLE_IF_FOLDED = Key.create("visible.folded");

  private final MarkupModelImpl myModel;
  private TextAttributes myForcedTextAttributes;
  private TextAttributesKey myTextAttributesKey;
  private LineMarkerRenderer myLineMarkerRenderer;
  private Color myErrorStripeColor;
  private Color myLineSeparatorColor;
  private SeparatorPlacement mySeparatorPlacement;
  private GutterIconRenderer myGutterIconRenderer;
  private Object myErrorStripeTooltip;
  private MarkupEditorFilter myFilter = MarkupEditorFilter.EMPTY;
  private CustomHighlighterRenderer myCustomRenderer;
  private LineSeparatorRenderer myLineSeparatorRenderer;

  private byte myFlags;

  private static final byte AFTER_END_OF_LINE_MASK = 1;
  private static final byte ERROR_STRIPE_IS_THIN_MASK = 2;
  private static final byte TARGET_AREA_IS_EXACT_MASK = 4;
  private static final byte IN_BATCH_CHANGE_MASK = 8;
  static final byte CHANGED_MASK = 16;
  static final byte RENDERERS_CHANGED_MASK = 32;
  static final byte FONT_STYLE_CHANGED_MASK = 64;
  static final byte FOREGROUND_COLOR_CHANGED_MASK = -128;

  @MagicConstant(intValues = {AFTER_END_OF_LINE_MASK, ERROR_STRIPE_IS_THIN_MASK, TARGET_AREA_IS_EXACT_MASK, IN_BATCH_CHANGE_MASK,
    CHANGED_MASK, RENDERERS_CHANGED_MASK, FONT_STYLE_CHANGED_MASK, FOREGROUND_COLOR_CHANGED_MASK})
  private @interface FlagConstant {}

  @MagicConstant(flags = {CHANGED_MASK, RENDERERS_CHANGED_MASK, FONT_STYLE_CHANGED_MASK, FOREGROUND_COLOR_CHANGED_MASK})
  private @interface ChangeStatus {}

  RangeHighlighterImpl(@NotNull MarkupModelImpl model,
                       int start,
                       int end,
                       int layer,
                       @NotNull HighlighterTargetArea target,
                       @Nullable TextAttributesKey textAttributesKey,
                       boolean greedyToLeft,
                       boolean greedyToRight) {
    super((DocumentEx)model.getDocument(), start, end, false, true);
    myTextAttributesKey = textAttributesKey;
    setFlag(TARGET_AREA_IS_EXACT_MASK, target == HighlighterTargetArea.EXACT_RANGE);
    myModel = model;

    registerInTree(start, end, greedyToLeft, greedyToRight, layer);
  }

  private boolean isFlagSet(@FlagConstant byte mask) {
    return BitUtil.isSet(myFlags, mask);
  }

  private void setFlag(@FlagConstant byte mask, boolean value) {
    myFlags = BitUtil.set(myFlags, mask, value);
  }


  @Override
  public TextAttributesKey getTextAttributesKey() {
    return myTextAttributesKey;
  }

  @Override
  @ApiStatus.Internal
  public @Nullable TextAttributes getForcedTextAttributes() {
    return myForcedTextAttributes;
  }

  @Override
  @ApiStatus.Internal
  public @Nullable Color getForcedErrorStripeMarkColor() {
    return myErrorStripeColor;
  }

  @Override
  public @Nullable TextAttributes getTextAttributes(@Nullable("when null, the global scheme will be used") EditorColorsScheme scheme) {
    if (myForcedTextAttributes != null) return myForcedTextAttributes;
    if (myTextAttributesKey == null) return null;

    EditorColorsScheme colorScheme = scheme == null ? EditorColorsManager.getInstance().getGlobalScheme() : scheme;
    return colorScheme.getAttributes(myTextAttributesKey);
  }

  @Override
  public void setTextAttributes(@Nullable TextAttributes textAttributes) {
    TextAttributes old = myForcedTextAttributes;
    if (old == textAttributes) return;

    myForcedTextAttributes = textAttributes;

    if (old == TextAttributes.ERASE_MARKER || textAttributes == TextAttributes.ERASE_MARKER ||
        old == null && myTextAttributesKey != null) {
      fireChanged(false, true, true);
    }
    else if (!Comparing.equal(old, textAttributes)) {
      fireChanged(false, getFontStyle(old) != getFontStyle(textAttributes),
                  !Comparing.equal(getForegroundColor(old), getForegroundColor(textAttributes)));
    }
  }

  @Override
  public void setTextAttributesKey(@NotNull TextAttributesKey textAttributesKey) {
    TextAttributesKey old = myTextAttributesKey;
    myTextAttributesKey = textAttributesKey;
    if (!Comparing.equal(old, textAttributesKey)) {
      fireChanged(false, myForcedTextAttributes == null, myForcedTextAttributes == null);
    }
  }

  @Override
  public void setVisibleIfFolded(boolean value) {
    putUserData(VISIBLE_IF_FOLDED, value ? Boolean.TRUE : null);
  }

  @Override
  public boolean isVisibleIfFolded() {
    return VISIBLE_IF_FOLDED.isIn(this);
  }

  @Override
  public <T> void putUserDataAndFireChanged(@NotNull Key<T> key, @Nullable T value) {
    putUserData(key, value);
    fireChanged(false, false, false);
  }

  private static int getFontStyle(TextAttributes textAttributes) {
    return textAttributes == null ? Font.PLAIN : textAttributes.getFontType();
  }

  private static Color getForegroundColor(TextAttributes textAttributes) {
    return textAttributes == null ? null : textAttributes.getForegroundColor();
  }

  @Override
  @NotNull
  public HighlighterTargetArea getTargetArea() {
    return isFlagSet(TARGET_AREA_IS_EXACT_MASK) ? HighlighterTargetArea.EXACT_RANGE : HighlighterTargetArea.LINES_IN_RANGE;
  }

  @Override
  public LineMarkerRenderer getLineMarkerRenderer() {
    return myLineMarkerRenderer;
  }

  @Override
  public void setLineMarkerRenderer(LineMarkerRenderer renderer) {
    boolean oldRenderedInGutter = isRenderedInGutter();
    LineMarkerRenderer old = myLineMarkerRenderer;
    myLineMarkerRenderer = renderer;
    if (isRenderedInGutter() != oldRenderedInGutter) {
      myModel.treeFor(this).updateRenderedFlags(this);
    }
    if (!Comparing.equal(old, renderer)) {
      fireChanged(true, false, false);
    }
  }

  @Override
  public CustomHighlighterRenderer getCustomRenderer() {
    return myCustomRenderer;
  }

  @Override
  public void setCustomRenderer(CustomHighlighterRenderer renderer) {
    CustomHighlighterRenderer old = myCustomRenderer;
    myCustomRenderer = renderer;
    if (!Comparing.equal(old, renderer)) {
      fireChanged(true, false, false);
    }
  }

  @Override
  public GutterIconRenderer getGutterIconRenderer() {
    return myGutterIconRenderer;
  }

  @Override
  public void setGutterIconRenderer(GutterIconRenderer renderer) {
    boolean oldRenderedInGutter = isRenderedInGutter();
    GutterMark old = myGutterIconRenderer;
    myGutterIconRenderer = renderer;
    if (isRenderedInGutter() != oldRenderedInGutter) {
      myModel.treeFor(this).updateRenderedFlags(this);
    }
    if (!Comparing.equal(old, renderer)) {
      fireChanged(true, false, false);
    }
  }

  @Override
  public Color getErrorStripeMarkColor(@Nullable("when null, the global scheme will be used") EditorColorsScheme scheme) {
    if (myErrorStripeColor == NULL_COLOR) return null;
    if (myErrorStripeColor != null) return myErrorStripeColor;
    if (myForcedTextAttributes != null) return myForcedTextAttributes.getErrorStripeColor();
    TextAttributes textAttributes = getTextAttributes(scheme);
    return textAttributes != null ? textAttributes.getErrorStripeColor() : null;
  }

  @Override
  public void setErrorStripeMarkColor(@Nullable Color color) {
    if (color == null) color = NULL_COLOR;
    Color old = myErrorStripeColor;
    myErrorStripeColor = color;
    if (!Comparing.equal(old, color)) {
      fireChanged(false, false, false);
    }
  }

  @Override
  public Object getErrorStripeTooltip() {
    return myErrorStripeTooltip;
  }

  @Override
  public void setErrorStripeTooltip(Object tooltipObject) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    Object old = myErrorStripeTooltip;
    myErrorStripeTooltip = tooltipObject;
    if (!Comparing.equal(old, tooltipObject)) {
      fireChanged(false, false, false);
    }
  }

  @Override
  public boolean isThinErrorStripeMark() {
    return isFlagSet(ERROR_STRIPE_IS_THIN_MASK);
  }

  @Override
  public void setThinErrorStripeMark(boolean value) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    boolean old = isThinErrorStripeMark();
    setFlag(ERROR_STRIPE_IS_THIN_MASK, value);
    if (old != value) {
      fireChanged(false, false, false);
    }
  }

  @Override
  public Color getLineSeparatorColor() {
    return myLineSeparatorColor;
  }

  @Override
  public void setLineSeparatorColor(Color color) {
    Color old = myLineSeparatorColor;
    myLineSeparatorColor = color;
    if (!Comparing.equal(old, color)) {
      fireChanged(false, false, false);
    }
  }

  @Override
  public SeparatorPlacement getLineSeparatorPlacement() {
    return mySeparatorPlacement;
  }

  @Override
  public void setLineSeparatorPlacement(@Nullable SeparatorPlacement placement) {
    SeparatorPlacement old = mySeparatorPlacement;
    mySeparatorPlacement = placement;
    if (!Comparing.equal(old, placement)) {
      fireChanged(false, false, false);
    }
  }

  @Override
  public void setEditorFilter(@NotNull MarkupEditorFilter filter) {
    myFilter = filter;
    fireChanged(false, false, false);
  }

  @Override
  @NotNull
  public MarkupEditorFilter getEditorFilter() {
    return myFilter;
  }

  @Override
  public boolean isAfterEndOfLine() {
    return isFlagSet(AFTER_END_OF_LINE_MASK);
  }

  @Override
  public void setAfterEndOfLine(boolean afterEndOfLine) {
    boolean old = isAfterEndOfLine();
    setFlag(AFTER_END_OF_LINE_MASK, afterEndOfLine);
    if (old != afterEndOfLine) {
      fireChanged(false, false, false);
    }
  }

  @Override
  public void setGreedyToLeft(boolean greedy) {
    boolean old = isGreedyToLeft();
    super.setGreedyToLeft(greedy);
    if (old != greedy) {
      fireChanged(false, false, false);
    }
  }

  @Override
  public void setGreedyToRight(boolean greedy) {
    boolean old = isGreedyToRight();
    super.setGreedyToRight(greedy);
    if (old != greedy) {
      fireChanged(false, false, false);
    }
  }

  @Override
  public void setStickingToRight(boolean value) {
    boolean old = isStickingToRight();
    super.setStickingToRight(value);
    if (old != value) {
      fireChanged(false, false, false);
    }
  }

  private void fireChanged(boolean renderersChanged, boolean fontStyleChanged, boolean foregroundColorChanged) {
    if (isFlagSet(IN_BATCH_CHANGE_MASK)) {
      setFlag(CHANGED_MASK, true);
      if (renderersChanged) setFlag(RENDERERS_CHANGED_MASK, true);
      if (fontStyleChanged) setFlag(FONT_STYLE_CHANGED_MASK, true);
      if (foregroundColorChanged) setFlag(FOREGROUND_COLOR_CHANGED_MASK, true);
    }
    else {
      myModel.fireAttributesChanged(this, renderersChanged, fontStyleChanged, foregroundColorChanged);
    }
  }

  @Override
  public int getAffectedAreaStartOffset() {
    int startOffset = getStartOffset();
    switch (getTargetArea()) {
      case EXACT_RANGE:
        return startOffset;
      case LINES_IN_RANGE:
        Document document = myModel.getDocument();
        int textLength = document.getTextLength();
        if (startOffset >= textLength) return textLength;
        return document.getLineStartOffset(document.getLineNumber(startOffset));
      default:
        throw new IllegalStateException(getTargetArea().toString());
    }
  }

  @Override
  public int getAffectedAreaEndOffset() {
    int endOffset = getEndOffset();
    switch (getTargetArea()) {
      case EXACT_RANGE:
        return endOffset;
      case LINES_IN_RANGE:
        Document document = myModel.getDocument();
        int textLength = document.getTextLength();
        if (endOffset >= textLength) return endOffset;
        return Math.min(textLength, document.getLineEndOffset(document.getLineNumber(endOffset)) + 1);
      default:
        throw new IllegalStateException(getTargetArea().toString());
    }

  }

  @ChangeStatus
  byte changeAttributesNoEvents(@NotNull Consumer<? super RangeHighlighterEx> change) {
    assert !isFlagSet(IN_BATCH_CHANGE_MASK);
    assert !isFlagSet(CHANGED_MASK);
    setFlag(IN_BATCH_CHANGE_MASK, true);
    setFlag(RENDERERS_CHANGED_MASK, false);
    setFlag(FONT_STYLE_CHANGED_MASK, false);
    setFlag(FOREGROUND_COLOR_CHANGED_MASK, false);
    byte result = 0;
    try {
      change.consume(this);
    }
    finally {
      setFlag(IN_BATCH_CHANGE_MASK, false);
      if (isFlagSet(CHANGED_MASK)) {
        result |= CHANGED_MASK;
        if (isFlagSet(RENDERERS_CHANGED_MASK)) result |= RENDERERS_CHANGED_MASK;
        if (isFlagSet(FONT_STYLE_CHANGED_MASK)) result |= FONT_STYLE_CHANGED_MASK;
        if (isFlagSet(FOREGROUND_COLOR_CHANGED_MASK)) result |= FOREGROUND_COLOR_CHANGED_MASK;
      }
      setFlag(CHANGED_MASK, false);
      setFlag(RENDERERS_CHANGED_MASK, false);
      setFlag(FONT_STYLE_CHANGED_MASK, false);
      setFlag(FOREGROUND_COLOR_CHANGED_MASK, false);
    }
    return result;
  }

  private MarkupModel getMarkupModel() {
    return myModel;
  }

  @Override
  public void setLineSeparatorRenderer(LineSeparatorRenderer renderer) {
    LineSeparatorRenderer old = myLineSeparatorRenderer;
    myLineSeparatorRenderer = renderer;
    if (!Comparing.equal(old, renderer)) {
      fireChanged(true, false, false);
    }
  }

  @Override
  public LineSeparatorRenderer getLineSeparatorRenderer() {
    return myLineSeparatorRenderer;
  }

  @Override
  protected void registerInTree(int start, int end, boolean greedyToLeft, boolean greedyToRight, int layer) {
    // we store highlighters in MarkupModel
    ((MarkupModelEx)getMarkupModel()).addRangeHighlighter(this, start, end, greedyToLeft, greedyToRight, layer);
  }

  @Override
  protected void unregisterInTree() {
    if (!isValid()) return;
    // we store highlighters in MarkupModel
    getMarkupModel().removeHighlighter(this);
  }

  @Override
  public int getLayer() {
    RangeHighlighterTree.RHNode node = (RangeHighlighterTree.RHNode)(Object)myNode;
    return node == null ? -1 : node.myLayer;
  }

  @Override
  @NonNls
  public String toString() {
    return "RangeHighlighter: ("+getStartOffset()+","+getEndOffset()+"); layer:"+getLayer()+"; tooltip: "+getErrorStripeTooltip() + (isValid() ? "" : "(invalid)");
  }
}
