package com.stansful.sshvpnclient.ui.apppicker

import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stansful.sshvpnclient.AppContainer
import com.stansful.sshvpnclient.domain.model.InstalledAppInfo
import com.stansful.sshvpnclient.ui.common.AppScreen
import com.stansful.sshvpnclient.ui.common.AppViewModelFactory
import com.stansful.sshvpnclient.ui.common.InsetGroup
import com.stansful.sshvpnclient.ui.common.InsetRow
import com.stansful.sshvpnclient.ui.common.StatusCapsule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
fun AppPickerRoute(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: AppPickerViewModel = viewModel(factory = AppViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.refreshSelection()
    }
    val saveAndBack = {
        viewModel.saveSelection()
        onBack()
    }

    BackHandler(onBack = saveAndBack)
    AppPickerScreen(
        state = state,
        onQueryChange = viewModel::setQuery,
        onTogglePackage = viewModel::togglePackage,
        onBack = saveAndBack,
    )
}

@Composable
private fun AppPickerScreen(
    state: AppPickerUiState,
    onQueryChange: (String) -> Unit,
    onTogglePackage: (String) -> Unit,
    onBack: () -> Unit,
) {
    AppScreen(
        title = "Select apps",
        onBack = onBack,
        actions = {
            TextButton(onClick = onBack) {
                Text("Done")
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text("Search") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )

            InsetGroup(contentPadding = PaddingValues(0.dp)) {
                InsetRow(
                    title = "Selected applications",
                    subtitle = "Only these apps will use the VPN in selected apps mode.",
                    trailing = {
                        StatusCapsule(
                            text = state.selectedCount.toString(),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }

            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = state.apps,
                        key = { app -> app.packageName },
                    ) { app ->
                        AppRow(
                            app = app,
                            checked = app.packageName in state.selectedPackages,
                            onToggle = { onTogglePackage(app.packageName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledAppInfo,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (checked) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (checked) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = app.packageName)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = app.label,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (app.isSystem) {
                        StatusCapsule(
                            text = "System",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val iconSizePx = with(LocalDensity.current) { APP_ICON_SIZE.roundToPx() }
    val iconBitmap by produceState<ImageBitmap?>(
        initialValue = AppIconMemoryCache.get(packageName, iconSizePx),
        packageName,
        iconSizePx,
    ) {
        if (value == null) {
            value = AppIconMemoryCache.load(packageManager, packageName, iconSizePx)
        }
    }
    val modifier = Modifier
        .size(APP_ICON_SIZE)
        .clip(MaterialTheme.shapes.small)
    val bitmap = iconBitmap

    if (bitmap == null) {
        Icon(
            Icons.Default.Apps,
            contentDescription = null,
            modifier = modifier.padding(8.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier,
        )
    }
}

private val APP_ICON_SIZE = 40.dp

/**
 * PackageManager icon decoding can be surprisingly expensive on vendor launchers. Keep it off the
 * Compose thread and retain a small, size-aware cache so rows do not decode again while scrolling.
 */
private object AppIconMemoryCache {
    private const val MAX_CACHE_BYTES = 4 * 1_024 * 1_024
    private const val CACHE_TTL_MS = 5 * 60 * 1_000L
    private const val MAX_CONCURRENT_DECODES = 2
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_DECODES)
    private val inFlightLock = Any()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ImageBitmap?>>()
    private val cache = object : LruCache<String, CachedAppIcon>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: CachedAppIcon): Int {
            val bitmap = value.bitmap ?: return 1
            return (bitmap.width.toLong() * bitmap.height.toLong() * BYTES_PER_PIXEL)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
    }

    fun get(packageName: String, sizePx: Int): ImageBitmap? =
        getFresh(key(packageName, sizePx))?.bitmap

    suspend fun load(
        packageManager: PackageManager,
        packageName: String,
        sizePx: Int,
    ): ImageBitmap? {
        val cacheKey = key(packageName, sizePx)
        while (true) {
            getFresh(cacheKey)?.let { return it.bitmap }
            val (load, isLoader) = synchronized(inFlightLock) {
                getFresh(cacheKey)?.let { return it.bitmap }
                val existing = inFlight[cacheKey]
                if (existing != null) {
                    existing to false
                } else {
                    CompletableDeferred<ImageBitmap?>().also { created ->
                        inFlight[cacheKey] = created
                    } to true
                }
            }
            if (!isLoader) {
                try {
                    return load.await()
                } catch (_: CancellationException) {
                    // The row that owned the decode may have left composition. Retry only while
                    // this consumer is still visible; its own cancellation must propagate.
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }

            try {
                val bitmap = withContext(decodeDispatcher) {
                    currentCoroutineContext().ensureActive()
                    val decoded = runCatching {
                        packageManager
                            .getApplicationIcon(packageName)
                            .toBitmap(width = sizePx, height = sizePx)
                            .asImageBitmap()
                    }.getOrNull()
                    currentCoroutineContext().ensureActive()
                    cache.put(
                        cacheKey,
                        CachedAppIcon(bitmap = decoded, cachedAtMs = SystemClock.elapsedRealtime()),
                    )
                    decoded
                }
                load.complete(bitmap)
                return bitmap
            } catch (error: CancellationException) {
                load.cancel(error)
                throw error
            } catch (error: Throwable) {
                load.completeExceptionally(error)
                throw error
            } finally {
                synchronized(inFlightLock) {
                    if (inFlight[cacheKey] === load) {
                        inFlight.remove(cacheKey)
                    }
                }
            }
        }
    }

    private fun getFresh(cacheKey: String): CachedAppIcon? {
        val cached = cache.get(cacheKey) ?: return null
        if (SystemClock.elapsedRealtime() - cached.cachedAtMs <= CACHE_TTL_MS) return cached
        cache.remove(cacheKey)
        return null
    }

    private fun key(packageName: String, sizePx: Int): String = "$packageName@$sizePx"

    private const val BYTES_PER_PIXEL = 4L

    private data class CachedAppIcon(
        val bitmap: ImageBitmap?,
        val cachedAtMs: Long,
    )
}
