package com.kooo.evdashboard.core;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class TuningCurveTest {
    private byte[] sample(){String body="Mode:normal\r\nVolume:0,8,18,20,50,12,0\r\nVolume:0,0,0,0,0,0,0\r\nLower_Volume:1,1,1,1,1,1,1\r\n";return ("Encryption_Code:"+TuningCurve.crc(body.getBytes(StandardCharsets.ISO_8859_1),0)+"\r\n"+body).getBytes(StandardCharsets.ISO_8859_1);}
    private byte[] toggle(byte[] bytes){byte[] out=bytes.clone();for(int i=0;i<out.length;i++){int b=out[i]&255;if(b!=112&&b!=125&&b!=10&&b!=13)out[i]=(byte)(b^112);}return out;}
    @Test public void plainRoundTripPreservesDisabledAndOtherRows()throws Exception{byte[] b=TuningCurve.update(sample(),new int[]{1,2,3,4,5,6,7},150);assertArrayEquals(new int[]{2,3,5,6,8,9,11},TuningCurve.values(b));String s=new String(b,StandardCharsets.ISO_8859_1);assertTrue(s.contains("Volume:0,0,0,0,0,0,0"));assertTrue(s.contains("Lower_Volume:1,1,1,1,1,1,1"));}
    @Test public void encodedRoundTrip()throws Exception{byte[] b=TuningCurve.update(toggle(sample()),new int[]{1,2,3,4,5,6,7},100);assertArrayEquals(new int[]{1,2,3,4,5,6,7},TuningCurve.values(b));assertFalse(new String(b,StandardCharsets.ISO_8859_1).contains("Volume:"));}
    @Test public void badChecksumRejected(){byte[] b=sample();b[b.length-5]='2';assertThrows(Exception.class,()->TuningCurve.values(b));}
    @Test public void silentOrWrongSizedCurvesRejected(){assertThrows(Exception.class,()->TuningCurve.update(sample(),new int[7],100));assertThrows(Exception.class,()->TuningCurve.update(sample(),new int[]{1,2},100));assertThrows(Exception.class,()->TuningCurve.update(sample(),new int[]{1,2,3,4,5,6,101},100));}
    @Test public void unknownFormatRejected(){assertThrows(Exception.class,()->TuningCurve.values("garbage".getBytes(StandardCharsets.US_ASCII)));}
}
