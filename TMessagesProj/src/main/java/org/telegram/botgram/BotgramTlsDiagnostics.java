package org.telegram.botgram;

import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.util.Locale;

/** Only fixed TLS error codes and exception class names are displayed. */
public final class BotgramTlsDiagnostics {
    private BotgramTlsDiagnostics() { }

    public static String describe(Throwable error) {
        StringBuilder types = new StringBuilder();
        String reason = null;
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (types.length() != 0) types.append(" > ");
            types.append(current.getClass().getSimpleName());
            if (current instanceof CertificateExpiredException) reason = "CERTIFICATE_EXPIRED";
            else if (current instanceof CertificateNotYetValidException) reason = "CERTIFICATE_NOT_YET_VALID";
            else if (reason == null && current instanceof CertPathValidatorException)
                reason = "CERTIFICATE_PATH_INVALID";
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("trust anchor")) reason = "CERTIFICATE_TRUST_ANCHOR_MISSING";
                else if (lower.contains("subject alternative") || lower.contains("subjectaltname")
                        || lower.contains("hostname") && lower.contains("verif")) reason = "CERTIFICATE_HOSTNAME_MISMATCH";
                else if (reason == null && lower.contains("no_application_protocol")) reason = "TLS_ALPN_NEGOTIATION_FAILED";
                else if (reason == null && lower.contains("protocol_version")) reason = "TLS_PROTOCOL_VERSION_REJECTED";
                else if (reason == null && lower.contains("handshake_failure")) reason = "TLS_HANDSHAKE_ALERT";
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        if (reason != null) return reason + " / " + types;
        return types.length() == 0 ? "TLS_CAUSE_UNAVAILABLE" : types.toString();
    }
}
