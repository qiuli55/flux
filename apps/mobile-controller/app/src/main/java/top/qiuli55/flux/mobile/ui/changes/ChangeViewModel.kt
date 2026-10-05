package top.qiuli55.flux.mobile.ui.changes

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.qiuli55.flux.mobile.data.Change
import top.qiuli55.flux.mobile.data.FluxApi
import top.qiuli55.flux.mobile.data.FluxApiException
import top.qiuli55.flux.mobile.ui.FluxViewModel

data class ChangeUiState(
    val loading: Boolean = true,
    val change: Change? = null,
    val error: String? = null,
    val notice: String? = null,
    val busy: Boolean = false,
)

/** 单条提案：看 diff / 新旧内容，执行 接受 → 应用 / 拒绝 / 回滚。 */
class ChangeViewModel(private val changeId: String) : FluxViewModel() {

    private val _state = MutableStateFlow(ChangeUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null, error = null)
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(change = api().getChange(changeId), loading = false, error = null)
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    fun accept() = action("已批准") { api -> api.acceptChanges(listOf(changeId)).first() }

    /** 应用 = 真正落盘（会跑测试并按结果决定是否回滚，失败原因在 apply_error 里）。 */
    fun apply() = action("已提交落盘") { api -> api.applyChanges(listOf(changeId)).first() }

    fun reject() = action("已拒绝") { api -> api.rejectChanges(listOf(changeId), reason = null).first() }

    fun rollback() = action("已回滚") { api -> api.rollbackChange(changeId) }

    private fun action(hint: String, block: suspend (FluxApi) -> Change) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, notice = null)
            try {
                val change = block(api())
                val message = when {
                    change.applyError != null -> "落盘失败：${change.applyError}"
                    else -> "$hint：${change.status}"
                }
                _state.value = _state.value.copy(busy = false, change = change, notice = message)
            } catch (e: FluxApiException) {
                _state.value = _state.value.copy(busy = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = e.message ?: "操作失败")
            }
        }
    }
}