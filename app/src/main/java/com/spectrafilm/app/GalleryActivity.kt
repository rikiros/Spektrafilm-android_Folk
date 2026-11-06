/*
 * Spektrafilm for Android — RAW folder gallery (launcher entry point). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Flow: choose a folder once -> every launch opens on a grid of its RAW/DNG files ->
 * tap a photo -> the existing editor (MainActivity) opens it through its VIEW path.
 * Back from the editor returns here. The editor, recipes and export are unchanged.
 */
package com.spectrafilm.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GalleryActivity : ComponentActivity() {
    /** Bumped on every resume so new shots and fresh "edited" badges appear. */
    private val refreshTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Diagnostics.installCrashHandler(this)
        val settings = AppSettings.from(this)
        setContent {
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SpektraTheme(dark = dark, dynamicColor = settings.dynamicColor) {
                GalleryScreen(refreshTick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue++
    }
}

private fun openEditor(ctx: Context, item: GalleryItem?) {
    val intent = Intent(ctx, MainActivity::class.java)
    if (item != null) {
        intent.action = Intent.ACTION_VIEW
        intent.data = item.uri
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(intent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryScreen(tick: Int) {
    val ctx = LocalContext.current
    var folder by remember { mutableStateOf(GalleryLibrary.loadFolder(ctx)) }
    var items by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching { GalleryLibrary.saveFolder(ctx, uri) }
                .onSuccess {
                    folder = uri
                    items = null
                    error = null
                }
                .onFailure { error = "Couldn't keep access to that folder (${it.message})." }
        }
    }

    LaunchedEffect(folder, tick) {
        val tree = folder ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            runCatching { GalleryLibrary.listRawFiles(ctx, tree) }
        }
        result
            .onSuccess {
                items = it
                error = null
            }
            .onFailure {
                Diag.w("gallery listing failed: ${it.message}")
                error = "Couldn't read this folder. Choose it again."
                items = emptyList()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Gallery")
                        val tree = folder
                        if (tree != null) {
                            val count = items?.size
                            val label = GalleryLibrary.folderLabel(tree)
                            Text(
                                if (count != null) "$label · $count RAW" else label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (folder != null) {
                        TextButton(onClick = { folderPicker.launch(folder) }) { Text("Folder") }
                    }
                    TextButton(onClick = { openEditor(ctx, null) }) { Text("Editor") }
                },
            )
        },
    ) { padding ->
        val list = items
        when {
            folder == null -> CenterMessage(
                padding,
                "Choose the folder where your RAW / DNG photos are saved " +
                    "(for example DCIM/MotionCam). It will open here every time.",
                actionLabel = "Choose RAW folder",
                onAction = { folderPicker.launch(null) },
            )
            list == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> CenterMessage(
                padding,
                error ?: "No RAW / DNG files in this folder yet.",
                actionLabel = "Choose another folder",
                onAction = { folderPicker.launch(folder) },
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 2.dp,
                    end = 2.dp,
                    top = padding.calculateTopPadding() + 2.dp,
                    bottom = padding.calculateBottomPadding() + 2.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(list, key = { it.uri.toString() }) { item ->
                    GalleryTile(item) { openEditor(ctx, item) }
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(
    padding: PaddingValues,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun GalleryTile(item: GalleryItem, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val bitmap by produceState<Bitmap?>(GalleryThumbnails.cached(item), item) {
        if (value == null) value = GalleryThumbnails.load(ctx, item)
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                item.name,
                modifier = Modifier.align(Alignment.Center).padding(6.dp),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.edited) {
            Text(
                "EDITED",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
