package fi.iki.elonen;

import java.io.InputStream;

/**
 * Works around a NanoHTTPD bug where {@code newFixedLengthResponse()} adds a
 * {@code Content-Length} header to the response header map, but
 * {@code Response.send()} unconditionally emits <em>another</em>
 * {@code Content-Length} via {@code sendContentLengthHeaderIfNotAlreadyPresent()},
 * producing <b>duplicate Content-Length headers</b>.
 *
 * <p>The protected {@code Response} constructor sets the internal
 * {@code contentLength} field without adding to the header map, so
 * {@code send()} emits exactly one {@code Content-Length}.
 */
public class NanoFixedLengthResponse {

    private NanoFixedLengthResponse() {}

    public static NanoHTTPD.Response create(
            NanoHTTPD.Response.IStatus status,
            String mimeType,
            InputStream data,
            long totalBytes) {
        return new NanoHTTPD.Response(status, mimeType, data, totalBytes);
    }
}
