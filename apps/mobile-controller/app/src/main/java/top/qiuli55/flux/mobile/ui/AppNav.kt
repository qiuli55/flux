package top.qiuli55.flux.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import top.qiuli55.flux.mobile.data.ConnectionConfig
import top.qiuli55.flux.mobile.data.FluxEnv
import top.qiuli55.flux.mobile.ui.changes.ChangeDetailScreen
import top.qiuli55.flux.mobile.ui.changes.ChangeViewModel
import top.qiuli55.flux.mobile.ui.setup.SetupScreen
import top.qiuli55.flux.mobile.ui.setup.SetupViewModel
import top.qiuli55.flux.mobile.ui.tasks.TaskDetailScreen
import top.qiuli55.flux.mobile.ui.tasks.TaskDetailViewModel
import top.qiuli55.flux.mobile.ui.tasks.TaskListScreen
import top.qiuli55.flux.mobile.ui.tasks.TaskListViewModel
import top.qiuli55.flux.mobile.ui.terminal.TerminalListScreen
import top.qiuli55.flux.mobile.ui.terminal.TerminalListViewModel
import top.qiuli55.flux.mobile.ui.terminal.TerminalScreen
import top.qiuli55.flux.mobile.ui.terminal.TerminalViewModel
import top.qiuli55.flux.mobile.ui.theme.FluxColors

/** 路由名（避免在多个地方写裸字符串）。 */
private object Routes {
    const val SETUP = "setup"
    const val TASKS = "tasks"
    const val TASK = "task/{taskId}"
    const val CHANGE = "change/{changeId}"
    const val TERMINAL = "terminal"
    const val SESSION = "session/{sessionId}"

    fun task(id: String) = "task/$id"
    fun change(id: String) = "change/$id"
    fun session(id: String) = "session/$id"
}

/**
 * 应用根：先读一次本地配置，决定落到"连接设置"还是"任务列表"。
 *
 * 首次安装（没有令牌）不该先看到一个 401 的错误页——直接进设置页，一步到位。
 */
@Composable
fun FluxRoot() {
    val config by produceState<ConnectionConfig?>(initialValue = null) {
        value = FluxEnv.settings.config.first()
    }
    val loaded = config
    if (loaded == null) {
        Surface(color = FluxColors.bg, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingRow("启动中…") }
        }
        return
    }
    FluxNavHost(startDestination = if (loaded.isComplete) Routes.TASKS else Routes.SETUP)
}

@Composable
private fun FluxNavHost(startDestination: String) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.SETUP) {
            val vm: SetupViewModel = viewModel()
            val firstRun = startDestination == Routes.SETUP
            SetupScreen(
                viewModel = vm,
                onBack = if (firstRun) null else ({ navController.popBackStack() }),
                onSaved = {
                    // 首次配置保存后直接进任务列表，并把设置页从回退栈里去掉
                    if (firstRun) {
                        navController.navigate(Routes.TASKS) {
                            popUpTo(Routes.SETUP) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(Routes.TASKS) {
            val vm: TaskListViewModel = viewModel()
            TaskListScreen(
                viewModel = vm,
                onOpenTask = { taskId -> navController.navigate(Routes.task(taskId)) },
                onOpenSettings = { navController.navigate(Routes.SETUP) },
                onOpenTerminal = { navController.navigate(Routes.TERMINAL) },
            )
        }

        composable(
            route = Routes.TASK,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
        ) { entry ->
            val taskId = entry.arguments?.getString("taskId").orEmpty()
            val vm: TaskDetailViewModel = viewModel(
                factory = viewModelFactory { initializer { TaskDetailViewModel(taskId) } },
            )
            TaskDetailScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenChange = { changeId -> navController.navigate(Routes.change(changeId)) },
            )
        }

        composable(
            route = Routes.CHANGE,
            arguments = listOf(navArgument("changeId") { type = NavType.StringType }),
        ) { entry ->
            val changeId = entry.arguments?.getString("changeId").orEmpty()
            val vm: ChangeViewModel = viewModel(
                factory = viewModelFactory { initializer { ChangeViewModel(changeId) } },
            )
            ChangeDetailScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.TERMINAL) {
            val vm: TerminalListViewModel = viewModel()
            TerminalListScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenSession = { sessionId -> navController.navigate(Routes.session(sessionId)) },
            )
        }

        composable(
            route = Routes.SESSION,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            val vm: TerminalViewModel = viewModel(
                factory = viewModelFactory { initializer { TerminalViewModel(sessionId) } },
            )
            TerminalScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}