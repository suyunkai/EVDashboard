package com.kooo.evdashboard.core;

import org.json.JSONObject;

public final class ImageRecord {
    public String id,name,category="UNCLASSIFIED",origin="DEVICE",sha="",device="",slot="",build="",sourceId="",sourceSha="";
    public String socket="UNKNOWN",password="UNKNOWN",message="尚未校验";
    public long created=System.currentTimeMillis(),bytes;
    public boolean validated,enableSsh,clearPassword,blankAllowed;
    public String group(){return device+"|"+slot+"|"+build;}
    public boolean original(){return category.equals("ORIGINAL");}
    public String categoryLabel(){return switch(category){case "ORIGINAL"->"原始镜像 · 用户确认";case "MODIFIED"->"已修改镜像";case "IMPORTED"->"外部导入";default->"待分类";};}
    public JSONObject json() throws Exception {
        JSONObject j=new JSONObject();
        j.put("id",id).put("name",name).put("category",category).put("origin",origin).put("sha",sha).put("device",device).put("slot",slot).put("build",build).put("sourceId",sourceId).put("sourceSha",sourceSha).put("socket",socket).put("password",password).put("message",message).put("created",created).put("bytes",bytes).put("validated",validated).put("enableSsh",enableSsh).put("clearPassword",clearPassword).put("blankAllowed",blankAllowed);return j;
    }
    public static ImageRecord parse(JSONObject j) throws Exception {
        ImageRecord r=new ImageRecord();r.id=j.getString("id");FilesEx.require(r.id.matches("[a-f0-9-]{36}"),"非法镜像记录 ID");r.name=j.optString("name",r.id);r.category=j.optString("category","UNCLASSIFIED");r.origin=j.optString("origin","IMPORT");r.sha=j.optString("sha");r.device=j.optString("device");r.slot=j.optString("slot");r.build=j.optString("build");r.sourceId=j.optString("sourceId");r.sourceSha=j.optString("sourceSha");r.socket=j.optString("socket","UNKNOWN");r.password=j.optString("password","UNKNOWN");r.message=j.optString("message");r.created=j.optLong("created");r.bytes=j.optLong("bytes");r.validated=j.optBoolean("validated");r.enableSsh=j.optBoolean("enableSsh");r.clearPassword=j.optBoolean("clearPassword");r.blankAllowed=j.optBoolean("blankAllowed");return r;
    }
}
