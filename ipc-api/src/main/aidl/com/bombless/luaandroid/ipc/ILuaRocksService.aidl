// ILuaRocksService.aidl
package com.bombless.luaandroid.ipc;

import com.bombless.luaandroid.ipc.RockInfo;
import com.bombless.luaandroid.ipc.RockOperation;
import com.bombless.luaandroid.ipc.RockOptions;

/**
 * LuaRocks package management (Manual.md section 5.3). Owner-only by default: callers other than
 * the host app itself need the "manage packages" grant in Lua Android -> Access.
 * Operation IDs are unrelated to script job IDs. Operations on the rock tree are serialised.
 */
interface ILuaRocksService {
    int getApiVersion();
    String getLuaRocksVersion();

    String installRock(String name, String version, in RockOptions options);
    String removeRock(String name, String version);
    String searchRocks(String query);
    String showRock(String name, String version);

    /** Text equivalent of `luarocks path` for the private tree (no network). */
    String getRocksPath();
    List<RockInfo> listInstalledRocks();

    RockOperation getRockOperation(String operationId);
    boolean cancelRockOperation(String operationId);
}
