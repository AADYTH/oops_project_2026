package com.cropscanner.folix;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

/**
 * Validates an upload as a real JPEG / PNG / WebP file and removes embedded
 * metadata BEFORE anything is sent to Gemini.
 *
 * Why this exists: phone photos routinely carry EXIF blocks with precise GPS
 * coordinates, device identifiers and timestamps. The Scanner brief says Gemini
 * must receive only what visual analysis needs — no precise location, no
 * unrelated metadata. The browser strips metadata when it re-encodes the photo
 * (see scanner-experience.js), but the server cannot trust that every caller is
 * our frontend, so it strips too.
 *
 * Design choices:
 *  - The file's TRUE type is decided from its magic bytes; the client-declared
 *    Content-Type is deliberately ignored (it is trivially spoofable and
 *    real phone uploads are sometimes mislabeled).
 *  - Stripping is LOSSLESS: segments/chunks are copied or dropped, pixels are
 *    never decoded or re-encoded (no quality loss, no ImageIO dependency, no
 *    decompression-bomb surface, and it works for WebP which the JDK cannot read).
 *  - Anything structurally malformed is rejected as INVALID_IMAGE rather than
 *    forwarded, so this also acts as the "is this really an image" gate.
 *  - It is NOT an image-quality classifier. Blurry, dark or partial photos pass
 *    straight through; judging evidence sufficiency is Gemini's job.
 */
@Component
public class ImageSanitizer {

    /** A validated image with metadata removed, plus its sniffed MIME type. */
    public record SanitizedImage(byte[] bytes, String mimeType) {
    }

    private final long maxBytes;

    public ImageSanitizer(@Value("${folix.scanner.max-image-bytes:10485760}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public SanitizedImage sanitize(byte[] input) {
        if (input == null || input.length == 0) {
            throw FolixAnalysisException.invalidImage("empty upload");
        }
        if (input.length > maxBytes) {
            throw FolixAnalysisException.tooLarge("upload of " + input.length + " bytes exceeds limit " + maxBytes);
        }
        if (isJpeg(input)) {
            return new SanitizedImage(stripJpeg(input), "image/jpeg");
        }
        if (isPng(input)) {
            return new SanitizedImage(stripPng(input), "image/png");
        }
        if (isWebp(input)) {
            return new SanitizedImage(stripWebp(input), "image/webp");
        }
        throw FolixAnalysisException.unsupportedType("unrecognized file signature");
    }

    // ------------------------------------------------------------------
    // Signatures
    // ------------------------------------------------------------------

    private static boolean isJpeg(byte[] b) {
        return b.length >= 4 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    private static final int[] PNG_SIG = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private static boolean isPng(byte[] b) {
        if (b.length < PNG_SIG.length) return false;
        for (int i = 0; i < PNG_SIG.length; i++) {
            if ((b[i] & 0xFF) != PNG_SIG[i]) return false;
        }
        return true;
    }

    private static boolean isWebp(byte[] b) {
        return b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    // ------------------------------------------------------------------
    // JPEG — walk marker segments, drop metadata-bearing ones, stop at first EOI
    // ------------------------------------------------------------------

    /**
     * Keeps: APP0 (JFIF), APP2 (ICC colour profile), APP14 (Adobe colour transform —
     * needed to decode some CMYK/YCCK files correctly) and every non-application
     * segment. Drops: APP1 (EXIF + XMP, where GPS lives), APP3–APP13 and APP15
     * (IPTC/Photoshop/vendor blocks) and COM comments. Anything after the first
     * EOI (e.g. the embedded gain-map JPEG of "Ultra HDR" phone photos, or
     * appended junk) is discarded.
     */
    private static byte[] stripJpeg(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(0xFF);
        out.write(0xD8);
        int pos = 2;
        boolean sawFrame = false;

        while (true) {
            // Find the next marker: 0xFF followed by a non-0xFF, non-0x00 byte (0xFF fill bytes are legal).
            if (pos >= b.length) {
                throw FolixAnalysisException.invalidImage("JPEG ended before image data");
            }
            if ((b[pos] & 0xFF) != 0xFF) {
                throw FolixAnalysisException.invalidImage("JPEG marker expected at offset " + pos);
            }
            while (pos + 1 < b.length && (b[pos + 1] & 0xFF) == 0xFF) {
                pos++; // skip fill bytes
            }
            if (pos + 1 >= b.length) {
                throw FolixAnalysisException.invalidImage("JPEG truncated in marker");
            }
            int marker = b[pos + 1] & 0xFF;

            if (marker == 0xD9) { // EOI before any scan
                throw FolixAnalysisException.invalidImage("JPEG has no image data");
            }
            if (marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0xD8) {
                throw FolixAnalysisException.invalidImage("unexpected JPEG marker 0x" + Integer.toHexString(marker));
            }

            int len = segmentLength(b, pos);
            int segEnd = pos + 2 + len;

            if (isFrameMarker(marker)) sawFrame = true;

            if (!isDroppedJpegSegment(marker)) {
                out.write(b, pos, 2 + len);
            }

            if (marker == 0xDA) { // SOS: entropy-coded data follows
                if (!sawFrame) {
                    throw FolixAnalysisException.invalidImage("JPEG scan without a frame header");
                }
                copyScanData(b, segEnd, out);
                return out.toByteArray();
            }
            pos = segEnd;
        }
    }

    /** Copies entropy-coded data and any further scan/table segments up to and including the first EOI. */
    private static void copyScanData(byte[] b, int start, ByteArrayOutputStream out) {
        int i = start;
        int copiedFrom = start;
        while (i + 1 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int next = b[i + 1] & 0xFF;
            if (next == 0x00 || (next >= 0xD0 && next <= 0xD7)) { // stuffed byte / restart marker
                i += 2;
                continue;
            }
            if (next == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (next == 0xD9) { // EOI — done; drop anything after it
                out.write(b, copiedFrom, i + 2 - copiedFrom);
                return;
            }
            // A real marker with a length (next progressive scan's SOS, DHT, DQT, DRI, APPn, COM...).
            int len = segmentLength(b, i);
            int segEnd = i + 2 + len;
            out.write(b, copiedFrom, i - copiedFrom); // entropy data before the marker
            if (!isDroppedJpegSegment(next)) {
                out.write(b, i, 2 + len);
            }
            i = segEnd;
            copiedFrom = segEnd;
        }
        // No EOI found: a truncated file. Forward what we have; Gemini decides whether it is usable.
        out.write(b, copiedFrom, b.length - copiedFrom);
    }

    private static int segmentLength(byte[] b, int markerPos) {
        if (markerPos + 3 >= b.length) {
            throw FolixAnalysisException.invalidImage("JPEG segment truncated");
        }
        int len = ((b[markerPos + 2] & 0xFF) << 8) | (b[markerPos + 3] & 0xFF);
        if (len < 2 || markerPos + 2 + len > b.length) {
            throw FolixAnalysisException.invalidImage("JPEG segment length out of range");
        }
        return len;
    }

    private static boolean isFrameMarker(int m) {
        return m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
    }

    private static boolean isDroppedJpegSegment(int marker) {
        if (marker == 0xFE) return true; // COM
        if (marker >= 0xE0 && marker <= 0xEF) {
            return !(marker == 0xE0 || marker == 0xE2 || marker == 0xEE); // keep APP0, APP2, APP14
        }
        return false;
    }

    // ------------------------------------------------------------------
    // PNG — walk chunks, drop textual/EXIF/time chunks, stop after IEND
    // ------------------------------------------------------------------

    private static byte[] stripPng(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 8);
        int pos = 8;
        boolean first = true;

        while (pos + 12 <= b.length) {
            long len = ((long) (b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16)
                    | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
            String type = new String(b, pos + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            long total = 12L + len; // length + type + data + crc
            if (len > b.length || pos + total > b.length) {
                throw FolixAnalysisException.invalidImage("PNG chunk length out of range");
            }
            if (first && !type.equals("IHDR")) {
                throw FolixAnalysisException.invalidImage("PNG missing IHDR");
            }
            first = false;

            boolean drop = type.equals("eXIf") || type.equals("tEXt") || type.equals("zTXt")
                    || type.equals("iTXt") || type.equals("tIME");
            if (!drop) {
                out.write(b, pos, (int) total);
            }
            pos += (int) total;
            if (type.equals("IEND")) {
                return out.toByteArray(); // discard trailing bytes
            }
        }
        throw FolixAnalysisException.invalidImage("PNG has no IEND chunk");
    }

    // ------------------------------------------------------------------
    // WebP — RIFF chunks; drop EXIF + XMP, clear their VP8X flags, fix RIFF size
    // ------------------------------------------------------------------

    private static byte[] stripWebp(byte[] b) {
        long riffSize = readLe32(b, 4);
        long declaredEnd = 8 + riffSize;
        if (declaredEnd > b.length || riffSize < 4) {
            throw FolixAnalysisException.invalidImage("WebP RIFF size out of range");
        }
        int end = (int) declaredEnd;

        ByteArrayOutputStream body = new ByteArrayOutputStream(b.length);
        int pos = 12;
        boolean sawImageData = false;

        while (pos + 8 <= end) {
            String fourcc = new String(b, pos, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            long size = readLe32(b, pos + 4);
            long padded = size + (size & 1);
            if (pos + 8 + padded > end) {
                // Some encoders omit the final pad byte; tolerate exactly that case.
                if (pos + 8 + size <= end) {
                    padded = size;
                } else {
                    throw FolixAnalysisException.invalidImage("WebP chunk length out of range");
                }
            }
            int dataStart = pos + 8;
            int chunkTotal = (int) (8 + padded);

            if (fourcc.equals("VP8 ") || fourcc.equals("VP8L") || fourcc.equals("ANMF")) {
                sawImageData = true;
            }

            if (fourcc.equals("EXIF") || fourcc.equals("XMP ")) {
                // dropped
            } else if (fourcc.equals("VP8X") && size >= 10) {
                byte[] chunk = new byte[chunkTotal];
                System.arraycopy(b, pos, chunk, 0, chunkTotal);
                chunk[8] = (byte) (chunk[8] & ~0x08 & ~0x04); // clear EXIF (0x08) and XMP (0x04) flags
                body.write(chunk, 0, chunk.length);
            } else {
                body.write(b, pos, chunkTotal);
            }
            pos = dataStart + (int) padded;
        }
        if (!sawImageData) {
            throw FolixAnalysisException.invalidImage("WebP has no image data");
        }

        byte[] bodyBytes = body.toByteArray();
        long newRiffSize = 4L + bodyBytes.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(12 + bodyBytes.length);
        out.write('R'); out.write('I'); out.write('F'); out.write('F');
        writeLe32(out, newRiffSize);
        out.write('W'); out.write('E'); out.write('B'); out.write('P');
        out.write(bodyBytes, 0, bodyBytes.length);
        return out.toByteArray();
    }

    private static long readLe32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static void writeLe32(ByteArrayOutputStream out, long v) {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 24) & 0xFF));
    }
}
