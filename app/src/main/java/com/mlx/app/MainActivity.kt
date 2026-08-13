package com.mlx.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mlx.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // edge-to-edge：insets 全部交由 Compose 层（含挖孔安全区）处理
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统"所有文件访问"授权页返回时刷新权限状态（AppViewModel.onAppResume）
        (application as MlxApp).container.refreshAllFilesAccess()
    }
}
