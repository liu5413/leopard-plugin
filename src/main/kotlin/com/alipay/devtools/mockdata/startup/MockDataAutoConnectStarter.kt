package com.alipay.devtools.mockdata.startup

import com.alipay.devtools.mockdata.MockDataService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目打开后启动 MockData 自动连接心跳：
 * - 立即触发一次包名自动检测的连接尝试
 * - 之后每 2 秒检查一次是否已连接，未连接则自动重连
 *
 * 心跳生命周期由 [MockDataService] 拥有，service.dispose 时随项目关闭一起停止。
 */
class MockDataAutoConnectStarter : ProjectActivity {
    override suspend fun execute(project: Project) {
        MockDataService.getInstance(project).startAutoConnectMonitor(intervalSeconds = 2)
    }
}
