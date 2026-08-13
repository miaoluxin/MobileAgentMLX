package com.mlx.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UiFormats {
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun time(ms: Long): String = timeFmt.format(Date(ms))

    /** 成本展示（输入为人民币元 —— 官网 CNY 计价，直接显示，无汇率转换） */
    fun usd(cny: Double): String = if (cny >= 0.01) {
        "¥%.2f".format(cny)
    } else {
        "¥%.3f".format(cny)
    }

    fun percent(ratio: Double?): String = ratio?.let { "%.1f%%".format(it * 100) } ?: "--"

    fun tokens(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 1000 -> "%.1fk".format(n / 1e3)
        else -> "$n"
    }
}
