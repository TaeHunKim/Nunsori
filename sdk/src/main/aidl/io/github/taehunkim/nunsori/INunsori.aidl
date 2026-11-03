package io.github.taehunkim.nunsori;

/**
 * Nunsori's index as a bound service (action Nunsori.ACTION_BIND), for apps the user allowed (an assistant chat,
 * a notes app). Everything stays on the phone: the caller gets text, ids and thumbnails, never file access.
 *
 * Versioning: methods are only ever appended (explicit transaction ids), never reordered, removed or changed; each
 * notes the API level that added it. Call hello() first. Payloads are JSON strings so fields can be added without
 * breaking older clients (they ignore unknown fields). Errors arrive as IllegalStateException encoded by
 * NunsoriErrors (codes in Nunsori.Errors), or SecurityException for refused callers.
 */
interface INunsori {
    int apiVersion() = 1;

    /**
     * API 1. Exchanges versions. client: {"api":1,"sdk":"0.1.0"}. Returns {"api":1,"minClientApi":1,"appVersion":"0.10.0"}.
     * Throws CLIENT_TOO_OLD when the client's API is below the minimum.
     */
    String hello(String clientJson) = 2;

    /**
     * API 1. Tools a chat model may call, as a JSON array of {"name","description","parameters"(JSON schema),"confirm"}.
     * "confirm": true means runTool only prepares the action: the user approves it in the caller's UI, then commit runs it.
     */
    String tools() = 3;

    /**
     * API 1. Runs a tool with JSON arguments; returns the text the model sees ("Error: ..." on bad input).
     * A confirm tool returns {"confirm":{"title","summary","files":[{"id","name","folder"}],"payload":{...}}} instead.
     */
    String runTool(String name, String argsJson) = 4;

    /** API 1. Carries out a confirmed action: payload from runTool. Returns {"ok":bool,"note":"3 done, ...","error"?}. */
    String commit(String name, String payloadJson) = 5;

    /** API 1. Facts about indexed files: JSON array of {"item_id","name","kind","mime","date","folder","status"}; unknown ids are left out. */
    String items(in long[] ids) = 6;

    /** API 1. A JPEG thumbnail at most maxPx on its long side; null when the file has none (audio, failed files). */
    @nullable ParcelFileDescriptor thumbnail(long id, int maxPx) = 7;

    /** API 1. The indexed text of a file (pages, transcript lines, OCR text) up to maxChars; "" when it has none. */
    String readText(long id, int maxChars) = 8;

    /**
     * API 1. Files whose indexing finished after cursor (0 = from now on: returns no ids, only the current cursor).
     * kinds: comma separated document, image, audio, video ("" = all). Returns {"ids":[...],"cursor":N}; pass the
     * cursor back next time.
     */
    String changesSince(long cursor, String kinds, int limit) = 9;

    /** API 1. Which of ids match query by meaning or words ("a|b|c": any alternative). */
    long[] matchItems(String query, in long[] ids, String kinds) = 10;

    /** API 1. Names the user gave to people in their photos: JSON array of strings. */
    String people() = 11;
}
