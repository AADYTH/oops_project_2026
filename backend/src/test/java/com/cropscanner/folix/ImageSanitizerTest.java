package com.cropscanner.folix;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ImageSanitizerTest {

    private final ImageSanitizer sanitizer = new ImageSanitizer(10 * 1024 * 1024);

    private static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + n.length <= haystack.length; i++) {
            for (int j = 0; j < n.length; j++) if (haystack[i + j] != n[j]) continue outer;
            return true;
        }
        return false;
    }

    private static void assertCode(FolixAnalysisException.Code expected, Runnable r) {
        FolixAnalysisException e = assertThrows(FolixAnalysisException.class, r::run);
        assertEquals(expected, e.code());
    }

    @Test
    void jpegExifGpsCommentsAreStrippedAndTypeIsSniffed() {
        byte[] in = TestImages.jpeg_exif();
        assertTrue(contains(in, TestImages.MARKER), "fixture must actually contain the marker");
        assertTrue(contains(in, "SECRET-COMMENT-MARKER"));

        ImageSanitizer.SanitizedImage out = sanitizer.sanitize(in);

        assertEquals("image/jpeg", out.mimeType());
        assertFalse(contains(out.bytes(), TestImages.MARKER));
        assertFalse(contains(out.bytes(), "SECRET-PHONE-MAKE"));
        assertFalse(contains(out.bytes(), "SECRET-COMMENT-MARKER"));
        assertFalse(contains(out.bytes(), "Exif"), "no EXIF header may survive");
        assertTrue(out.bytes().length < in.length);
        assertEquals((byte) 0xFF, out.bytes()[0]);
        assertEquals((byte) 0xD8, out.bytes()[1]);
        assertEquals((byte) 0xD9, out.bytes()[out.bytes().length - 1]);
    }

    @Test
    void progressiveJpegIsStrippedAndKeepsAllScans() {
        byte[] in = TestImages.jpeg_progressive_exif();
        ImageSanitizer.SanitizedImage out = sanitizer.sanitize(in);
        assertFalse(contains(out.bytes(), TestImages.MARKER));
        // A progressive JPEG has several SOS (FFDA) segments; all of them must be preserved.
        assertEquals(countMarker(in, 0xDA), countMarker(out.bytes(), 0xDA));
        assertTrue(countMarker(out.bytes(), 0xDA) > 1);
    }

    @Test
    void metadataFreeJpegSurvivesByteForByte() {
        byte[] in = TestImages.jpeg_plain();
        assertArrayEquals(in, sanitizer.sanitize(in).bytes());
    }

    @Test
    void bytesAppendedAfterEndOfImageAreDiscarded() {
        byte[] plain = TestImages.jpeg_plain();
        byte[] junk = "TRAILING-JUNK-MARKER".getBytes(StandardCharsets.ISO_8859_1);
        byte[] in = Arrays.copyOf(plain, plain.length + junk.length);
        System.arraycopy(junk, 0, in, plain.length, junk.length);
        byte[] out = sanitizer.sanitize(in).bytes();
        assertFalse(contains(out, "TRAILING-JUNK-MARKER"));
        assertArrayEquals(plain, out);
    }

    @Test
    void pngTextAndExifChunksAreStripped() {
        byte[] in = TestImages.png_text_exif();
        assertTrue(contains(in, TestImages.MARKER));
        ImageSanitizer.SanitizedImage out = sanitizer.sanitize(in);
        assertEquals("image/png", out.mimeType());
        assertFalse(contains(out.bytes(), TestImages.MARKER));
        assertFalse(contains(out.bytes(), "tEXt"));
        assertFalse(contains(out.bytes(), "eXIf"));
        assertTrue(contains(out.bytes(), "IDAT"));
        assertTrue(contains(out.bytes(), "IEND"));
    }

    @Test
    void metadataFreePngIsUnchanged() {
        byte[] in = TestImages.png_plain();
        assertArrayEquals(in, sanitizer.sanitize(in).bytes());
    }

    @Test
    void webpExifAndXmpAreStrippedAndRiffSizeIsConsistent() {
        byte[] in = TestImages.webp_exif_xmp();
        assertTrue(contains(in, TestImages.MARKER));
        ImageSanitizer.SanitizedImage out = sanitizer.sanitize(in);
        assertEquals("image/webp", out.mimeType());
        byte[] b = out.bytes();
        assertFalse(contains(b, TestImages.MARKER));
        assertFalse(contains(b, "EXIF"));
        assertFalse(contains(b, "XMP "));
        long riff = (b[4] & 0xFFL) | ((b[5] & 0xFFL) << 8) | ((b[6] & 0xFFL) << 16) | ((b[7] & 0xFFL) << 24);
        assertEquals(b.length - 8, riff, "RIFF size must match the rebuilt file");
        // VP8X flag byte: EXIF (0x08) and XMP (0x04) bits must be cleared.
        assertTrue(contains(b, "VP8X"));
        int vp8x = indexOf(b, "VP8X");
        assertEquals(0, b[vp8x + 8] & 0x0C);
    }

    @Test
    void metadataFreeWebpIsUnchanged() {
        byte[] in = TestImages.webp_plain();
        assertArrayEquals(in, sanitizer.sanitize(in).bytes());
    }

    @Test
    void contentTypeClaimsAreIrrelevantOnlyTheBytesDecide() {
        // A plain-text file is rejected no matter what a client might claim it is.
        assertCode(FolixAnalysisException.Code.UNSUPPORTED_IMAGE_TYPE,
                () -> sanitizer.sanitize("this is not an image at all".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void unsupportedFormatsAreRejected() {
        assertCode(FolixAnalysisException.Code.UNSUPPORTED_IMAGE_TYPE,
                () -> sanitizer.sanitize("GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1)));
        assertCode(FolixAnalysisException.Code.UNSUPPORTED_IMAGE_TYPE,
                () -> sanitizer.sanitize("%PDF-1.7\n1 0 obj".getBytes(StandardCharsets.ISO_8859_1)));
        assertCode(FolixAnalysisException.Code.UNSUPPORTED_IMAGE_TYPE,
                () -> sanitizer.sanitize("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void emptyAndNullUploadsAreInvalid() {
        assertCode(FolixAnalysisException.Code.INVALID_IMAGE, () -> sanitizer.sanitize(new byte[0]));
        assertCode(FolixAnalysisException.Code.INVALID_IMAGE, () -> sanitizer.sanitize(null));
    }

    @Test
    void oversizedUploadIsRejectedBeforeAnyParsing() {
        ImageSanitizer tiny = new ImageSanitizer(100);
        assertCode(FolixAnalysisException.Code.IMAGE_TOO_LARGE, () -> tiny.sanitize(TestImages.jpeg_plain()));
    }

    @Test
    void truncatedOrCorruptFilesAreInvalidNotForwarded() {
        byte[] jpeg = TestImages.jpeg_exif();
        assertCode(FolixAnalysisException.Code.INVALID_IMAGE,
                () -> sanitizer.sanitize(Arrays.copyOf(jpeg, 20)));           // ends inside the header segments
        assertCode(FolixAnalysisException.Code.INVALID_IMAGE,
                () -> sanitizer.sanitize(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9})); // no image data
        byte[] png = TestImages.png_plain();
        assertCode(FolixAnalysisException.Code.INVALID_IMAGE,
                () -> sanitizer.sanitize(Arrays.copyOf(png, png.length - 20))); // no IEND
        byte[] webp = TestImages.webp_plain();
        assertCode(FolixAnalysisException.Code.INVALID_IMAGE,
                () -> sanitizer.sanitize(Arrays.copyOf(webp, webp.length - 30))); // RIFF size exceeds data
    }

    @Test
    void blurryOrLowQualityPhotosAreNotRejectedByTheSanitizer() {
        // The sanitizer only checks structure. A tiny, featureless image is still passed through:
        // judging evidence sufficiency is Gemini's job, not a local quality gate.
        assertDoesNotThrowSanitizing(TestImages.jpeg_plain());
    }

    private void assertDoesNotThrowSanitizing(byte[] b) {
        assertNotNull(sanitizer.sanitize(b));
    }

    private static int countMarker(byte[] b, int marker) {
        // Counts SOS markers structurally (FF DA followed by a plausible length), good enough for fixtures.
        int c = 0;
        for (int i = 0; i + 3 < b.length; i++) {
            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == marker && b[i + 2] == 0 && (b[i + 3] & 0xFF) >= 2) c++;
        }
        return c;
    }

    private static int indexOf(byte[] h, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + n.length <= h.length; i++) {
            for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }
}
