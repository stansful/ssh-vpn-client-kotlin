package com.stansful.sshvpnclient.ui.settings

import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.stansful.sshvpnclient.ui.theme.Shadow
import com.stansful.sshvpnclient.ui.theme.ShadowColors
import com.stansful.sshvpnclient.ui.theme.ShadowShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The app's launcher icon, or a tinted initial while it loads or when Android has none for it. Icons
 * decode off the main thread through a small shared cache.
 */
@Composable
internal fun AppAvatar(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    cornerRadius: Dp = 12.dp,
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState(AppIconMemoryCache.get(packageName, sizePx), packageName, sizePx) {
        if (value == null) value = AppIconMemoryCache.load(packageManager, packageName, sizePx)
    }
    val shape = RoundedCornerShape(cornerRadius)
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size).clip(shape))
    } else {
        InitialAvatar(packageName, label, modifier, size, shape)
    }
}

/** A tinted tile with the app's first letter; the tint is stable per package. */
@Composable
internal fun InitialAvatar(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    shape: RoundedCornerShape = ShadowShapes.Tile,
) {
    val (fill, ink) = Shadow.colors.avatarTone(packageName)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.firstOrNull()?.uppercase().orEmpty(),
            style = Shadow.type.rowTitle.copy(fontWeight = FontWeight.SemiBold),
            color = ink,
        )
    }
}

/** A stable tone pair (tint, ink) for a package, from the state tones of the palette. */
internal fun ShadowColors.avatarTone(packageName: String): Pair<Color, Color> {
    val tones = listOf(
        skyTint to skyText,
        amberTint to amberText,
        mintTint to mintText,
        coralTint to coralText,
        surface3 to ink2,
    )
    return tones[Math.floorMod(packageName.hashCode(), tones.size)]
}

/**
 * PackageManager icon decoding can be surprisingly expensive on vendor launchers. Keep it off the
 * Compose thread and retain a small, size-aware cache so rows do not decode again while scrolling.
 */
private object AppIconMemoryCache {
    private const val MAX_CACHE_BYTES = 4 * 1_024 * 1_024
    private const val CACHE_TTL_MS = 5 * 60 * 1_000L
    private const val MAX_CONCURRENT_DECODES = 2
    private const val BYTES_PER_PIXEL = 4L
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

    fun get(packageName: String, sizePx: Int): ImageBitmap? = getFresh(key(packageName, sizePx))?.bitmap

    suspend fun load(packageManager: PackageManager, packageName: String, sizePx: Int): ImageBitmap? {
        val cacheKey = key(packageName, sizePx)
        while (true) {
            getFresh(cacheKey)?.let { return it.bitmap }
            val (load, isLoader) = synchronized(inFlightLock) {
                getFresh(cacheKey)?.let { return it.bitmap }
                val existing = inFlight[cacheKey]
                if (existing != null) {
                    existing to false
                } else {
                    CompletableDeferred<ImageBitmap?>().also { inFlight[cacheKey] = it } to true
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
                        packageManager.getApplicationIcon(packageName)
                            .toBitmap(width = sizePx, height = sizePx)
                            .asImageBitmap()
                    }.getOrNull()
                    currentCoroutineContext().ensureActive()
                    cache.put(cacheKey, CachedAppIcon(decoded, SystemClock.elapsedRealtime()))
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
                    if (inFlight[cacheKey] === load) inFlight.remove(cacheKey)
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

    private data class CachedAppIcon(val bitmap: ImageBitmap?, val cachedAtMs: Long)
}
