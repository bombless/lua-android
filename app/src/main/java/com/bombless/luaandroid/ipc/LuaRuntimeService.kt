package com.bombless.luaandroid.ipc
import android.app.Service
import android.content.Intent
import android.os.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
class LuaRuntimeService:Service() {
 private lateinit var store:JobStore
 private val pool=Executors.newFixedThreadPool(2)
 private val callbacks=ConcurrentHashMap<String,RemoteCallbackList<IJobCallback>>()
 private val futures=ConcurrentHashMap<String,java.util.concurrent.Future<*>>()
 private val engine=LuaEngine()
 override fun onCreate(){super.onCreate();store=JobStore(this)}
 override fun onBind(intent:Intent?):IBinder=binder
 private val binder=object:ILuaRuntimeService.Stub(){
  override fun getApiVersion()=1
  override fun getRuntimeInfo()="{\"engine\":\"LuaJ 3.0.1\",\"luaCompatibility\":\"5.2\",\"nativeModules\":false,\"rockTree\":\"files/lua/rocks\"}"
  override fun submit(request:JobRequest):String {
   authorize();require(request.apiVersion==1){"API_VERSION_MISMATCH"}
   require((request.source!=null) xor (request.scriptFd!=null)){"INVALID_REQUEST"}
   require(request.arguments.size<=128 && request.arguments.all{it.length<=4096}){"INVALID_REQUEST"}
   require(request.timeoutMillis in 100..300000){"INVALID_REQUEST"}
   val source=request.source ?: readScript(request.scriptFd!!)
   require(source.toByteArray().size<=262144){"INPUT_TOO_LARGE"}
   val id=UUID.randomUUID().toString();store.dir(id).mkdirs();File(store.dir(id),"script.lua").writeText(source)
   val uid=Binder.getCallingUid();val info=JobInfo(id,"QUEUED",System.currentTimeMillis())
   store.save(info,uid);notify(info);futures[id]=pool.submit{execute(id,source,request.timeoutMillis)}
   return id
  }
  override fun getJob(jobId:String):JobInfo? {authorize();return owned(jobId)?.first}
  override fun listJobs(limit:Int,beforeCreatedAtMillis:Long):MutableList<JobInfo>{
   authorize();require(limit in 1..100);return store.list(limit,beforeCreatedAtMillis).filter{owned(it.jobId)!=null}.toMutableList()
  }
  override fun cancel(jobId:String):Boolean {
   authorize();val pair=owned(jobId)?:return false;val old=pair.first
   if(old.state in terminal)return false
   futures[jobId]?.cancel(true)
   val next=old.copy(state="CANCELLED",finishedAtMillis=System.currentTimeMillis(),errorCode="EXECUTION_CANCELLED")
   store.save(next,pair.second);notify(next);return true
  }
  override fun registerCallback(jobId:String,callback:IJobCallback){authorize();owned(jobId)?:throw IllegalArgumentException("JOB_NOT_FOUND");callbacks.getOrPut(jobId){RemoteCallbackList()}.register(callback)}
  override fun unregisterCallback(jobId:String,callback:IJobCallback){authorize();callbacks[jobId]?.unregister(callback)}
  override fun openResult(jobId:String,artifactId:String):ParcelFileDescriptor {
   authorize();val info=owned(jobId)?.first?:throw IllegalArgumentException("JOB_NOT_FOUND")
   val artifact=info.artifacts.firstOrNull{it.artifactId==artifactId}?:throw IllegalArgumentException("JOB_NOT_FOUND")
   val file=File(store.dir(jobId),artifact.displayName)
   require(file.isFile && file.canonicalPath.startsWith(store.dir(jobId).canonicalPath+File.separator))
   return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
  }
 }
 private fun authorize(){if(Binder.getCallingUid()==Process.myUid())return}
 private fun owned(id:String):Pair<JobInfo,Int>? {val pair=store.read(id)?:return null;if(pair.second!=Binder.getCallingUid())throw SecurityException("UNAUTHORIZED_CALLER");return pair}
 private fun readScript(fd:ParcelFileDescriptor):String {
  val output=java.io.ByteArrayOutputStream()
  ParcelFileDescriptor.AutoCloseInputStream(fd.dup()).use{input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=262144){"INPUT_TOO_LARGE"};output.write(buffer,0,n)}}
  return output.toString("UTF-8")
 }
 private fun execute(id:String,source:String,timeout:Long){
  val pair=store.read(id)?:return;var info=pair.first;val uid=pair.second
  if(info.state=="CANCELLED")return
  info=info.copy(state="RUNNING",startedAtMillis=System.currentTimeMillis());store.save(info,uid);notify(info)
  try {
   val executor=Executors.newSingleThreadExecutor()
   val running=executor.submit<RunResult>{engine.run(source,1024*1024)}
   val result=try{running.get(timeout,TimeUnit.MILLISECONDS)}finally{executor.shutdownNow()}
   val stdout=File(store.dir(id),"stdout.txt");stdout.writeText(result.stdout)
   info=info.copy(state="SUCCEEDED",finishedAtMillis=System.currentTimeMillis(),exitCode=0,returnValueJson=result.result,
    stdoutBytes=stdout.length(),artifacts=arrayOf(ArtifactInfo("stdout","stdout.txt","text/plain; charset=utf-8",stdout.length())))
  } catch(e:java.util.concurrent.TimeoutException) {
   info=info.copy(state="TIMED_OUT",finishedAtMillis=System.currentTimeMillis(),errorCode="EXECUTION_TIMEOUT")
  } catch(e:InterruptedException) {
   Thread.currentThread().interrupt();info=info.copy(state="CANCELLED",finishedAtMillis=System.currentTimeMillis(),errorCode="EXECUTION_CANCELLED")
  } catch(e:Throwable) {
   info=info.copy(state="FAILED",finishedAtMillis=System.currentTimeMillis(),errorCode="SCRIPT_RUNTIME_ERROR",errorMessage=(e.message?:e.javaClass.simpleName).take(1024))
  }
  store.save(info,uid);notify(info);futures.remove(id)
 }
 private fun notify(info:JobInfo){val list=callbacks[info.jobId]?:return;val count=list.beginBroadcast();try{for(i in 0 until count)runCatching{list.getBroadcastItem(i).onJobChanged(info)}}finally{list.finishBroadcast()}}
 override fun onDestroy(){pool.shutdownNow();super.onDestroy()}
 companion object{private val terminal=setOf("SUCCEEDED","FAILED","CANCELLED","TIMED_OUT")}
}
