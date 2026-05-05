package com.github.jelenadjuric01.gitmuse.notification

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/**
 * Thin wrapper over [NotificationGroupManager]. The matching `<notificationGroup id="Git Muse">`
 * is registered in `plugin.xml`.
 *
 * All async results from the LLM round-trip surface through here — never via modal dialogs.
 */
object Notifier {

    const val GROUP_ID: String = "Git Muse"
    private const val SETTINGS_ID: String = "com.github.jelenadjuric01.gitmuse.settings"
    private const val TITLE: String = "Git Muse"

    fun info(project: Project, content: String) =
        notify(project, content, NotificationType.INFORMATION)

    fun warn(project: Project, content: String) =
        notify(project, content, NotificationType.WARNING)

    /**
     * @param withSettingsAction adds a "Configure…" link that opens
     *                           Settings → Tools → Git Muse directly.
     */
    fun error(project: Project, content: String, withSettingsAction: Boolean = false) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(TITLE, content, NotificationType.ERROR)
        if (withSettingsAction) {
            notification.addAction(
                NotificationAction.createSimple("Configure…") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, SETTINGS_ID)
                }
            )
        }
        notification.notify(project)
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(TITLE, content, type)
            .notify(project)
    }
}
