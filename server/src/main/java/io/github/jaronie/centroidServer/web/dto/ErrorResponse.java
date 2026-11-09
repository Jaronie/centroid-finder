package io.github.jaronie.centroidServer.web.dto;

**
 * Every error body in the spec: {"error": "..."}.
 *
 * <p>A record rather than a Map so the compiler checks the field name. In
 * Express you would write the object literal inline, and a typo in "error"
 * would ship silently.
 */

public record ErrorResponse(String error) {
}
