package com.finalexec.boundary;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Wave 2: writes a {@link BoundaryViolation} as a JSON REST error response.
 * Shared by every boundary that rejects at the HTTP layer (B2, B9, B10, B17).
 */
public final class BoundaryViolationResponse {
    private BoundaryViolationResponse() {}

    public static void write(HttpServletResponse response, int status, BoundaryViolation violation) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(violation.toJson());
    }
}
