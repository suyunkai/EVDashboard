package com.kooo.evdashboard;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import com.kooo.evdashboard.core.*;
import java.io.*;
import java.nio.*;

final class AudioImport {
    static byte[] decode(Context context,Uri uri,int gain)throws Exception{return decode(context,uri,gain,1);}
    static byte[] decode(Context context,Uri uri,int gain,int targetChannels)throws Exception{
        // A vendor raw-audio codec can stall despite valid PCM input. WAV is parsed locally.
        try(InputStream opened=context.getContentResolver().openInputStream(uri);BufferedInputStream in=new BufferedInputStream(opened)){
            in.mark(12);byte[] header=new byte[12];int count=0,n;while(count<header.length&&(n=in.read(header,count,header.length-count))>0)count+=n;in.reset();
            if(count==12&&ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(0)==0x46464952&&ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(8)==0x45564157){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];while((n=in.read(buffer))!=-1){FilesEx.require(bytes.size()+n<=6*1024*1024,"WAV 超过 6 MiB 输入限制");bytes.write(buffer,0,n);}return SoundWave.convert(bytes.toByteArray(),targetChannels,48000,gain);}
        }
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
        try{
            extractor.setDataSource(context,uri,null);MediaFormat input=null;
            for(int t=0;t<extractor.getTrackCount();t++){MediaFormat f=extractor.getTrackFormat(t);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){input=f;extractor.selectTrack(t);break;}}
            FilesEx.require(input!=null,"没有可解码的音轨");if(input.containsKey(MediaFormat.KEY_DURATION))FilesEx.require(input.getLong(MediaFormat.KEY_DURATION)<=30_000_000,"音频不能超过 30 秒");
            codec=MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));codec.configure(input,null,null,0);codec.start();
            boolean sentEnd=false,done=false;int rate=input.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=input.getInteger(MediaFormat.KEY_CHANNEL_COUNT),encoding=AudioFormat.ENCODING_PCM_16BIT;ByteArrayOutputStream pcm=new ByteArrayOutputStream();MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long deadline=System.nanoTime()+60_000_000_000L;
            FilesEx.require(rate>=8000&&rate<=192000&&channels>=1&&channels<=8,"不支持的音频采样率或声道数");
            while(!done){FilesEx.require(System.nanoTime()<deadline,"音频解码超时");if(!sentEnd){int slot=codec.dequeueInputBuffer(10000);if(slot>=0){ByteBuffer buffer=codec.getInputBuffer(slot);int n=extractor.readSampleData(buffer,0);if(n<0){codec.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);sentEnd=true;}else{codec.queueInputBuffer(slot,0,n,extractor.getSampleTime(),0);extractor.advance();}}}
                int slot=codec.dequeueOutputBuffer(info,10000);if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat format=codec.getOutputFormat();int nextRate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),nextChannels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),nextEncoding=format.containsKey(MediaFormat.KEY_PCM_ENCODING)?format.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;FilesEx.require(pcm.size()==0||(rate==nextRate&&channels==nextChannels&&encoding==nextEncoding),"不支持音频中途改变采样格式");rate=nextRate;channels=nextChannels;encoding=nextEncoding;FilesEx.require(rate>=8000&&rate<=192000&&channels>=1&&channels<=8&&(encoding==AudioFormat.ENCODING_PCM_16BIT||encoding==AudioFormat.ENCODING_PCM_FLOAT),"解码器输出不是支持的 PCM");}
                else if(slot>=0){ByteBuffer buffer=codec.getOutputBuffer(slot).order(ByteOrder.LITTLE_ENDIAN);FilesEx.require(info.offset>=0&&info.size>=0&&(long)info.offset+info.size<=buffer.capacity()&&info.size%(channels*(encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2))==0,"解码后的采样帧不完整");long added=encoding==AudioFormat.ENCODING_PCM_FLOAT?info.size/2:info.size;FilesEx.require(pcm.size()+added<=Math.min(16L*1024*1024,30L*rate*channels*2),"解码后音频超过 30 秒或 16 MiB 内存限制");buffer.position(info.offset);buffer.limit(info.offset+info.size);if(encoding==AudioFormat.ENCODING_PCM_FLOAT){while(buffer.remaining()>=4){float f=buffer.getFloat();FilesEx.require(Float.isFinite(f),"音频含非法采样");short v=(short)Math.round(Math.max(-1,Math.min(1,f))*32767);pcm.write(v&255);pcm.write((v>>8)&255);}}else{byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);pcm.write(bytes);}done=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(slot,false);}}
            byte[] raw=pcm.toByteArray();FilesEx.require(raw.length%2==0,"PCM 长度异常");short[] samples=new short[raw.length/2];ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);return SoundWave.convert(SoundWave.encode(samples,rate,channels),targetChannels,48000,gain);
        }finally{if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}extractor.release();}
    }
}
