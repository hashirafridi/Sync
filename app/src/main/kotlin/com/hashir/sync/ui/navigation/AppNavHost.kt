package com.hashir.sync.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hashir.sync.feature.dashboard.DashboardScreen
import com.hashir.sync.feature.downloader.DownloaderScreen
import com.hashir.sync.feature.editor.EditorMode
import com.hashir.sync.feature.editor.PostEditorScreen
import com.hashir.sync.feature.page.PageDetailScreen
import com.hashir.sync.feature.queue.QueueScreen
import com.hashir.sync.feature.settings.SettingsScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val QUEUE = "queue"
    const val SETTINGS = "settings"

    const val ARG_PAGE_ID = "pageId"
    const val ARG_MODE = "mode"
    const val ARG_DOWNLOAD_ID = "downloadId"

    /** Sentinel for "no download", since nav arguments cannot be null longs. */
    const val NO_DOWNLOAD = -1L

    const val PAGE_DETAIL = "page/{$ARG_PAGE_ID}"
    const val DOWNLOADER = "download/{$ARG_PAGE_ID}"
    const val EDITOR = "editor/{$ARG_PAGE_ID}?$ARG_MODE={$ARG_MODE}&$ARG_DOWNLOAD_ID={$ARG_DOWNLOAD_ID}"

    fun pageDetail(pageId: String): String = "page/$pageId"

    fun downloader(pageId: String): String = "download/$pageId"

    fun editor(
        pageId: String,
        mode: EditorMode,
        downloadId: Long = NO_DOWNLOAD,
    ): String = "editor/$pageId?$ARG_MODE=${mode.name}&$ARG_DOWNLOAD_ID=$downloadId"
}

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = Routes.DASHBOARD,
    ) {
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onPageSelected = { pageId -> navController.navigate(Routes.pageDetail(pageId)) },
                onOpenQueue = { navController.navigate(Routes.QUEUE) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.QUEUE) {
            QueueScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.PAGE_DETAIL,
            arguments = listOf(navArgument(Routes.ARG_PAGE_ID) { type = NavType.StringType }),
        ) {
            PageDetailScreen(
                onBack = { navController.popBackStack() },
                onCompose = { pageId, mode -> navController.navigate(Routes.editor(pageId, mode)) },
                onDownload = { pageId -> navController.navigate(Routes.downloader(pageId)) },
            )
        }

        composable(
            route = Routes.DOWNLOADER,
            arguments = listOf(navArgument(Routes.ARG_PAGE_ID) { type = NavType.StringType }),
        ) {
            DownloaderScreen(
                onBack = { navController.popBackStack() },
                onDownloadReady = { pageId, downloadId ->
                    navController.navigate(
                        Routes.editor(pageId, EditorMode.MEDIA, downloadId),
                    ) {
                        // Drop the downloader from the back stack: going "back" from the
                        // editor should return to the Page, not re-open a finished download.
                        popUpTo(Routes.DOWNLOADER) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument(Routes.ARG_PAGE_ID) { type = NavType.StringType },
                navArgument(Routes.ARG_MODE) {
                    type = NavType.StringType
                    defaultValue = EditorMode.MEDIA.name
                },
                navArgument(Routes.ARG_DOWNLOAD_ID) {
                    type = NavType.LongType
                    defaultValue = Routes.NO_DOWNLOAD
                },
            ),
        ) {
            PostEditorScreen(
                onBack = { navController.popBackStack() },
                onPublished = {
                    // Pops to the Page explicitly rather than just one step back. The
                    // depth varies -- reaching the editor through the downloader leaves a
                    // different stack than picking media directly -- so a bare
                    // popBackStack lands somewhere different depending on the route taken.
                    val returned = navController.popBackStack(Routes.PAGE_DETAIL, inclusive = false)
                    if (!returned) navController.popBackStack()
                },
            )
        }
    }
}
