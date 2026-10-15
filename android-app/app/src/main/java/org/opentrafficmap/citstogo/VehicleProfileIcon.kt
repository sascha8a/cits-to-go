package org.opentrafficmap.citstogo

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.opentrafficmap.citstogo.srem.SremProfile

/** The icon of the station type of a vehicle profile, from the station type icons in images/station-types. */
@DrawableRes
fun SremProfile.iconRes(): Int = when (this) {
    SremProfile.PEDESTRIAN -> R.drawable.ic_station_type_pedestrian
    SremProfile.BICYCLE -> R.drawable.ic_station_type_cyclist
    SremProfile.MOPED -> R.drawable.ic_station_type_moped
    SremProfile.MOTORCYCLE -> R.drawable.ic_station_type_motorcycle
    SremProfile.PASSENGER_CAR -> R.drawable.ic_station_type_passenger_car
    SremProfile.PUBLIC_TRANSPORT_BUS -> R.drawable.ic_station_type_bus
    SremProfile.LIGHT_TRUCK -> R.drawable.ic_station_type_light_truck
    SremProfile.HEAVY_TRUCK -> R.drawable.ic_station_type_heavy_truck
    SremProfile.SPECIAL_VEHICLE -> R.drawable.ic_station_type_special_vehicles
    SremProfile.TRAM -> R.drawable.ic_station_type_tram
}

/** The icon of [profile]: its pictogram on its own dark rounded square, the same in light and dark mode. */
@Composable
fun VehicleProfileIcon(profile: SremProfile, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(profile.iconRes()),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}

/** The current vehicle profile as a field that opens a list of all profiles with their icons. */
@Composable
fun VehicleProfileSelector(
    profile: SremProfile,
    onProfileChange: (SremProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val chooseLabel = stringResource(R.string.vehicle_profile_choose)
    Box(modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = chooseLabel, role = Role.DropdownList) { expanded = true },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(ContextCompat.getColor(context, R.color.card))),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VehicleProfileIcon(profile, size = 48.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        stringResource(R.string.vehicle_profile_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                    )
                    Text(
                        profile.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                    Text(
                        stringResource(R.string.vehicle_profile_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    tint = Color(ContextCompat.getColor(context, R.color.primary)),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SremProfile.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayName) },
                    leadingIcon = { VehicleProfileIcon(option, size = 32.dp) },
                    trailingIcon = if (option == profile) {
                        { Icon(imageVector = Icons.Rounded.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        if (option != profile) onProfileChange(option)
                    },
                )
            }
        }
    }
}
