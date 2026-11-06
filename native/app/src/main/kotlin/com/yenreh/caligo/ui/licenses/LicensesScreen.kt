package com.yenreh.caligo.ui.licenses

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yenreh.caligo.AppInfo
import com.yenreh.caligo.R
import com.yenreh.caligo.ui.lab.LabDivider
import com.yenreh.caligo.ui.lab.LabListTile
import com.yenreh.caligo.ui.lab.LabTopBar
import com.yenreh.caligo.ui.theme.LabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The catalog, read off the main thread; null while it loads */
@Composable
private fun rememberLicenses(): List<PackageLicenses>? {
    val context = LocalContext.current
    val packages by produceState<List<PackageLicenses>?>(null, context) {
        value = LicenseCatalog.load(context.applicationContext)
    }
    return packages
}

/** Flutter's license page: the app, then every package with how many licenses it has */
@Composable
fun LicensesRoute(appVersion: String, onBack: () -> Unit, onPackage: (String) -> Unit, modifier: Modifier = Modifier) {
    LicensesScreen(rememberLicenses(), appVersion, onBack, onPackage, modifier)
}

@Composable
fun LicensesScreen(
    packages: List<PackageLicenses>?,
    appVersion: String,
    onBack: () -> Unit,
    onPackage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LabTheme.palette
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = p.paper,
        contentWindowInsets = WindowInsets(0),
        topBar = { LabTopBar(stringResource(R.string.licenses_page_title), onBack = onBack) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .testTag("licenses_list"),
                contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues(),
            ) {
                item { AboutProgram(appVersion) }
                if (packages == null) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = p.accent)
                        }
                    }
                } else {
                    items(packages, key = { it.name }) { item ->
                        LabListTile(
                            title = item.name,
                            subtitle = pluralStringResource(R.plurals.license_count, item.licenses.size, item.licenses.size),
                            modifier = Modifier.clickable(role = Role.Button) { onPackage(item.name) },
                        )
                    }
                }
            }
        }
    }
}

/** Name, version and who made it, above the packages */
@Composable
private fun AboutProgram(appVersion: String) {
    val type = MaterialTheme.typography
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(AppInfo.NAME, style = type.headlineSmall, textAlign = TextAlign.Center)
        if (appVersion.isNotEmpty()) {
            Text(appVersion, style = type.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 18.dp))
        }
        Text(stringResource(R.string.made_by), style = type.bodySmall, textAlign = TextAlign.Center)
    }
}

/** One package's licenses, each after a rule, in Flutter's paragraphs */
@Composable
fun LicenseRoute(name: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val licenses = rememberLicenses()?.firstOrNull { it.name == name }?.licenses
    val paragraphs by produceState<List<List<LicenseParagraph>>?>(null, licenses) {
        value = licenses?.let { texts -> withContext(Dispatchers.Default) { texts.map(::licenseParagraphs) } }
    }
    LicenseScreen(name, licenses?.size ?: 0, paragraphs, onBack, modifier)
}

@Composable
fun LicenseScreen(
    name: String,
    count: Int,
    licenses: List<List<LicenseParagraph>>?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LabTheme.palette
    val style = MaterialTheme.typography.bodySmall
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = p.paper,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            LabTopBar(name, onBack = onBack, subtitle = pluralStringResource(R.plurals.license_count, count, count))
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(p.paper),
            contentAlignment = Alignment.TopCenter,
        ) {
            val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .testTag("license_text"),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp + bottom),
            ) {
                licenses?.forEach { paragraphs ->
                    item { LabDivider(Modifier.padding(18.dp)) }
                    items(paragraphs) { paragraph ->
                        if (paragraph.indent == LicenseParagraph.CENTERED) {
                            Text(
                                paragraph.text,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                style = style.copy(fontWeight = FontWeight.Bold),
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            Text(
                                paragraph.text,
                                modifier = Modifier.padding(top = 8.dp, start = 16.dp * paragraph.indent),
                                style = style,
                            )
                        }
                    }
                }
                if (licenses == null) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(40.dp), color = p.accent)
                        }
                    }
                }
            }
        }
    }
}
