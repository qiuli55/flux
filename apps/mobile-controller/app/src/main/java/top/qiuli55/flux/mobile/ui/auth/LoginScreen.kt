package top.qiuli55.flux.mobile.ui.auth

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import top.qiuli55.flux.mobile.data.AccountSession
import top.qiuli55.flux.mobile.data.CloudAuthBus
import top.qiuli55.flux.mobile.ui.ErrorBanner
import top.qiuli55.flux.mobile.ui.fluxFieldColors
import top.qiuli55.flux.mobile.ui.theme.FluxColors

/**
 * 登录 Flux：邮箱/用户名 + 密码，或走 GitHub OAuth。
 *
 * 登录只和账号云（B 机）打交道；设备列表由下一页负责，Flux 本体地址在选中设备后才确定。
 */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onSignedIn: (AccountSession) -> Unit,
    onManualConnect: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        CloudAuthBus.codes.collectLatest { code ->
            viewModel.exchangeCode(code, onSignedIn)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 48.dp),
    ) {
        Text("Flux", style = MaterialTheme.typography.headlineMedium, color = FluxColors.text)
        Spacer(Modifier.height(6.dp))
        Text(
            "登录后，同一个账号下的电脑和服务器都会出现在设备列表里。",
            style = MaterialTheme.typography.bodySmall,
            color = FluxColors.text3,
        )
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = state.identifier,
            onValueChange = viewModel::onIdentifierChange,
            label = { Text("邮箱或用户名") },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            colors = fluxFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("密码") },
            singleLine = true,
            enabled = !state.busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = fluxFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            ErrorBanner(state.error.orEmpty())
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { viewModel.login(onSignedIn) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            } else {
                Text("登录")
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.githubUrl()))
                context.startActivity(intent)
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("用 GitHub 登录")
        }

        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "GitHub 登录会在浏览器里完成，授权后自动回到本应用。",
                style = MaterialTheme.typography.bodySmall,
                color = FluxColors.text3,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onManualConnect,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("手动连接（地址 + 令牌）")
        }
    }
}
