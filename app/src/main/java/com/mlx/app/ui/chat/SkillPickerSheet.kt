package com.mlx.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mlx.app.core.skills.Skill

/**
 * 技能选择器（优化 4a）：长按输入框 + 按钮唤出 ——
 * 搜索 + 技能列表（名称/描述 + 内置|用户、inline|subagent 标签），
 * 选中回填 "@skill:名称" 到输入框（引擎解析后注入剧本，确定性执行）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillPickerSheet(
    skills: List<Skill>,
    onDismiss: () -> Unit,
    onPick: (Skill) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(query, skills) {
        if (query.isBlank()) skills
        else skills.filter { s ->
            s.name.contains(query, ignoreCase = true) ||
                s.description.contains(query, ignoreCase = true) ||
                s.triggers.any { it.contains(query, ignoreCase = true) }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.7f)) {
            Text(
                "选择技能",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索技能名称 / 描述 / 触发词…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.name }) { skill ->
                    SkillRow(
                        skill = skill,
                        onClick = { onPick(skill) },
                    )
                }
                if (filtered.isEmpty()) {
                    item { Text("无匹配技能", modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SkillRow(skill: Skill, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        skill.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (skill.scope == "builtin") "[内置]" else "[用户]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (skill.runAs == "subagent") "[subagent]" else "[inline]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp))
    }
}
