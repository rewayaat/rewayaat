package com.rewayaat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Answers whether an address is Canadian, from a list shipped with the application.
 *
 * <p>The ranges come from two sources that disagree in a useful way. The regional
 * registries' delegated-extended files record who an allocation was <em>issued</em> to.
 * GeoLite2 measures where a network actually <em>answers</em> from. A VPN exit serving
 * Montreal on a block registered in Panama is Panama to the registries and Canada to
 * GeoLite2, and it is a reader in Canada either way - so the shipped list is the union,
 * which covers about seven million addresses the registries alone miss.
 *
 * <p>Carrying a list at all, rather than calling a geolocation service, keeps a third
 * party out of the path of every request: the visitor addresses such a service would be
 * asked about are not ours to hand out. Regenerate with
 * {@code scripts/ops/generate_ca_ranges.py}.
 *
 * <p>The list goes stale, and the direction it fails in is deliberate. An address this
 * does not recognise is reported as not Canadian, so a reassigned block redirects to the
 * canonical host rather than quietly staying behind on a host that is being retired.
 *
 * <p>Country-level address geolocation is never exact, and a VPN the databases have not
 * caught up with still defeats it. Nothing here should ever decide what a visitor is
 * allowed to see - only which host serves them.
 *
 * <p>This product includes GeoLite2 data created by MaxMind, available from
 * <a href="https://www.maxmind.com">https://www.maxmind.com</a>.
 */
@Component
public class CanadianAddresses {

    private static final Logger log = LoggerFactory.getLogger(CanadianAddresses.class);

    private final long[] v4Start;
    private final long[] v4End;
    private final BigInteger[] v6Start;
    private final BigInteger[] v6End;

    public CanadianAddresses() {
        List<long[]> four = new ArrayList<>();
        List<BigInteger[]> six = new ArrayList<>();
        readInto("geo/ca-ipv4.txt", parts -> four.add(new long[]{
                Long.parseLong(parts[0]), Long.parseLong(parts[1])}));
        readInto("geo/ca-ipv6.txt", parts -> six.add(new BigInteger[]{
                new BigInteger(parts[0], 16), new BigInteger(parts[1], 16)}));

        this.v4Start = four.stream().mapToLong(r -> r[0]).toArray();
        this.v4End = four.stream().mapToLong(r -> r[1]).toArray();
        this.v6Start = six.stream().map(r -> r[0]).toArray(BigInteger[]::new);
        this.v6End = six.stream().map(r -> r[1]).toArray(BigInteger[]::new);
        log.info("Canadian address ranges loaded: {} IPv4, {} IPv6", v4Start.length, v6Start.length);
    }

    private interface RowReader {
        void accept(String[] parts);
    }

    private static void readInto(String resource, RowReader reader) {
        ClassPathResource file = new ClassPathResource(resource);
        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2) {
                    reader.accept(parts);
                }
            }
        } catch (IOException | RuntimeException e) {
            // An empty table reports every address as not Canadian, which is the same way
            // a stale one fails: towards the canonical host rather than away from it.
            log.error("Could not read {}; treating every address as outside Canada", resource, e);
        }
    }

    /** True only for an address the shipped list places in Canada. */
    public boolean contains(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        InetAddress parsed;
        try {
            parsed = InetAddress.getByName(address.trim());
        } catch (UnknownHostException e) {
            return false;
        }
        byte[] bytes = parsed.getAddress();
        if (parsed instanceof Inet4Address) {
            long value = 0;
            for (byte b : bytes) {
                value = (value << 8) | (b & 0xFF);
            }
            int i = Arrays.binarySearch(v4Start, value);
            if (i >= 0) {
                return true;
            }
            i = -i - 2;
            return i >= 0 && value <= v4End[i];
        }
        BigInteger value = new BigInteger(1, bytes);
        int i = Arrays.binarySearch(v6Start, value);
        if (i >= 0) {
            return true;
        }
        i = -i - 2;
        return i >= 0 && value.compareTo(v6End[i]) <= 0;
    }
}
