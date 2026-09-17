package com.example.bilimonitor.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 分框时间输入的状态持有者（用户定稿：不使用整框填时间）。
 * 五个字段：年/月/日/时/分；全部填写且日期合法时 toEpoch() 才返回值。
 */
class DateTimeParts {
    var year by mutableStateOf("")
    var month by mutableStateOf("")
    var day by mutableStateOf("")
    var hour by mutableStateOf("")
    var minute by mutableStateOf("")

    fun isComplete(): Boolean =
        year.length == 4 && month.isNotBlank() && day.isNotBlank() &&
            hour.isNotBlank() && minute.isNotBlank()

    /** 全部填写且日期合法 → epoch 毫秒；否则 null（含 2 月 30 日这类非法日期）。 */
    fun toEpoch(): Long? = try {
        if (!isComplete()) null
        else java.time.LocalDateTime.of(
            year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt()
        ).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    fun setEpoch(millis: Long?) {
        if (millis == null) {
            year = ""; month = ""; day = ""; hour = ""; minute = ""
            return
        }
        val dt = java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        year = dt.year.toString()
        month = "%02d".format(dt.monthValue)
        day = "%02d".format(dt.dayOfMonth)
        hour = "%02d".format(dt.hour)
        minute = "%02d".format(dt.minute)
    }
}

/** 日期-only 版（年/月/日三框），用于筛选的自定义起止日期。 */
class DateParts {
    var year by mutableStateOf("")
    var month by mutableStateOf("")
    var day by mutableStateOf("")

    fun isComplete(): Boolean = year.length == 4 && month.isNotBlank() && day.isNotBlank()

    /** 当天 00:00 的 epoch 毫秒；填写不完整或日期非法时 null。 */
    fun toEpoch(): Long? = try {
        if (!isComplete()) null
        else java.time.LocalDate.of(year.toInt(), month.toInt(), day.toInt())
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    fun setEpochDate(millis: Long?) {
        if (millis == null) {
            year = ""; month = ""; day = ""
            return
        }
        val d = java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        year = d.year.toString()
        month = "%02d".format(d.monthValue)
        day = "%02d".format(d.dayOfMonth)
    }
}

@Composable
fun smallTimeBox(
    label: String,
    value: String,
    maxLength: Int,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { ch -> ch.isDigit() }.take(maxLength)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

/**
 * 五框时间输入：年 / 月 / 日 / 时 / 分。
 * [onChanged] 在任一分框内容变化后触发（用于"填两项自动补全第三项"）。
 */
@Composable
fun DateTimeFields(
    label: String,
    parts: DateTimeParts,
    modifier: Modifier = Modifier,
    onChanged: () -> Unit = {}
) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            smallTimeBox("年", parts.year, 4, { parts.year = it; onChanged() }, Modifier.weight(1.4f))
            smallTimeBox("月", parts.month, 2, { parts.month = it; onChanged() }, Modifier.weight(1f))
            smallTimeBox("日", parts.day, 2, { parts.day = it; onChanged() }, Modifier.weight(1f))
            smallTimeBox("时", parts.hour, 2, { parts.hour = it; onChanged() }, Modifier.weight(1f))
            smallTimeBox("分", parts.minute, 2, { parts.minute = it; onChanged() }, Modifier.weight(1f))
        }
    }
}

/** 三框日期输入：年 / 月 / 日。 */
@Composable
fun DateFields(
    label: String,
    parts: DateParts,
    modifier: Modifier = Modifier,
    onChanged: () -> Unit = {}
) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            smallTimeBox("年", parts.year, 4, { parts.year = it; onChanged() }, Modifier.weight(1.4f))
            smallTimeBox("月", parts.month, 2, { parts.month = it; onChanged() }, Modifier.weight(1f))
            smallTimeBox("日", parts.day, 2, { parts.day = it; onChanged() }, Modifier.weight(1f))
        }
    }
}
