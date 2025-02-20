// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.vcs.branch;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents information about single branch in certain VCS root.
 */
public interface BranchData {
  @NotNull
  String getPresentableRootName();

  @Nullable
  String getBranchName();
}