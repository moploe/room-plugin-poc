package com.poc.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

@Composable
fun Screen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf(listOf<String>()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "OLD build (v1) — person 表只有 id/name，没有 age 列。\n" +
                "插完数据后，去桌面用 adb install -r 装 NEW 包，不要卸载这个 App。",
            style = MaterialTheme.typography.titleMedium,
        )

        Button(onClick = {
            scope.launch {
                val dbPath = context.getDatabasePath("person.db").also { it.parentFile?.mkdirs() }.absolutePath
                val dao = PersonDbV1Builder(dbPath)
                    .setDriver(AndroidSQLiteDriver())
                    .build()
                    .dao()
                val id = dao.insert(PersonV1(name = "Alice"))
                val all = dao.getAll()
                log = listOf("[OLD v1] inserted Alice, id=$id, rows=${all.map { it.name }}") + log
            }
        }) { Text("插入 v1 用户 (Alice)") }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(log) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
