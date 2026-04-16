package org.thingai.app.aigateway

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import org.thingai.app.aigateway.auth.LMApiKey
import org.thingai.app.aigateway.auth.LMServiceApiKey
import org.thingai.app.aigateway.auth.LMServiceAuth
import org.thingai.app.aigateway.lm.LMService
import org.thingai.app.aigateway.lm.entity.LMStoredConversation
import org.thingai.app.aigateway.lm.entity.LMStoredMessage
import org.thingai.app.aigateway.utils.EnvConfig
import org.thingai.base.Service
import org.thingai.base.log.ILog
import org.thingai.platform.dao.DaoFile
import org.thingai.platform.dao.DaoSqlite
import java.io.File

object LMApplication : Service() {

    lateinit var apiKeyService: LMServiceApiKey
        private set

    lateinit var authService: LMServiceAuth
        private set

    init {
        name = "LMApplication"
        appDirName = "lm_application"
        version = "0.1"

        ILog.ENABLE_LOGGING = true
        ILog.logLevel = ILog.DEBUG
    }

    override fun onServiceInit() {
        EnvConfig.load()

        val jwtSecret    = EnvConfig["JWT_SECRET"]    ?: "change-me-jwt-secret"
        val authUsername = EnvConfig["AUTH_USERNAME"] ?: "admin"
        val authPassword = EnvConfig["AUTH_PASSWORD"] ?: "change-me-password"

        val dao = DaoSqlite("$appDir/lm_application.db")
        val daoFile = DaoFile("$appDir/files")
        dao.initDao(
            arrayOf(
                LMApiKey::class.java,
                LMStoredConversation::class.java,
                LMStoredMessage::class.java
            )
        )

        apiKeyService = LMServiceApiKey(dao)
        authService   = LMServiceAuth(
            jwtSecret = jwtSecret,
            username  = authUsername,
            password  = authPassword
        )

        LMService.setDao(dao)
        LMService.setAppDir(File(appDir))
        LMService.setEngineConfig(
            EngineConfig(
                modelPath    = "./model/gemma4-e2b/gemma-4-E2B-it.litertlm",
                backend      = Backend.CPU(),
                audioBackend = Backend.CPU(),
                visionBackend = Backend.CPU(),
            )
        )
        LMService.start { success ->
            if (!success) ILog.e("LMApplication", "onServiceInit: engine failed to start")
        }
    }
}
