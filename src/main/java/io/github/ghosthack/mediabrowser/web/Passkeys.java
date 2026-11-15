package io.github.ghosthack.mediabrowser.web;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * The phone page's "Wi-Fi-like" passkey: four groups of four lower-case
 * letters ({@code abcd-efgh-jkmn-pqrs}), drawn from an alphabet without the
 * letters people misread ({@code i}, {@code l}, {@code o}). 16 letters from 23
 * is about 72 bits — far beyond guessing through the login throttle.
 *
 * <p>Typing is forgiving: case, spaces and dashes are ignored, so
 * {@code ABCD EFGH JKMN PQRS} and {@code abcdefghjkmnpqrs} are the same
 * passkey. The cookie a phone keeps is not the passkey itself but an HMAC
 * derived from it ({@link #cookieToken}), so rotating the passkey invalidates
 * every cookie at once, and a cookie never reveals the passkey.</p>
 */
public final class Passkeys {

    /** a–z without i, l, o. */
    static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz";
    static final int GROUPS = 4;
    static final int GROUP_LENGTH = 4;
    static final int LETTERS = GROUPS * GROUP_LENGTH;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] COOKIE_LABEL =
            "media-browser web cookie v1".getBytes(StandardCharsets.US_ASCII);

    private Passkeys() {}

    /** A fresh random passkey in its canonical dashed form. */
    public static String generate() {
        var letters = new StringBuilder(LETTERS);
        for (int i = 0; i < LETTERS; i++) {
            letters.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return format(letters.toString());
    }

    /** {@code text} lower-cased with everything but letters removed. */
    public static String normalize(String text) {
        if (text == null) return "";
        var out = new StringBuilder(text.length());
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z') out.append(c);
        }
        return out.toString();
    }

    /**
     * {@code text} as a canonical passkey, or empty when it is not one (wrong
     * length, or a letter outside the alphabet).
     */
    public static Optional<String> canonical(String text) {
        String letters = normalize(text);
        if (letters.length() != LETTERS) return Optional.empty();
        for (char c : letters.toCharArray()) {
            if (ALPHABET.indexOf(c) < 0) return Optional.empty();
        }
        return Optional.of(format(letters));
    }

    /** Normalized letters grouped by dashes. */
    static String format(String letters) {
        var out = new StringBuilder();
        for (int i = 0; i < letters.length(); i++) {
            if (i > 0 && i % GROUP_LENGTH == 0) out.append('-');
            out.append(letters.charAt(i));
        }
        return out.toString();
    }

    /**
     * Whether {@code given} (as typed) is {@code passkey}, compared in
     * constant time over the normalized letters.
     */
    public static boolean matches(String passkey, String given) {
        byte[] expected = normalize(passkey).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = normalize(given).getBytes(StandardCharsets.US_ASCII);
        return expected.length > 0 && MessageDigest.isEqual(expected, actual);
    }

    /** The cookie value a phone holding {@code passkey} keeps: HMAC-SHA256, base64url. */
    public static String cookieToken(String passkey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    normalize(passkey).getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(COOKIE_LABEL));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
