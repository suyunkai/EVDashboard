package com.kooo.evdashboard.core;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class CpDiscoveryTest {
    @Test public void candidatesUseAssignedVehicleAddressesAndExcludeLocalHosts() throws Exception {
        List<String> local = List.of("198.18.48.15", "198.19.8.15", "192.168.34.132", "198.18.37.2");
        List<String> hosts = CpDiscovery.candidates("198.18.44.2", local);
        assertEquals(List.of("198.18.44.2", "198.18.48.2", "198.19.8.2"), hosts.subList(0,3));
        assertEquals(new HashSet<>(hosts).size(), hosts.size());
        assertTrue(hosts.contains("198.18.0.2")); assertTrue(hosts.contains("198.18.255.2"));
        for (String self : local) assertFalse(hosts.contains(self));
        assertFalse(hosts.contains("192.168.34.2"));
    }
    @Test public void fallbackOnlyExpandsConfiguredPrivateSubnetAndSkipsScannedAndSelf() {
        List<String> hosts = CpDiscovery.fallback("198.18.44.7", List.of("198.18.44.15"), List.of("198.18.44.2", "198.18.44.7"));
        assertEquals(251, hosts.size()); assertTrue(hosts.contains("198.18.44.254"));
        assertFalse(hosts.contains("198.18.44.0")); assertFalse(hosts.contains("198.18.44.255"));
        assertFalse(hosts.contains("198.18.44.15")); assertFalse(hosts.contains("198.18.44.7"));
        assertTrue(CpDiscovery.fallback("8.8.8.8", List.of(), List.of()).isEmpty());
        assertEquals(254, CpDiscovery.fallback("172.16.4.2", List.of(), List.of()).size());
        assertTrue(CpDiscovery.fallback("172.32.4.2", List.of(), List.of()).isEmpty());
    }
    private String banner(String value) throws Exception {
        return CpDiscovery.banner(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)), System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
    }
    @Test public void acceptsSsh2AndCompatibilityIdentificationAfterPreamble() throws Exception {
        assertEquals("SSH-2.0-dropbear_2022.83", banner("SSH-2.0-dropbear_2022.83\r\n"));
        assertEquals("SSH-1.99-example comment", banner("Notice\r\nSSH-1.99-example comment\n"));
    }
    @Test public void rejectsOpenNonSshPortsMalformedLegacyAndOversizedReplies() throws Exception {
        for (String value : List.of("HTTP/1.1 200 OK\r\n\r\n", "SSH-1.5-old\n", "SSH-2.0-\n", "SSH-2.0-bad\0name\n", "SSH-2.0-bad\tname\n", "SSH-2.0-"+"a".repeat(250)+"\n", "SSH-2.0-truncated")) assertNull(value, banner(value));
        assertNull(banner("notice\n".repeat(17) + "SSH-2.0-hidden\n"));
        assertNull(CpDiscovery.banner(new ByteArrayInputStream("SSH-2.0-test\n".getBytes(StandardCharsets.US_ASCII)), System.nanoTime()-1));
    }
    @Test public void socketProbeChecksProtocolAndClosesConnection() throws Exception {
        for (String reply : List.of("SSH-2.0-dropbear\r\n", "HTTP/1.1 200 OK\r\n")) {
            try (ServerSocket server = new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
                ExecutorService worker = Executors.newSingleThreadExecutor();
                try {
                    Future<Integer> closed = worker.submit(() -> {try(Socket client=server.accept()) {client.setSoTimeout(2000); client.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII)); return client.getInputStream().read();}});
                    CpDiscovery.Endpoint result = new CpDiscovery().probe("127.0.0.1",server.getLocalPort(),300,100);
                    if (reply.startsWith("SSH")) {assertNotNull(result); assertEquals("SSH-2.0-dropbear",result.banner());} else assertNull(result);
                    assertEquals(Integer.valueOf(-1),closed.get(3,TimeUnit.SECONDS));
                } finally {worker.shutdownNow();}
            }
        }
    }
    @Test public void cancellationClosesAnInFlightSilentSocket() throws Exception {
        CpDiscovery scan = new CpDiscovery();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(2000);
            Future<CpDiscovery.Endpoint> result = worker.submit(() -> scan.probe("127.0.0.1",server.getLocalPort(),300,10000));
            try (Socket connected = server.accept()) {
                scan.cancel(); assertNull(result.get(1,TimeUnit.SECONDS));
                connected.setSoTimeout(1000); assertEquals(-1, connected.getInputStream().read());
            }
            assertThrows(InterruptedIOException.class, () -> scan.scanHosts(List.of("198.18.44.2"),false,System.nanoTime()+1000000,(f,n,t,c)->{}));
        } finally {worker.shutdownNow();}
    }
    @Test public void expiredScanReportsPartialCompletionWithoutClaimingAllHostsChecked() throws Exception {
        CpDiscovery.Result result = new CpDiscovery().scanHosts(List.of("198.18.44.2"),false,System.nanoTime()-1,(f,n,t,c)->{});
        assertTrue(result.timedOut()); assertEquals(0,result.checked()); assertTrue(result.endpoints().isEmpty());
    }
}
