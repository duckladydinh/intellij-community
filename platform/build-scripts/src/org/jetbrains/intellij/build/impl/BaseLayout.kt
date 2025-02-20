// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.impl

import com.intellij.util.containers.MultiMap
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import java.util.*

/**
 * Describes layout of a plugin or the platform JARs in the product distribution
 */
open class BaseLayout {
  companion object {
    const val APP_JAR = "app.jar"
  }

  // one module can be packed into several JARs, that's why we have map "jar to modules" and not "module to jar"
  private val _jarToModules = TreeMap<String, MutableList<String>>()
  // only as guard for checkAndAssociateModuleNameWithJarPath - do not use it, because strictly speaking for one module maybe several JARs
  private val moduleNameToJarPath = HashMap<String, String>()

  /** JAR name (or path relative to 'lib' directory) to names of modules */
  val jarToModules: Map<String, List<String>>
    get() = Collections.unmodifiableMap(_jarToModules)

  /** artifact name to relative output path */
  internal var includedArtifacts: PersistentMap<String, String> = persistentMapOf()

  /** list of additional resources which should be included into the distribution */
  internal var resourcePaths: PersistentList<ModuleResourceData> = persistentListOf()

  /** module name to entries which should be excluded from its output */
  val moduleExcludes: MultiMap<String, String> = MultiMap.createLinked()

  @Suppress("SSBasedInspection")
  internal val includedProjectLibraries = ObjectOpenHashSet<ProjectLibraryData>()
  val includedModuleLibraries: MutableSet<ModuleLibraryData> = LinkedHashSet()

  /** module name to name of the module library */
  val excludedModuleLibraries: MultiMap<String, String> = MultiMap.createLinked()

  /** JAR name -> name of project library which content should be unpacked */
  val projectLibrariesToUnpack: MultiMap<String, String> = MultiMap.createLinked()
  val modulesWithExcludedModuleLibraries: MutableList<String> = mutableListOf()

  /** set of keys in {@link #moduleJars} which are set explicitly, not automatically derived from modules names */
  val explicitlySetJarPaths: MutableSet<String> = LinkedHashSet()

  val includedModuleNames: Collection<String>
    get() = _jarToModules.values.asSequence().flatten().distinct().toList()

  open fun withModule(moduleName: String, relativeJarPath: String) {
    checkAndAssociateModuleNameWithJarPath(moduleName, relativeJarPath)
    explicitlySetJarPaths.add(relativeJarPath)
  }

  private fun checkAndAssociateModuleNameWithJarPath(moduleName: String, relativeJarPath: String) {
    val previousJarPath = moduleNameToJarPath.putIfAbsent(moduleName, relativeJarPath)
    if (previousJarPath != null && moduleName != "intellij.maven.artifactResolver.common") {
      if (previousJarPath == relativeJarPath) {
        // already added
        return
      }

      // allow to put module to several JARs if JAR located in another dir
      // (e.g. intellij.spring.customNs packed into main JAR and customNs/customNs.jar)
      check(previousJarPath.contains('/') || relativeJarPath.contains('/')) {
        "$moduleName cannot be packed into $relativeJarPath because it is already configured to be packed into $previousJarPath"
      }
    }

    _jarToModules.computeIfAbsent(relativeJarPath) { mutableListOf() }.add(moduleName)
  }

  open fun withModule(moduleName: String) {
    checkAndAssociateModuleNameWithJarPath(moduleName, "${convertModuleNameToFileName(moduleName)}.jar")
  }

  protected fun withModuleImpl(moduleName: String, jarPath: String) {
    checkAndAssociateModuleNameWithJarPath(moduleName, jarPath)
  }

  fun withProjectLibraryUnpackedIntoJar(libraryName: String, jarName: String) {
    projectLibrariesToUnpack.putValue(jarName, libraryName)
  }

  fun excludeFromModule(moduleName: String, excludedPattern: String) {
    moduleExcludes.putValue(moduleName, excludedPattern)
  }

  fun withProjectLibrary(libraryName: String) {
    includedProjectLibraries.add(ProjectLibraryData(libraryName = libraryName, packMode = LibraryPackMode.MERGED))
  }

  fun withProjectLibrary(libraryName: String, packMode: LibraryPackMode) {
    includedProjectLibraries.add(ProjectLibraryData(libraryName = libraryName, packMode = packMode))
  }

  /**
   * Include the module library to the plugin distribution. Please note that it makes sense to call this method only
   * for additional modules which aren't copied directly to the 'lib' directory of the plugin distribution, because for ordinary modules
   * their module libraries are included into the layout automatically.
   * @param relativeOutputPath target path relative to 'lib' directory
   */
  fun withModuleLibrary(libraryName: String, moduleName: String, relativeOutputPath: String) {
    includedModuleLibraries.add(ModuleLibraryData(
      moduleName = moduleName,
      libraryName = libraryName,
      relativeOutputPath = relativeOutputPath))
  }
}

internal fun convertModuleNameToFileName(moduleName: String): String = moduleName.removePrefix("intellij.").replace('.', '-')

data class ModuleLibraryData(
  val moduleName: String,
  val libraryName: String,
  val relativeOutputPath: String = "",
)