# Subsonic authentication parameters — `subsonic-auth.md`

Purpose: Pins down how the Subsonic client builds authentication query parameters and encodes HTTP URLs.

How derived: OpenSubsonic authentication-extension rules, Subsonic API parameter semantics, and failing unit tests against MockWebServer observed during T1.

Confidence: [VERIFIED] raw values are encoded exactly once by `HttpUrl.Builder`; [VERIFIED] API-key mode sends only `apiKey`, `v`, `c`, and `f`; [VERIFIED] legacy password mode sends `p=enc:<hex>`.

Date: 2026-10-30

Links: `../../Prompt.md` T1; `provider-navidrome/src/main/java/app/omnisonic/provider/navidrome/SubsonicClient.kt`.

---

`SubsonicClient` passes raw parameter values to `okhttp3.HttpUrl.Builder.addQueryParameter`, which performs one round of URL encoding. Authentication parameters are:

- Token mode: `u`, `t`, `s`, `v`, `c`, `f`.
- Legacy password mode: `u`, `p` (`enc:` followed by lowercase UTF-8 hex), `v`, `c`, `f`.
- API-key mode: `apiKey`, `v`, `c`, `f` only. No `u`, `t`, `s`, or `p` is present.

[VERIFIED] Usernames containing reserved characters such as `+`, `@`, and spaces decode back to their original value from the request URL.
