package com.bombless.luaandroid.ipc
import android.content.Context
import org.json.JSONObject
import java.io.File
internal class JobStore(context: Context) {
 val root=File(context.filesDir,"lua/jobs").apply{mkdirs()}
 fun dir(id:String):File { require(id.matches(Regex("[a-f0-9-]{36}"))); return File(root,id) }
 fun save(info:JobInfo,uid:Int) {
  val artifacts=org.json.JSONArray().apply{info.artifacts.forEach{put(JSONObject().put("id",it.artifactId).put("name",it.displayName).put("mime",it.mimeType).put("size",it.sizeBytes))}}
  val j=JSONObject().put("jobId",info.jobId).put("state",info.state).put("created",info.createdAtMillis)
   .put("started",info.startedAtMillis).put("finished",info.finishedAtMillis).put("exit",info.exitCode)
   .put("result",info.returnValueJson).put("stdout",info.stdoutBytes).put("errorCode",info.errorCode)
   .put("errorMessage",info.errorMessage).put("uid",uid).put("artifacts",artifacts)
  val d=dir(info.jobId).apply{mkdirs()}; val tmp=File(d,"job.tmp"); tmp.writeText(j.toString())
  val target=File(d,"job.json"); if(!tmp.renameTo(target)) target.writeText(j.toString())
 }
 fun read(id:String):Pair<JobInfo,Int>?=runCatching {
  val j=JSONObject(File(dir(id),"job.json").readText()); val a=j.optJSONArray("artifacts")?:org.json.JSONArray()
  val arts=Array(a.length()){i->val x=a.getJSONObject(i);ArtifactInfo(x.getString("id"),x.getString("name"),x.getString("mime"),x.getLong("size"))}
  JobInfo(id,j.getString("state"),j.getLong("created"),j.optLong("started"),j.optLong("finished"),
   if(j.isNull("exit")) null else j.getInt("exit"),if(j.isNull("result")) null else j.getString("result"),
   j.optLong("stdout"),0,false,j.optString("errorCode").takeIf{it!="null"&&it.isNotEmpty()},
   j.optString("errorMessage").takeIf{it!="null"&&it.isNotEmpty()},arts) to j.getInt("uid")
 }.getOrNull()
 fun list(limit:Int,before:Long)=root.listFiles().orEmpty().mapNotNull{read(it.name)?.first}
  .filter{before<=0||it.createdAtMillis<before}.sortedByDescending{it.createdAtMillis}.take(limit)
}
