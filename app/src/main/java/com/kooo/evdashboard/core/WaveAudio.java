package com.kooo.evdashboard.core;

import java.nio.*;
import java.nio.charset.StandardCharsets;

public final class WaveAudio {
    public static byte[] encodeMono(short[] samples,int rate)throws Exception{
        FilesEx.require(rate==48000&&samples.length>0&&samples.length<=rate*30,"音频需为 48 kHz 且不超过 30 秒");ByteBuffer b=ByteBuffer.allocate(44+samples.length*2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36+samples.length*2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16).put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples.length*2);for(short s:samples)b.putShort(s);return b.array();
    }
    public static short[] convert(short[] interleaved,int channels,int rate,int gain)throws Exception{
        FilesEx.require(channels>=1&&channels<=8&&rate>=8000&&rate<=192000&&interleaved.length%channels==0&&gain>=25&&gain<=200,"不支持的 PCM 格式或音量");int frames=interleaved.length/channels;FilesEx.require(frames>0&&frames<=rate*30,"音频需为 0–30 秒");int count=(int)((long)frames*48000/rate);short[] out=new short[count];
        double peak=1;double[] mono=new double[frames];for(int k=0;k<frames;k++){double sum=0;for(int c=0;c<channels;c++)sum+=interleaved[k*channels+c];mono[k]=sum/channels;peak=Math.max(peak,Math.abs(mono[k]));}double scale=Math.min(gain/100.0,30000/peak);
        for(int k=0;k<count;k++){double position=k*(double)rate/48000;int a=(int)position,b=Math.min(frames-1,a+1);double value=mono[a]+(mono[b]-mono[a])*(position-a);out[k]=(short)Math.round(value*scale);}return out;
    }
    public static byte[] silence(byte[] wave)throws Exception{
        FilesEx.require(wave.length>=44&&wave.length<=6*1024*1024,"WAV 长度异常");ByteBuffer b=ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);FilesEx.require(b.getInt(0)==0x46464952&&b.getInt(8)==0x45564157&&Integer.toUnsignedLong(b.getInt(4))==wave.length-8,"RIFF/WAVE 容器长度不匹配");boolean format=false,data=false;int alignment=0,dataSize=0;byte[] result=wave.clone();
        for(int at=12;at<wave.length;){
            FilesEx.require(at+8<=wave.length,"WAV 尾部 chunk 不完整");int type=b.getInt(at),size=b.getInt(at+4);long end=(long)at+8+size+(size&1);FilesEx.require(size>=0&&end<=wave.length,"WAV chunk 或填充越界");
            if(type==0x20746d66){FilesEx.require(!format&&size>=16&&b.getShort(at+8)==1&&b.getShort(at+22)==16,"只支持唯一的 PCM 16-bit 格式块");int channels=Short.toUnsignedInt(b.getShort(at+10)),rate=b.getInt(at+12);alignment=Short.toUnsignedInt(b.getShort(at+20));FilesEx.require(channels>=1&&channels<=8&&rate>=8000&&rate<=192000&&alignment==channels*2&&b.getInt(at+16)==rate*alignment,"WAV 采样格式不一致");format=true;}
            if(type==0x61746164){FilesEx.require(!data&&size>0,"WAV 音频块重复或为空");java.util.Arrays.fill(result,at+8,at+8+size,(byte)0);data=true;dataSize=size;}at=(int)end;
        }
        FilesEx.require(format&&data&&dataSize%alignment==0,"WAV 缺少格式、音频块或采样帧不完整");return result;
    }
    /** Only the canonical 48 kHz / mono file produced by this application. */
    public static short[] normalizedSamples(byte[] wave)throws Exception{
        silence(wave);ByteBuffer b=ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);
        FilesEx.require(b.getInt(12)==0x20746d66&&b.getInt(16)==16&&b.getShort(22)==1&&b.getInt(24)==48000&&b.getInt(36)==0x61746164&&b.getInt(40)==wave.length-44,"不是本应用生成的标准 WAV");
        FilesEx.require(wave.length<=44+48000*30*2,"音频超过 30 秒");short[] samples=new short[(wave.length-44)/2];b.position(44);b.asShortBuffer().get(samples);return samples;
    }
    /** PCM WAV needs no vendor media codec. Strictly validate every chunk before converting. */
    public static byte[] normalizeWave(byte[] wave,int gain)throws Exception{
        silence(wave);ByteBuffer b=ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN);int channels=0,rate=0,dataAt=0,dataBytes=0;
        for(int at=12;at<wave.length;){int type=b.getInt(at),size=b.getInt(at+4);if(type==0x20746d66){channels=Short.toUnsignedInt(b.getShort(at+10));rate=b.getInt(at+12);}if(type==0x61746164){dataAt=at+8;dataBytes=size;}at+=8+size+(size&1);}
        short[] samples=new short[dataBytes/2];b.position(dataAt);b.asShortBuffer().get(samples);return encodeMono(convert(samples,channels,rate,gain),48000);
    }
}
