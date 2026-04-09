package org.thingai.app.aigateway

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import org.thingai.app.aigateway.auth.LMApiKey
import org.thingai.app.aigateway.callback.RequestCallback
import org.thingai.app.aigateway.engine.LMEngineManager
import org.thingai.base.Service
import org.thingai.base.log.ILog
import org.thingai.platform.dao.DaoSqlite

class LMApplication: Service() {
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

        LMEngineManager.setEngineConfig(EngineConfig(
            modelPath = "/data/model/gemma4-e4b/gemma-4-E4B-it.litertlm",
            backend = Backend.CPU()
        ))

        LMEngineManager.initEngine(object: RequestCallback<Boolean> {
            override fun onSuccess(result: Boolean) {

            }

            override fun onError(error: String) {

            }
        })
    }
}