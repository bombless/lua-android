package com.bombless.luaandroid.ipc
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jme.JmePlatform
import org.json.JSONObject
internal data class RunResult(val stdout:String,val result:String?)
internal class LuaEngine {
 fun run(source:String,limit:Int):RunResult {
  val globals=JmePlatform.standardGlobals(); val output=StringBuilder()
  globals.set("print",object:VarArgFunction(){override fun invoke(args:org.luaj.vm2.Varargs):org.luaj.vm2.Varargs {
   val line=(1..args.narg()).joinToString("\t"){args.arg(it).tojstring()}+"\n"
   if(output.length<limit)output.append(line.take(limit-output.length))
   return LuaValue.NONE
  }})
  val value=globals.load(source,"job.lua").call()
  val json=when { value.isnil()->"null";value.isboolean()||value.isnumber()->value.tojstring();value.isstring()->JSONObject.quote(value.tojstring());else->null }
  return RunResult(output.toString(),json)
 }
}
