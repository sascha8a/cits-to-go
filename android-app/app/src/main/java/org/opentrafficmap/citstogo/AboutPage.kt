package org.opentrafficmap.citstogo

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.opentrafficmap.citstogo.update.CodebergAppUpdateChecker

@Composable
fun AboutPage() {
    val context = LocalContext.current

    fun open(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIdentityCard()
        DeveloperCard()
        ProjectInfoCard()
        LinksCard(onOpenLink = { open(it) })
        ThanksCard()
        LicencesCard(onOpenLink = { open(it) })
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun AppIdentityCard() {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(ContextCompat.getColor(context, R.color.card)),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color(ContextCompat.getColor(context, R.color.ic_launcher_background))),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = stringResource(R.string.cd_about_app_logo),
                    modifier = Modifier.size(80.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                )
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                )
                Text(
                    text = stringResource(R.string.about_app_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(ContextCompat.getColor(context, R.color.secondary)),
                )
            }
        }
    }
}

@Composable
private fun DeveloperCard() {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(ContextCompat.getColor(context, R.color.card)),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(ContextCompat.getColor(context, R.color.primary_container))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = Color(ContextCompat.getColor(context, R.color.primary)),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_developer_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                    Text(
                        text = stringResource(R.string.author_name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(ContextCompat.getColor(context, R.color.secondary)),
                    )
                    Text(
                        text = stringResource(R.string.author_email),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectInfoCard() {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(ContextCompat.getColor(context, R.color.card)),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(ContextCompat.getColor(context, R.color.primary_container))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = Color(ContextCompat.getColor(context, R.color.primary)),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_project_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                }
            }

            HorizontalDivider(
                color = Color(ContextCompat.getColor(context, R.color.divider)),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_project_paragraph1),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                )
                Text(
                    text = stringResource(R.string.about_project_paragraph2),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                )
                Text(
                    text = stringResource(R.string.about_project_paragraph3),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                )
            }
        }
    }
}

@Composable
private fun LinksCard(onOpenLink: (String) -> Unit) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(ContextCompat.getColor(context, R.color.card)),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(ContextCompat.getColor(context, R.color.primary_container))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        tint = Color(ContextCompat.getColor(context, R.color.primary)),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_links_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                }
            }

            HorizontalDivider(
                color = Color(ContextCompat.getColor(context, R.color.divider)),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LinkRow(
                    label = stringResource(R.string.about_source_code_label),
                    url = CodebergAppUpdateChecker.REPOSITORY_URL,
                    onOpenLink = onOpenLink,
                )
                HorizontalDivider(
                    color = Color(ContextCompat.getColor(context, R.color.divider)),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                LinkRow(
                    label = stringResource(R.string.about_opentrafficmap_label),
                    url = "https://opentrafficmap.org",
                    onOpenLink = onOpenLink,
                )
            }
        }
    }
}

@Composable
private fun LinkRow(
    label: String,
    url: String,
    onOpenLink: (String) -> Unit,
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable { onOpenLink(url) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color(ContextCompat.getColor(context, R.color.on_surface)),
            )
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                maxLines = 1,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Link,
                contentDescription = stringResource(R.string.cd_open_external_link),
                tint = Color(ContextCompat.getColor(context, R.color.primary)),
                modifier = Modifier.size(18.dp),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ThanksCard() {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(ContextCompat.getColor(context, R.color.primary_container)),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(ContextCompat.getColor(context, R.color.success_container))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = Color(ContextCompat.getColor(context, R.color.success)),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_thanks_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                    Text(
                        text = stringResource(R.string.about_thanks_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(ContextCompat.getColor(context, R.color.secondary)),
                    )
                }
            }
        }
    }
}

private class Library(val name: String, val licence: String, val url: String)

/** The libraries the map brings along, from the resolved dependencies of MapLibre Native Android. */
private val mapLibraries = listOf(
    Library("MapLibre Native Android", "BSD 2-Clause", "https://github.com/maplibre/maplibre-native"),
    Library("MapLibre Android Gestures", "BSD 2-Clause", "https://github.com/maplibre/maplibre-gestures-android"),
    Library("MapLibre Java GeoJSON and Turf", "Apache 2.0", "https://github.com/maplibre/maplibre-java"),
    Library("OkHttp", "Apache 2.0", "https://square.github.io/okhttp/"),
    Library("Public Suffix List, in OkHttp", "MPL 2.0", "https://publicsuffix.org/"),
    Library("Okio", "Apache 2.0", "https://github.com/square/okio"),
    Library("Timber", "Apache 2.0", "https://github.com/JakeWharton/timber"),
    Library("Gson", "Apache 2.0", "https://github.com/google/gson"),
    Library("kotlinx.coroutines", "Apache 2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    Library(
        "AndroidX, Jetpack Compose and Kotlin",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx",
    ),
)

@Composable
private fun LicencesCard(onOpenLink: (String) -> Unit) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(ContextCompat.getColor(context, R.color.card)),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(ContextCompat.getColor(context, R.color.primary_container))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                        tint = Color(ContextCompat.getColor(context, R.color.primary)),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_licences_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                }
            }

            HorizontalDivider(
                color = Color(ContextCompat.getColor(context, R.color.divider)),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Text(
                text = stringResource(R.string.about_licences_map_libraries),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(ContextCompat.getColor(context, R.color.on_surface)),
            )
            Column {
                mapLibraries.forEach { library ->
                    LinkRow(label = "${library.name} (${library.licence})", url = library.url, onOpenLink = onOpenLink)
                }
            }

            HorizontalDivider(
                color = Color(ContextCompat.getColor(context, R.color.divider)),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Text(
                text = stringResource(R.string.about_licences_map_data),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(ContextCompat.getColor(context, R.color.on_surface)),
            )
            Column {
                LinkRow(label = "OpenFreeMap", url = "https://openfreemap.org", onOpenLink = onOpenLink)
                LinkRow(label = "© OpenMapTiles", url = "https://www.openmaptiles.org/", onOpenLink = onOpenLink)
                LinkRow(
                    label = "© OpenStreetMap contributors (ODbL)",
                    url = "https://www.openstreetmap.org/copyright",
                    onOpenLink = onOpenLink,
                )
            }

            HorizontalDivider(
                color = Color(ContextCompat.getColor(context, R.color.divider)),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Column {
                LicenceText(R.string.about_licence_maplibre_native, R.raw.maplibre_native_android_licenses)
                LicenceText(R.string.about_licence_maplibre_native_bundled, R.raw.maplibre_native_bundled_licenses)
                LicenceText(R.string.about_licence_maplibre_gestures, R.raw.maplibre_gestures_license)
                LicenceText(R.string.about_licence_apache, R.raw.apache_license_2_0)
            }
        }
    }
}

/** The full text of a bundled licence, which binary distributions have to reproduce. */
@Composable
private fun LicenceText(labelResId: Int, rawResId: Int) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelResId),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = Color(ContextCompat.getColor(context, R.color.on_surface)),
        )
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.cd_hide_licence_text else R.string.cd_show_licence_text,
            ),
            tint = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
            modifier = Modifier.size(22.dp),
        )
    }
    if (expanded) {
        val text = remember(rawResId) {
            context.resources.openRawResource(rawResId).bufferedReader().use { it.readText() }
        }
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
        )
    }
}
