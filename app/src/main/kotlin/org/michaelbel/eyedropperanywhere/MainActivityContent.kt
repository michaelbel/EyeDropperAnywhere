package org.michaelbel.eyedropperanywhere

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun MainActivityContent(
    overlayAllowed: Boolean,
    serviceRunning: Boolean,
    onStartOrStop: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onAddTile: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.screen_title)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
        ) {
            item {
                SegmentedListItem(
                    onClick = onStartOrStop,
                    shapes = ListItemDefaults.shapes(shape = RoundedCornerShape(24.dp)),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    supportingContent = {
                        Text(text = stringResource(if (serviceRunning) R.string.stop_description else R.string.start_description))
                    },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (serviceRunning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_dropper_eye),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                ) {
                    Text(text = stringResource(if (serviceRunning) R.string.stop_title else R.string.start_title))
                }
            }
            item {
                Text(
                    text = stringResource(R.string.projection_explanation),
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 24.dp,),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                SegmentedListItem(
                    onClick = onAddTile,
                    modifier = Modifier.padding(bottom = ListItemDefaults.SegmentedGap),
                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    supportingContent = {
                        Text(text = stringResource(R.string.add_tile_description))
                    },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_tile_small),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                ) {
                    Text(text = stringResource(R.string.add_tile_title))
                }
            }
            item {
                SegmentedListItem(
                    onClick = onOpenOverlaySettings,
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    supportingContent = {
                        Text(text = stringResource(if (overlayAllowed) R.string.overlay_allowed else R.string.overlay_not_allowed))
                    },
                    leadingContent = {
                        Icon(
                            painter = painterResource(if (overlayAllowed) R.drawable.ic_check_circle else R.drawable.ic_close),
                            contentDescription = null,
                            tint = if (overlayAllowed) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                ) {
                    Text(text = stringResource(R.string.overlay_title))
                }
            }
        }
    }
}
