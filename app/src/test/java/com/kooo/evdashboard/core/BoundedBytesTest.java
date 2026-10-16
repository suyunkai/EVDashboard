package com.kooo.evdashboard.core;

import org.junit.Test;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class BoundedBytesTest {
    @Test public void acceptsLargeBinaryBurstsWithoutPipeOrDataLoss() throws Exception {
        byte[] source=new byte[988520];new Random(73).nextBytes(source);BoundedBytes sink=new BoundedBytes(source.length);
        ExecutorService writer=Executors.newSingleThreadExecutor();
        try {writer.submit(()->{try {for(int at=0;at<source.length;at+=32768)sink.write(source,at,Math.min(32768,source.length-at));}catch(IOException e){throw new RuntimeException(e);}}).get(2,TimeUnit.SECONDS);}
        finally {writer.shutdownNow();}
        assertArrayEquals(source,sink.bytes());byte[] copy=sink.bytes();copy[0]^=1;assertArrayEquals(source,sink.bytes());
    }
    @Test public void overflowCannotBeMistakenForSuccessfulTruncatedOutput() throws Exception {
        BoundedBytes sink=new BoundedBytes(4);sink.write(new byte[]{1,2,3,4});assertEquals(4,sink.bytes().length);
        assertThrows(IOException.class,()->sink.write(5));assertThrows(IOException.class,sink::check);assertThrows(IOException.class,sink::bytes);
        assertThrows(IOException.class,()->sink.write(new byte[1]));
    }
    @Test public void stderrLimitAndPacketBoundariesAreEnforced() throws Exception {
        BoundedBytes errors=new BoundedBytes(65535);errors.write(new byte[32768]);
        assertThrows(IOException.class,()->errors.write(new byte[32768]));assertThrows(IOException.class,errors::bytes);
        BoundedBytes empty=new BoundedBytes(0);empty.write(new byte[0]);assertThrows(IOException.class,()->empty.write(1));
    }
}
