package com.bombless.luaandroid.ipc

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.bombless.luaandroid.AppGraph
import com.bombless.luaandroid.LuaApp
import com.bombless.luaandroid.core.Caller
import com.bombless.luaandroid.core.ServiceError
import com.bombless.luaandroid.security.Capability

/** LuaRocks package management (Manual.md section 5.3). Owner-only unless the owner grants "manage packages". */
class LuaRocksService : Service() {
    private val graph: AppGraph get() = (application as LuaApp).graph

    override fun onBind(intent: Intent?): IBinder = binder

    private fun <T> guard(block: (Caller) -> T): T {
        try {
            val caller = graph.policy.authorize(Binder.getCallingUid(), Capability.MANAGE_ROCKS)
            graph.ensureReady()
            return block(caller)
        } catch (e: ServiceError) {
            throw e.toBinderException()
        } catch (e: SecurityException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "unexpected failure", e)
            throw IllegalStateException("${ErrorCode.INTERNAL_ERROR}: unexpected failure (${e.javaClass.simpleName})")
        }
    }

    private val binder = object : ILuaRocksService.Stub() {
        override fun getApiVersion(): Int = LuaRuntimeContract.API_VERSION

        override fun getLuaRocksVersion(): String = guard { graph.rocks.luarocksVersion }

        override fun installRock(name: String?, version: String?, options: RockOptions?): String =
            guard { graph.rocks.install(name, version, options?.server) }

        override fun removeRock(name: String?, version: String?): String = guard { graph.rocks.remove(name, version) }

        override fun searchRocks(query: String?): String = guard { graph.rocks.search(query) }

        override fun showRock(name: String?, version: String?): String = guard { graph.rocks.show(name, version) }

        override fun getRocksPath(): String = guard { graph.rocks.rocksPath() }

        override fun listInstalledRocks(): MutableList<RockInfo> = guard { graph.rocks.listInstalled().toMutableList() }

        override fun getRockOperation(operationId: String?): RockOperation = guard { graph.rocks.operation(operationId) }

        override fun cancelRockOperation(operationId: String?): Boolean = guard { graph.rocks.cancel(operationId) }
    }

    private companion object {
        const val TAG = "LuaRocksService"
    }
}
