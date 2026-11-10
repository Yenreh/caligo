package com.yenreh.caligo.ui.stops

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yenreh.caligo.R
import com.yenreh.caligo.domain.AwaitedBus
import com.yenreh.caligo.domain.FadingArrivals
import com.yenreh.caligo.domain.Wait
import com.yenreh.caligo.domain.WaitKey
import com.yenreh.caligo.ui.lab.LabFilledButton
import com.yenreh.caligo.ui.lab.LabTextButton
import com.yenreh.caligo.ui.theme.LabTheme
import com.yenreh.caligo.waits.NotificationAccess
import com.yenreh.caligo.waits.WaitControl
import com.yenreh.caligo.waits.WaitStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Why a bell did not start a wait */
enum class WaitMessage { NEEDS_NOTIFICATIONS, CAP_REACHED }

/**
 * The bells of the arrival rows: one tap starts waiting for that bus, and
 * the bell fills; another stops. The first asks for the notification
 * permission; without it, or with the app's notifications off, no wait
 * starts and [message] says why.
 */
class WaitBells(
    private val control: WaitControl,
    private val access: NotificationAccess,
    private val scope: CoroutineScope,
) {
    val running: StateFlow<Set<WaitKey>> get() = control.running

    var message by mutableStateOf<WaitMessage?>(null)
        private set

    fun toggle(bus: AwaitedBus, seed: FadingArrivals?) {
        if (bus.key in control.running.value) {
            control.stop(bus.key)
            return
        }
        scope.launch {
            if (!access.granted() && !access.request()) {
                message = WaitMessage.NEEDS_NOTIFICATIONS
                return@launch
            }
            if (!access.enabled()) {
                message = WaitMessage.NEEDS_NOTIFICATIONS
                return@launch
            }
            if (control.start(bus, seed) == WaitStart.CAP_REACHED) message = WaitMessage.CAP_REACHED
        }
    }

    fun openSettings() {
        message = null
        access.openSettings()
    }

    fun dismiss() {
        message = null
    }
}

/** The bells of the rows below; none shows without them */
val LocalWaitBells = staticCompositionLocalOf<WaitBells?> { null }

/** [content] with bells on its arrival rows, and what they have to say */
@Composable
fun WaitBellsHost(control: WaitControl, access: NotificationAccess, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val bells = remember(control, access, scope) { WaitBells(control, access, scope) }
    CompositionLocalProvider(LocalWaitBells provides bells, content = content)
    bells.message?.let { WaitMessageDialog(it, onSettings = bells::openSettings, onDismiss = bells::dismiss) }
}

/**
 * The bell of a row for [bus], a 48 dp button: filled while that bus is
 * waited for. Null [bus] is a row whose stop is not known yet, as those
 * kept from the last run in an area: the bell shows, but waits for the
 * first answer. Nothing without [LocalWaitBells].
 */
@Composable
fun WaitBell(bus: AwaitedBus?, line: String, seed: FadingArrivals?) {
    val bells = LocalWaitBells.current ?: return
    val running by bells.running.collectAsStateWithLifecycle()
    val on = bus != null && bus.key in running
    val p = LabTheme.palette
    val description = stringResource(if (on) R.string.wait_stop else R.string.wait_start, line)
    Box(
        Modifier
            .size(BELL_SIZE)
            .clickable(enabled = bus != null, role = Role.Button) { bus?.let { bells.toggle(it, seed) } }
            .semantics { contentDescription = description }
            .testTag(if (on) "wait_bell_on" else "wait_bell"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(if (on) R.drawable.ic_notifications else R.drawable.ic_notifications_none),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = when {
                on -> p.accent
                bus == null -> p.ruleSoft
                else -> p.muted
            },
        )
    }
}

/** The room a bell takes, kept on rows that have none, so countdowns line up */
val BELL_SIZE = 48.dp

/** Why the wait did not start, with the way to the notification settings when that is why */
@Composable
private fun WaitMessageDialog(message: WaitMessage, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val p = LabTheme.palette
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 40.dp, vertical = 24.dp)
                .widthIn(min = 280.dp, max = 560.dp)
                .fillMaxWidth()
                .background(p.paper)
                .border(1.dp, p.rule)
                .padding(24.dp)
                .testTag("wait_message"),
        ) {
            Text(
                text = when (message) {
                    WaitMessage.NEEDS_NOTIFICATIONS -> stringResource(R.string.wait_needs_notifications)
                    WaitMessage.CAP_REACHED -> pluralStringResource(R.plurals.wait_cap_reached, Wait.CAP, Wait.CAP)
                },
                style = MaterialTheme.typography.bodyLarge.copy(color = p.ink),
            )
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                LabTextButton(onClick = onDismiss) { Text(stringResource(R.string.wait_close)) }
                if (message == WaitMessage.NEEDS_NOTIFICATIONS) {
                    Spacer(Modifier.width(8.dp))
                    LabFilledButton(onClick = onSettings) { Text(stringResource(R.string.wait_open_settings)) }
                }
            }
        }
    }
}
