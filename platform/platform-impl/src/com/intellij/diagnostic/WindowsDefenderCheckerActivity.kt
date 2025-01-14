// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.diagnostic

import com.intellij.CommonBundle
import com.intellij.ide.BrowserUtil
import com.intellij.ide.IdeBundle
import com.intellij.notification.*
import com.intellij.notification.impl.NotificationFullContent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectPostStartupActivity
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.NlsContexts
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

internal class WindowsDefenderCheckerActivity : ProjectPostStartupActivity {
  override suspend fun execute(project: Project) {
    val app = ApplicationManager.getApplication()
    if (app.isUnitTestMode) {
      return
    }

    val windowsDefenderChecker = WindowsDefenderChecker.getInstance()
    if (windowsDefenderChecker.isVirusCheckIgnored(project)) return

    val checkResult = windowsDefenderChecker.checkWindowsDefender(project)
    if (checkResult.status == WindowsDefenderChecker.RealtimeScanningStatus.SCANNING_ENABLED &&
        checkResult.pathStatus.any { !it.value }) {

      val nonExcludedPaths = checkResult.pathStatus.filter { !it.value }.keys
      val notification = WindowsDefenderNotification(
        DiagnosticBundle.message("virus.scanning.warn.title"),
        windowsDefenderChecker.getNotificationText(nonExcludedPaths),
        nonExcludedPaths
      )
      notification.isImportant = true
      notification.collapseDirection = Notification.CollapseActionsDirection.KEEP_LEFTMOST
      windowsDefenderChecker.configureActions(project, notification)

      withContext(Dispatchers.EDT) {
        notification.notify(project)
      }
    }
  }
}

internal class WindowsDefenderNotification(@NlsContexts.NotificationTitle title: String, @NlsContexts.NotificationContent text: String, val paths: Collection<Path>) :
  Notification(NotificationGroup.createIdWithTitle("System Health", IdeBundle.message("notification.group.system.health")), title, text, NotificationType.WARNING), NotificationFullContent

internal class WindowsDefenderFixAction(val paths: Collection<Path>) : NotificationAction(DiagnosticBundle.message("virus.scanning.fix.action")) {
  override fun actionPerformed(e: AnActionEvent, notification: Notification) {
    val rc = Messages.showDialog(
      e.project,
      DiagnosticBundle.message("virus.scanning.fix.explanation", ApplicationNamesInfo.getInstance().fullProductName,
                               WindowsDefenderChecker.getInstance().configurationInstructionsUrl),
      DiagnosticBundle.message("virus.scanning.fix.title"),
      arrayOf(
        DiagnosticBundle.message("virus.scanning.fix.automatically"),
        DiagnosticBundle.message("virus.scanning.fix.manually"),
        CommonBundle.getCancelButtonText()
      ),
      0,
      null)
    when (rc) {
      0 -> {
        notification.expire()
        ApplicationManager.getApplication().executeOnPooledThread {
          if (WindowsDefenderChecker.getInstance().runExcludePathsCommand(e.project, paths)) {
            UIUtil.invokeLaterIfNeeded {
              Notifications.Bus.notifyAndHide(
                Notification(NotificationGroup.createIdWithTitle("System Health", IdeBundle.message("notification.group.system.health")),
                             "", DiagnosticBundle.message("virus.scanning.fix.success.notification"), NotificationType.INFORMATION), e.project)
            }
          }
        }
      }
      1 -> BrowserUtil.browse(WindowsDefenderChecker.getInstance().configurationInstructionsUrl)
    }
  }
}
