package com.vuhpquang.zentask.android.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vuhpquang.zentask.android.ZenApp
import com.vuhpquang.zentask.model.Attachment
import kotlinx.coroutines.launch

/** Task detail → Attachments: thumbnails / file tiles, Add (any file), tap opens, long-press removes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AttachmentsSection(app: ZenApp, taskId: String, revision: Int) {
    val scope = rememberCoroutineScope()
    val cached by app.files.cached.collectAsState()
    val items = remember(taskId, revision) { app.core.attachmentActions.of(taskId) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Attachment?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        error = null
        scope.launch { for (u in uris) runCatching { app.files.add(u, taskId) }.onFailure { error = it.message } }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Attachments", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.AttachFile, null); Text(" Add") }
        }
        if (items.isEmpty()) {
            Text("Photos and files up to 10 MB (bigger photos are shrunk to fit).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { a ->
                    val file = if (a.fileId in cached) app.files.file(a.fileId) else null
                    Column(Modifier.width(88.dp).combinedClickable(onClick = { app.files.open(a) }, onLongClick = { removing = a })) {
                        Box(Modifier.size(88.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            val thumb = remember(file) {
                                if (file != null && a.isImage) BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() else null
                            }
                            when {
                                thumb != null -> Image(thumb, a.name, Modifier.size(88.dp), contentScale = ContentScale.Crop)
                                file == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else -> Icon(Icons.Filled.InsertDriveFile, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(a.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    removing?.let { a ->
        Confirm("Remove \"${a.name}\"?", "It's removed from this task on every device.", "Remove",
            onConfirm = { app.core.attachmentActions.remove(a.id); removing = null }, onDismiss = { removing = null })
    }
}
