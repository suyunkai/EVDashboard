package com.kooo.evdashboard.core;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Independent parser for the observed configuration wire format, not copied application code. */
public final class TuningCurve {
    private static final Pattern ROW=Pattern.compile("(?<![A-Za-z0-9_])Volume:([0-9]+(?:,[0-9]+){6})(?![0-9,])");
    private static final Pattern CHECK=Pattern.compile("Encryption_Code:([0-9]+)");
    private static byte[] toggle(byte[] b){byte[] out=b.clone();for(int k=0;k<out.length;k++){int v=out[k]&255;if(v!=0x70&&v!=0x7d&&v!=10&&v!=13)out[k]=(byte)(v^0x70);}return out;}
    public static int crc(byte[] data,int start){int value=0xffff;for(int k=start;k<data.length;k++){if(data[k]==13)continue;value^=(data[k]&255)<<8;for(int bit=0;bit<8;bit++)value=((value<<1)^((value&0x8000)!=0?0x1021:0))&0xffff;}return value;}
    private static String plain(byte[] source)throws Exception{FilesEx.require(source.length>0&&source.length<65536,"曲线文件大小异常");String raw=new String(source,StandardCharsets.ISO_8859_1);String text=ROW.matcher(raw).find()?raw:new String(toggle(source),StandardCharsets.ISO_8859_1);FilesEx.require(ROW.matcher(text).find(),"不支持的曲线格式");Matcher m=CHECK.matcher(text);if(m.find()){int next=text.indexOf('\n',m.end());FilesEx.require(next>=0&&Integer.parseInt(m.group(1))==crc(text.getBytes(StandardCharsets.ISO_8859_1),next+1),"原曲线校验码不正确");}return text;}
    public static int[] values(byte[] source)throws Exception{Matcher m=ROW.matcher(plain(source));while(m.find()){int[] row=Arrays.stream(m.group(1).split(",")).mapToInt(Integer::parseInt).toArray();if(Arrays.stream(row).anyMatch(v->v!=0))return row;}throw new java.io.IOException("未找到有效音量曲线");}
    public static byte[] update(byte[] source,int[] levels,int gainPercent)throws Exception{
        FilesEx.require(levels.length==7&&gainPercent>=50&&gainPercent<=200,"需要 7 个速度点，总音量范围 50–200%");
        for(int v:levels)FilesEx.require(v>=0&&v<=100,"速度点范围 0–100");FilesEx.require(Arrays.stream(levels).anyMatch(v->v>0),"不允许将有效 AVAS 曲线全部静音");
        String text=plain(source);boolean encoded=!ROW.matcher(new String(source,StandardCharsets.ISO_8859_1)).find();Matcher m=ROW.matcher(text);StringBuffer out=new StringBuffer();int changed=0;
        while(m.find()){int[] row=Arrays.stream(m.group(1).split(",")).mapToInt(Integer::parseInt).toArray();if(Arrays.stream(row).allMatch(v->v==0))continue;StringJoiner values=new StringJoiner(",");for(int v:levels)values.add(Integer.toString(Math.min(200,Math.round(v*gainPercent/100f))));m.appendReplacement(out,Matcher.quoteReplacement("Volume:"+values));changed++;}m.appendTail(out);FilesEx.require(changed>0,"没有可修改的有效曲线");text=out.toString();
        Matcher c=CHECK.matcher(text);if(c.find()){int next=text.indexOf('\n',c.end());FilesEx.require(next>=0,"校验字段没有下一行");int crc=crc(text.getBytes(StandardCharsets.ISO_8859_1),next+1);text=text.substring(0,c.start(1))+crc+text.substring(c.end(1));}
        byte[] result=text.getBytes(StandardCharsets.ISO_8859_1);if(encoded)result=toggle(result);plain(result);return result;
    }
}
