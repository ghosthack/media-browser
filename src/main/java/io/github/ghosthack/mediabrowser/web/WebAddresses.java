package io.github.ghosthack.mediabrowser.web;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * This machine's IPv4 addresses a phone could reach, best first, for the
 * phone link and its QR code. The server listens on every interface; this
 * only decides which address the link names.
 *
 * <p>Ranking: private LAN ranges (192.168/16, 10/8, 172.16/12) first, the
 * usual Wi-Fi/Ethernet interface names ({@code en*}, {@code eth*},
 * {@code wl*}) before others, then shared-address space (100.64/10, which is
 * where Tailscale and carrier NAT live), then anything else routable.
 * Loopback, link-local (169.254/16), and interfaces that are down or virtual
 * are left out.</p>
 */
public final class WebAddresses {

    /** One candidate: the interface's name and the dotted address. */
    public record Candidate(String interfaceName, String address) {}

    private WebAddresses() {}

    /** The candidates on this machine, best first; empty when it has none. */
    public static List<Candidate> discover() {
        var found = new ArrayList<Candidate>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                } catch (SocketException e) {
                    continue;
                }
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        found.add(new Candidate(nif.getName(), a.getHostAddress()));
                    }
                }
            }
        } catch (SocketException e) {
            return List.of();
        }
        return rank(found);
    }

    /** {@code candidates} without unusable addresses, best first (stable for ties). */
    static List<Candidate> rank(List<Candidate> candidates) {
        return candidates.stream()
                .filter(c -> score(c) >= 0)
                .sorted(Comparator.comparingInt(WebAddresses::score))
                .toList();
    }

    /** Lower is better; negative means unusable. */
    static int score(Candidate c) {
        int[] o = octets(c.address());
        if (o == null) return -1;
        if (o[0] == 127 || o[0] == 0 || (o[0] == 169 && o[1] == 254) || o[0] >= 224) return -1;
        boolean lan = o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] >= 16 && o[1] <= 31);
        boolean shared = o[0] == 100 && o[1] >= 64 && o[1] <= 127;
        String name = c.interfaceName() == null ? "" : c.interfaceName().toLowerCase(Locale.ROOT);
        boolean usual = name.startsWith("en") || name.startsWith("eth") || name.startsWith("wl")
                || name.startsWith("wi");
        int range = lan ? 0 : shared ? 20 : 10;
        return range + (usual ? 0 : 5);
    }

    private static int[] octets(String address) {
        String[] parts = address.split("\\.");
        if (parts.length != 4) return null;
        int[] o = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                o[i] = Integer.parseInt(parts[i]);
                if (o[i] < 0 || o[i] > 255) return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return o;
    }

    /** The phone link for {@code host}: {@code http://host:port/#k=passkey}. */
    public static String phoneLink(String host, int port, String passkey) {
        return "http://" + host + ":" + port + "/#k=" + passkey;
    }
}
