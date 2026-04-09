package org.thingai.app.aigateway

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import org.thingai.app.aigateway.auth.LMApiKey
import org.thingai.app.aigateway.auth.LMApiKeyService
import org.thingai.app.aigateway.callback.RequestCallback
import org.thingai.app.aigateway.engine.LMEngineManager
import org.thingai.base.Service
import org.thingai.base.log.ILog
import org.thingai.platform.dao.DaoSqlite

object LMApplication: Service() {

    lateinit var apiKeyService: LMApiKeyService
        private set

    init {
        name = "LMApplication"
        appDirName = "lm_application"
        version = "0.1"

        ILog.ENABLE_LOGGING = true
        ILog.logLevel = ILog.DEBUG
    }

    override fun onServiceInit() {
        val dao = DaoSqlite("$appDir/lm_application.db")
        dao.initDao(
            arrayOf(
                LMApiKey::class.java
            )
        )

        apiKeyService = LMApiKeyService(dao)

        LMEngineManager.setEngineConfig(EngineConfig(
            modelPath = "./model/gemma4-e4b/gemma-4-E4B-it.litertlm",
            backend = Backend.GPU()
        ))

        LMEngineManager.initEngine(object: RequestCallback<Boolean> {
            override fun onSuccess(result: Boolean) {

            }

            override fun onError(error: String) {

            }
        })
    }
}