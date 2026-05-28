package com.edgefabric.caching.gossip;

import com.edgefabric.caching.dto.GossipDigestDTO;
import com.edgefabric.caching.dto.GossipDigestDTO.GossipNodeEntry;
import com.edgefabric.caching.model.Status;
import lombok.extern.slf4j.Slf4j;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Exception;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact binary codec for gossip UDP payloads with LZ4 compression.
 *
 * <h3>Optimisations over the previous JSON format</h3>
 * <ol>
 *   <li><b>No field labels</b> — binary format; field positions are fixed by schema.</li>
 *   <li><b>FULL vs DELTA entries</b> — nodes whose heartbeat is below
 *       {@link #FULL_ENTRY_HEARTBEAT_THRESHOLD} are serialised with full network
 *       coordinates (host, ports).  Well-established nodes only send
 *       {@code cacheNodeId + state}, saving ~25 bytes per entry per round.</li>
 *   <li><b>Heartbeat delta</b> — each entry's heartbeat is stored as
 *       {@code absHeartbeat - senderHeartbeat}, a small signed integer that
 *       encodes in 4 bytes and compresses near-perfectly.</li>
 *   <li><b>LZ4 compression</b> — the entire inner body is LZ4-compressed before
 *       sending. Achieves 40–70 % size reduction on typical gossip payloads
 *       (repetitive node IDs, uniform ports).</li>
 * </ol>
 *
 * <h3>Wire format</h3>
 * <pre>
 * Outer packet  (what travels over UDP):
 *   byte  0-1  : magic  0xEF 0xFB  (used to reject malformed / legacy packets fast)
 *   byte  2-5  : decompressed body length (int, big-endian)
 *   byte  6+   : LZ4-compressed body
 *
 * Inner body  (after decompression):
 *   1 byte  : senderNodeId length
 *   N bytes : senderNodeId (UTF-8)
 *   4 bytes : senderHeartbeat (int, big-endian)  — delta reference
 *   2 bytes : entry count (short, big-endian)
 *
 *   [Per entry]
 *   1 byte  : flags  bit0=0 DELTA, bit0=1 FULL
 *   1 byte  : cacheNodeId length
 *   N bytes : cacheNodeId (UTF-8)
 *   1 byte  : status ordinal  (ALIVE=0 DRAINING=1 SUSPECT=2 DEAD=3)
 *   4 bytes : incarnation (int, big-endian)
 *   4 bytes : heartbeatDelta = absHeartbeat - senderHeartbeat (int, signed, big-endian)
 *   [FULL only – present when flags bit0=1]
 *   1 byte  : host length
 *   N bytes : host (UTF-8)
 *   2 bytes : servicePort (short, unsigned)
 *   2 bytes : gossipPort  (short, unsigned)
 * </pre>
 */
@Slf4j
@Component
public class GossipMessageCodec {

    /** Magic bytes at the start of every outer packet for fast rejection of bad data. */
    static final byte MAGIC_0 = (byte) 0xEF;
    static final byte MAGIC_1 = (byte) 0xFB;

    /**
     * Nodes whose absolute heartbeat is below this threshold are sent as FULL entries
     * (including host + ports). Once a node's heartbeat exceeds this value it is
     * considered "well-established" and only its mutable state is gossiped (DELTA).
     *
     * <p>At a 1-second gossip interval this means a new node announces its full
     * coordinates for the first ~10 seconds, which is more than enough for the
     * information to fan out across a typical cluster.
     */
    static final long FULL_ENTRY_HEARTBEAT_THRESHOLD = 10;

    /** Max decompressed body size accepted; guards against malformed-packet OOM. */
    private static final int MAX_DECOMPRESSED_BYTES = 100_000;

    private static final byte FLAG_FULL  = 0x01;
    private static final byte FLAG_DELTA = 0x00;

    /** Status enum values indexed by ordinal for O(1) decode. */
    private static final Status[] STATUS_BY_ORDINAL = Status.values();

    private final LZ4Compressor   compressor;
    private final LZ4FastDecompressor decompressor;

    public GossipMessageCodec() {
        LZ4Factory factory = LZ4Factory.fastestInstance();
        this.compressor   = factory.fastCompressor();
        this.decompressor = factory.fastDecompressor();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Serialises {@code digest} to the compact binary + LZ4 wire format.
     *
     * @throws IOException if binary serialisation fails (should never happen in practice)
     */
    public byte[] encode(GossipDigestDTO digest) throws IOException {
        byte[] body = serializeBody(digest);
        return wrapWithHeader(body);
    }

    /**
     * Decodes a UDP payload produced by {@link #encode}.
     *
     * @throws IOException if magic bytes are wrong, packet is truncated,
     *                     or decompressed size exceeds {@link #MAX_DECOMPRESSED_BYTES}
     */
    public GossipDigestDTO decode(byte[] data) throws IOException {
        byte[] body = unwrapAndDecompress(data);
        return deserializeBody(body);
    }

    // ── Size estimators (used by GossipSender for MTU splitting) ─────────────

    /**
     * Estimates the uncompressed inner-body size of the packet envelope for a
     * given {@code senderNodeId}.  Used by the MTU-splitting logic; the real
     * on-wire size (after compression + outer header) will be smaller.
     */
    public int estimateEnvelopeSize(String senderNodeId) {
        // 1 (idLen) + idLen + 4 (senderHB) + 2 (entryCount)
        return 1 + utf8Len(senderNodeId) + 4 + 2;
    }

    /**
     * Estimates the uncompressed inner-body size of one entry.
     * FULL entries include the host / port bytes; DELTA entries do not.
     */
    public int estimateEntrySize(GossipNodeEntry entry) {
        // flags(1) + idLen(1) + id + status(1) + incarnation(4) + hbDelta(4)
        int base = 1 + 1 + utf8Len(entry.getCacheNodeId()) + 1 + 4 + 4;
        if (entry.isFullEntry()) {
            // hostLen(1) + host + svcPort(2) + gossipPort(2)
            base += 1 + utf8Len(entry.getHost()) + 2 + 2;
        }
        return base;
    }

    // ── Encode internals ──────────────────────────────────────────────────────

    private byte[] serializeBody(GossipDigestDTO digest) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        DataOutputStream out = new DataOutputStream(baos);

        // Sender header
        writeString(out, digest.getSenderNodeId());
        out.writeInt((int) digest.getSenderHeartbeat());

        // Entries
        List<GossipNodeEntry> entries = digest.getEntries();
        out.writeShort(entries.size());
        for (GossipNodeEntry entry : entries) {
            writeEntry(out, entry, digest.getSenderHeartbeat());
        }
        out.flush();
        return baos.toByteArray();
    }

    private void writeEntry(DataOutputStream out, GossipNodeEntry entry, long senderHB) throws IOException {
        boolean full = entry.isFullEntry();
        out.writeByte(full ? FLAG_FULL : FLAG_DELTA);
        writeString(out, entry.getCacheNodeId());
        out.writeByte(entry.getStatus() != null ? Status.valueOf(entry.getStatus()).ordinal() : 0);
        out.writeInt((int) entry.getIncarnation());
        // Delta: small signed int, compresses extremely well
        out.writeInt((int) (entry.getHeartbeat() - senderHB));
        if (full) {
            writeString(out, entry.getHost());
            out.writeShort(entry.getServicePort());
            out.writeShort(entry.getGossipPort());
        }
    }

    private void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 255) {
            throw new IOException("String too long for 1-byte length prefix: " + s.length() + " chars");
        }
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    // ── Compression ───────────────────────────────────────────────────────────

    private byte[] wrapWithHeader(byte[] body) {
        int maxCompressed = compressor.maxCompressedLength(body.length);
        // Outer layout: 2 magic + 4 decompressed-len + compressed-body
        byte[] out = new byte[6 + maxCompressed];
        out[0] = MAGIC_0;
        out[1] = MAGIC_1;
        int compressedLen = compressor.compress(body, 0, body.length, out, 6, maxCompressed);
        // Big-endian decompressed length in bytes 2-5
        out[2] = (byte) (body.length >>> 24);
        out[3] = (byte) (body.length >>> 16);
        out[4] = (byte) (body.length >>> 8);
        out[5] = (byte)  body.length;
        // Trim to actual size
        byte[] result = new byte[6 + compressedLen];
        System.arraycopy(out, 0, result, 0, result.length);
        return result;
    }

    // ── Decode internals ──────────────────────────────────────────────────────

    private byte[] unwrapAndDecompress(byte[] data) throws IOException {
        if (data.length < 6) {
            throw new IOException("Gossip packet too short: " + data.length + " bytes");
        }
        // Magic check — fast-fail for malformed / legacy packets
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1) {
            throw new IOException(String.format(
                    "Invalid gossip magic: expected 0x%02X 0x%02X but got 0x%02X 0x%02X",
                    MAGIC_0 & 0xFF, MAGIC_1 & 0xFF, data[0] & 0xFF, data[1] & 0xFF));
        }
        int decompressedLen =
                ((data[2] & 0xFF) << 24) | ((data[3] & 0xFF) << 16) |
                ((data[4] & 0xFF) << 8)  |  (data[5] & 0xFF);
        if (decompressedLen <= 0 || decompressedLen > MAX_DECOMPRESSED_BYTES) {
            throw new IOException("Suspicious decompressed length: " + decompressedLen);
        }
        try {
            byte[] body = new byte[decompressedLen];
            decompressor.decompress(data, 6, body, 0, decompressedLen);
            return body;
        } catch (LZ4Exception e) {
            throw new IOException("LZ4 decompression failed: " + e.getMessage(), e);
        }
    }

    private GossipDigestDTO deserializeBody(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));

        String senderNodeId   = readString(in);
        long   senderHB       = Integer.toUnsignedLong(in.readInt());
        int    entryCount     = in.readShort() & 0xFFFF;

        List<GossipNodeEntry> entries = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            entries.add(readEntry(in, senderHB));
        }
        return GossipDigestDTO.builder()
                .senderNodeId(senderNodeId)
                .senderHeartbeat(senderHB)
                .entries(entries)
                .build();
    }

    private GossipNodeEntry readEntry(DataInputStream in, long senderHB) throws IOException {
        byte    flags       = in.readByte();
        boolean full        = (flags & FLAG_FULL) != 0;
        String  cacheNodeId = readString(in);
        int     statusOrd   = in.readByte() & 0xFF;
        if (statusOrd >= STATUS_BY_ORDINAL.length) {
            throw new IOException("Unknown status ordinal: " + statusOrd);
        }
        Status status     = STATUS_BY_ORDINAL[statusOrd];
        long   incarnation = Integer.toUnsignedLong(in.readInt());
        long   hbDelta     = in.readInt();                        // signed delta
        long   heartbeat   = senderHB + hbDelta;                 // restore absolute

        GossipNodeEntry.GossipNodeEntryBuilder builder = GossipNodeEntry.builder()
                .cacheNodeId(cacheNodeId)
                .status(status.name())
                .incarnation(incarnation)
                .heartbeat(heartbeat);

        if (full) {
            builder.host(readString(in))
                   .servicePort(in.readShort() & 0xFFFF)
                   .gossipPort(in.readShort()  & 0xFFFF);
        }
        // For DELTA entries host remains null — receiver enriches from local membership list
        return builder.build();
    }

    private String readString(DataInputStream in) throws IOException {
        int len = in.readByte() & 0xFF;
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Calculates the UTF-8 byte length of a string without allocating a byte array.
     * Much more efficient than {@code s.getBytes(UTF_8).length} in hot paths.
     */
    private int utf8Len(String s) {
        if (s == null) return 0;
        int len = 0;
        int i = 0;
        while (i < s.length()) {
            int codePoint = s.codePointAt(i);
            if (codePoint <= 0x7F) {
                len++;
            } else if (codePoint <= 0x7FF) {
                len += 2;
            } else if (codePoint <= 0xFFFF) {
                len += 3;
            } else {
                len += 4;
            }
            i += Character.charCount(codePoint);
        }
        return len;
    }
}

