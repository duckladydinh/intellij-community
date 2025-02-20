// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.roots.impl;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.*;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.RootsChangeRescanningInfo;
import com.intellij.openapi.roots.FileIndex;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.ex.ProjectRootManagerEx;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.java.JavaSourceRootType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class DirectoryIndexTestCase extends HeavyPlatformTestCase {
  protected DirectoryIndexImpl myIndex;
  protected ProjectFileIndex myFileIndex;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myIndex = (DirectoryIndexImpl)DirectoryIndex.getInstance(myProject);
    myFileIndex = ProjectRootManager.getInstance(myProject).getFileIndex();
  }

  @Override
  protected void tearDown() throws Exception {
    myFileIndex = null;
    myIndex = null;
    super.tearDown();
  }

  protected void assertNotInProject(VirtualFile file) {
    DirectoryInfo info = myIndex.getInfoForFile(file);
    assertFalse(info.toString(), myFileIndex.isInProject(file));
    assertFalse(info.toString(), info.isExcluded(file));
    assertNull(info.toString(), info.getUnloadedModuleName());
  }

  protected void assertExcluded(@NotNull VirtualFile file, Module module) {
    DirectoryInfo info = myIndex.getInfoForFile(file);
    assertTrue(file + " " + info, myFileIndex.isExcluded(file));
    assertNull(file + " " + info, info.getUnloadedModuleName());
    assertEquals(module, myFileIndex.getModuleForFile(file, false));
    assertFalse(myFileIndex.isInSource(file));
    assertFalse(myFileIndex.isInSourceContent(file));
    assertFalse(myFileIndex.isInTestSourceContent(file));
    assertFalse(myFileIndex.isUnderSourceRootOfType(file, ContainerUtil.set(JavaSourceRootType.SOURCE, JavaSourceRootType.TEST_SOURCE)));
  }

  protected void assertInLibrarySources(VirtualFile file, Module module) {
    assertTrue(file.getPresentableUrl(), myFileIndex.isInLibrarySource(file));
    assertEquals(file.getPresentableUrl(), module, myFileIndex.getModuleForFile(file));
  }

  protected void assertNotInLibrarySources(VirtualFile file, Module module) {
    assertFalse(file.getPresentableUrl(), myFileIndex.isInLibrarySource(file));
    assertEquals(file.getPresentableUrl(), module, myFileIndex.getModuleForFile(file));
  }

  protected DirectoryInfo assertInProject(VirtualFile file) {
    assertTrue(file.toString(), myFileIndex.isInProject(file));
    DirectoryInfo info = myIndex.getInfoForFile(file);
    assertNull(info.toString(), info.getUnloadedModuleName());
    myIndex.assertConsistency(info);
    return info;
  }

  protected void assertNotExcluded(VirtualFile file) {
    assertFalse(myFileIndex.isExcluded(file));
  }

  protected void assertExcludedFromProject(VirtualFile file) {
    assertExcluded(file, null);
  }

  protected void assertIteratedContent(Module module, @Nullable List<VirtualFile> mustContain, @Nullable List<VirtualFile> mustNotContain) {
    assertIteratedContent(ModuleRootManager.getInstance(module).getFileIndex(), mustContain, mustNotContain);
    assertIteratedContent(myFileIndex, mustContain, mustNotContain);
  }

  protected void assertIndexableContent(@Nullable List<VirtualFile> mustContain, @Nullable List<VirtualFile> mustNotContain) {
    final Set<VirtualFile> collected = new HashSet<>();
    FileBasedIndex.getInstance().iterateIndexableFiles(fileOrDir -> {
      if (!collected.add(fileOrDir)) {
        fail(fileOrDir + " visited twice");
      }
      return true;
    }, getProject(), new EmptyProgressIndicator());
    if (mustContain != null) assertContainsElements(collected, mustContain);
    if (mustNotContain != null) assertDoesntContain(collected, mustNotContain);
  }

  protected void fireRootsChanged() {
    ApplicationManager.getApplication().runWriteAction(() -> ProjectRootManagerEx.getInstanceEx(getProject()).
      makeRootsChange(EmptyRunnable.getInstance(), RootsChangeRescanningInfo.NO_RESCAN_NEEDED));
  }

  protected static void assertIteratedContent(@NotNull FileIndex fileIndex,
                                              @Nullable List<VirtualFile> mustContain,
                                              @Nullable List<VirtualFile> mustNotContain) {
    final Set<VirtualFile> collected = new HashSet<>();
    fileIndex.iterateContent(fileOrDir -> {
      if (!collected.add(fileOrDir)) {
        fail(fileOrDir + " visited twice");
      }
      return true;
    });
    if (mustContain != null) assertContainsElements(collected, mustContain);
    if (mustNotContain != null) assertDoesntContain(collected, mustNotContain);
  }
  protected static void assertIteratedContent(@NotNull FileIndex fileIndex,
                                              @NotNull VirtualFile root,
                                              @Nullable List<VirtualFile> mustContain,
                                              @Nullable List<VirtualFile> mustNotContain) {
    final Set<VirtualFile> collected = new HashSet<>();
    fileIndex.iterateContentUnderDirectory(root, fileOrDir -> {
      if (!collected.add(fileOrDir)) {
        fail(fileOrDir + " visited twice");
      }
      return true;
    });
    if (mustContain != null) assertContainsElements(collected, mustContain);
    if (mustNotContain != null) assertDoesntContain(collected, mustNotContain);
  }

  @NotNull
  protected static Module createJavaModuleWithContent(@NotNull Project project, @NotNull String name, @NotNull VirtualFile contentRoot) {
    ModuleType<?> type = ModuleTypeManager.getInstance().findByID(ModuleTypeId.JAVA_MODULE);
    return WriteCommandAction.writeCommandAction(project).compute(() -> {
      ModifiableModuleModel moduleModel = ModuleManager.getInstance(project).getModifiableModel();
      Module module = moduleModel.newModule(contentRoot.toNioPath().resolve(name + ".iml"), type.getId());
      moduleModel.commit();
      assertNotNull(module);
      PsiTestUtil.addContentRoot(module, contentRoot);
      return module;
    });
  }
}
