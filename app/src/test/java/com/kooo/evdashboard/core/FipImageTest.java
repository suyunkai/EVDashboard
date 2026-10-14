package com.kooo.evdashboard.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.*;
import static org.junit.Assert.*;

public class FipImageTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    File fixture()throws Exception{File f=temp.newFile();try(RandomAccessFile out=new RandomAccessFile(f,"rw")){out.setLength(FipImage.PARTITION_BYTES);ByteBuffer h=ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);h.putInt(0,0xaa640001).putInt(4,0x12345678);byte[] id={(byte)0x8a,0x50,0x46,0x7f,(byte)0x89,(byte)0x9d,0x4a,(byte)0xb3,(byte)0x9e,(byte)0x97,(byte)0x8f,(byte)0xe0,(byte)0xed,0x6d,0x1d,(byte)0xcb};h.position(16);h.put(id);h.putLong(32,4096).putLong(40,8192).putLong(72,12288);out.write(h.array());ByteBuffer s=ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);s.putInt(0,0xe0f5e1e2).putInt(8,1).put(12,(byte)12).putLong(24,123456).putInt(36,2);out.seek(5120);out.write(s.array());}return f;}
    void byteAt(File f,long offset,int value)throws Exception{try(RandomAccessFile out=new RandomAccessFile(f,"rw")){out.seek(offset);out.write(value);}}
    @Test public void supportedStructureWithoutModelCheck()throws Exception{assertEquals(8192,FipImage.read(fixture()).payloadBytes);}
    @Test public void truncatedRejected()throws Exception{File f=fixture();try(RandomAccessFile out=new RandomAccessFile(f,"rw")){out.setLength(12300);}assertThrows(Exception.class,()->FipImage.read(f));}
    @Test public void unknownFeaturesAndWrongTypeRejected()throws Exception{File f=fixture();byteAt(f,5120+80,1);assertThrows(Exception.class,()->FipImage.read(f));File g=fixture();byteAt(g,16,0);assertThrows(Exception.class,()->FipImage.read(g));}
    @Test public void nonzeroTailCannotChange()throws Exception{File a=fixture(),b=fixture();byteAt(a,15000,1);assertThrows(Exception.class,()->FipImage.compatibleEnvelope(a,b));}
    @Test public void fileIdentityCannotChange()throws Exception{File a=fixture(),b=fixture();byteAt(b,5120+48,7);assertThrows(Exception.class,()->FipImage.compatibleEnvelope(a,b));}
    @Test public void matchingEnvelopeAccepted()throws Exception{File a=fixture(),b=fixture();FipImage.compatibleEnvelope(a,b);}
}
