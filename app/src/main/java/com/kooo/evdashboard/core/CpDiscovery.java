package com.kooo.evdashboard.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Read-only SSH endpoint discovery. A banner is never proof of CP identity or host trust. */
public final class CpDiscovery {
    private static final int WORKERS = 24, CONNECT_MS = 300, BANNER_MS = 700;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean cancelled;

    public record Endpoint(String host, String banner) {}
    public record Result(List<Endpoint> endpoints, int checked, boolean timedOut) {}
    public interface Progress {void update(boolean fallback, int checked, int total, int found);}

    public void cancel() {
        cancelled = true;
        for (Socket socket : sockets) try {socket.close();} catch (IOException ignored) {}
    }

    public static List<String> localAddresses() throws SocketException {
        List<String> addresses = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces != null) for (NetworkInterface nic : Collections.list(interfaces)) {
            if (!nic.isUp() || nic.isLoopback()) continue;
            for (InetAddress address : Collections.list(nic.getInetAddresses()))
                if (address instanceof Inet4Address) addresses.add(address.getHostAddress());
        }
        return addresses;
    }

    /** Use assigned IPs, not interface names: eth0.12 can carry 198.18.44.x. */
    static List<String> candidates(String configured, List<String> local) throws IOException {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        hosts.add(CpAddress.parse(configured));
        for (String address : local) if (vehicleNetwork(address)) hosts.add(prefix(address) + ".2");
        for (int subnet : new int[]{44, 37, 35, 36, 38, 39, 40, 43, 45, 48, 49, 1, 2, 10, 100, 168})
            hosts.add("198.18." + subnet + ".2");
        for (int subnet = 0; subnet < 256; subnet++) hosts.add("198.18." + subnet + ".2");
        hosts.removeAll(local);
        return new ArrayList<>(hosts);
    }

    /** Only expand the explicitly configured private/vehicle subnet, never the Wi-Fi subnet. */
    static List<String> fallback(String configured, List<String> local, List<String> scanned) {
        List<String> hosts = new ArrayList<>();
        String[] p = configured.split("\\.");
        int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
        if (!(vehicleNetwork(configured) || a == 10 || a == 172 && b >= 16 && b <= 31 || a == 192 && b == 168)) return hosts;
        Set<String> excluded = new HashSet<>(local); excluded.addAll(scanned);
        for (int last = 1; last < 255; last++) {
            String host = prefix(configured) + "." + last;
            if (!excluded.contains(host)) hosts.add(host);
        }
        return hosts;
    }

    private static String prefix(String address) {return address.substring(0, address.lastIndexOf('.'));}
    private static boolean vehicleNetwork(String address) {return address.startsWith("198.18.") || address.startsWith("198.19.");}

    public Result scan(String configured, List<String> local, Progress progress) throws IOException {
        List<String> first = candidates(configured, local);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(16);
        Result result = scanHosts(first, false, deadline, progress);
        if (result.endpoints.isEmpty() && !result.timedOut) {
            Result next = scanHosts(fallback(configured, local, first), true, deadline, progress);
            result = new Result(next.endpoints, result.checked + next.checked, next.timedOut);
        }
        return result;
    }

    Result scanHosts(List<String> hosts, boolean fallback, long deadline, Progress progress) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CompletionService<Endpoint> completions = new ExecutorCompletionService<>(pool);
        List<Endpoint> found = new ArrayList<>();
        int sent = 0, checked = 0;
        try {
            progress.update(fallback, 0, hosts.size(), 0);
            while (checked < hosts.size()) {
                if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("CP IP 探测已取消");
                if (System.nanoTime() >= deadline) break;
                while (sent < hosts.size() && sent - checked < WORKERS) {
                    String host = hosts.get(sent++);
                    completions.submit(() -> probe(host, 22, CONNECT_MS, BANNER_MS));
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                Future<Endpoint> done = completions.poll(Math.min(left, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                if (done == null) continue;
                Endpoint endpoint = done.get(); checked++;
                if (endpoint != null) found.add(endpoint);
                progress.update(fallback, checked, hosts.size(), found.size());
            }
            if (cancelled) throw new InterruptedIOException("CP IP 探测已取消");
            found.sort(Comparator.comparingLong(e -> numeric(e.host)));
            return new Result(List.copyOf(found), checked, checked < hosts.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("CP IP 探测已取消");
        } catch (ExecutionException e) {throw new IOException("CP IP 探测失败", e.getCause());}
        finally {
            pool.shutdownNow();
            for (Socket socket : sockets) try {socket.close();} catch (IOException ignored) {}
        }
    }

    private static long numeric(String host) {
        long value = 0; for (String p : host.split("\\.")) value = value * 256 + Integer.parseInt(p); return value;
    }

    Endpoint probe(String host, int port, int connectMs, int readMs) {
        try (Socket socket = new Socket()) {
            sockets.add(socket);
            try {
                if (cancelled || Thread.currentThread().isInterrupted()) return null;
                socket.connect(new InetSocketAddress(host, port), connectMs);
                socket.setSoTimeout(readMs);
                String banner = banner(socket.getInputStream(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readMs));
                return banner == null ? null : new Endpoint(host, banner);
            } finally {sockets.remove(socket);}
        } catch (IOException e) {return null;}
    }

    /** RFC 4253 identification, bounded preamble and absolute deadline even for trickle responses. */
    static String banner(InputStream in, long deadline) throws IOException {
        for (int line = 0, bytes = 0; line < 16 && bytes < 2048; line++) {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            boolean ended = false;
            while (text.size() < 255 && ++bytes <= 2048) {
                if (System.nanoTime() >= deadline) return null;
                int c = in.read();
                if (c < 0) return null;
                if (c == '\n') {ended = true; break;}
                text.write(c);
            }
            if (!ended) return null;
            String value = new String(text.toByteArray(), StandardCharsets.US_ASCII);
            if (value.endsWith("\r")) value = value.substring(0, value.length() - 1);
            if (value.startsWith("SSH-"))
                return value.matches("SSH-(2\\.0|1\\.99)-[\\x21-\\x2c\\x2e-\\x7e]+(?: [\\x20-\\x7e]*)?") ? value : null;
        }
        return null;
    }
}
