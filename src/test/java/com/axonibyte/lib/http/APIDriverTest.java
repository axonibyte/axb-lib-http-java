/*
 * Copyright (c) 2026 Axonibyte Innovations, LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.axonibyte.lib.http;

import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests the CORS origin-matching rule.
 *
 * <p>Access-Control-Allow-Origin admits a single origin or {@code *} and nothing
 * else. The previous implementation emitted the configured allowlist verbatim, so a
 * multi-origin configuration produced a comma-joined header that browsers reject --
 * silently blocking every configured origin rather than permitting them.</p>
 *
 * @author Caleb L. Power <cpower@axonibyte.com>
 */
public class APIDriverTest {

  private static final Set<String> ALLOWLIST = Set.of(
      "https://app.example.com",
      "https://admin.example.com");

  @Test public void resolveAllowOrigin_wildcard_returnsWildcard() {
    Assert.assertEquals(
        APIDriver.resolveAllowOrigin("https://anything.example.com", Set.of("*"), true),
        "*");
  }

  @Test public void resolveAllowOrigin_wildcard_returnsWildcardEvenWithoutOriginHeader() {
    Assert.assertEquals(
        APIDriver.resolveAllowOrigin(null, Set.of("*"), true),
        "*");
  }

  @Test public void resolveAllowOrigin_matchingOrigin_echoesThatOriginAlone() {
    // The critical property: exactly one origin comes back, never the joined allowlist.
    String resolved = APIDriver.resolveAllowOrigin(
        "https://app.example.com", ALLOWLIST, false);
    Assert.assertEquals(resolved, "https://app.example.com");
    Assert.assertFalse(resolved.contains(","), "header must never be a list");
  }

  @Test public void resolveAllowOrigin_secondMatchingOrigin_alsoEchoesThatOriginAlone() {
    Assert.assertEquals(
        APIDriver.resolveAllowOrigin("https://admin.example.com", ALLOWLIST, false),
        "https://admin.example.com");
  }

  @Test public void resolveAllowOrigin_unlistedOrigin_isOmitted() {
    Assert.assertNull(
        APIDriver.resolveAllowOrigin("https://evil.example.com", ALLOWLIST, false));
  }

  @Test public void resolveAllowOrigin_absentOriginHeader_isOmitted() {
    Assert.assertNull(APIDriver.resolveAllowOrigin(null, ALLOWLIST, false));
  }

  @Test public void resolveAllowOrigin_isCaseSensitiveAndExact() {
    // Origins are compared byte-for-byte; no scheme/host normalization is performed,
    // so near-misses must not match.
    Assert.assertNull(
        APIDriver.resolveAllowOrigin("https://APP.example.com", ALLOWLIST, false));
    Assert.assertNull(
        APIDriver.resolveAllowOrigin("http://app.example.com", ALLOWLIST, false));
    Assert.assertNull(
        APIDriver.resolveAllowOrigin("https://app.example.com:8443", ALLOWLIST, false));
    Assert.assertNull(
        APIDriver.resolveAllowOrigin("https://app.example.com.evil.tld", ALLOWLIST, false));
  }

  @Test public void resolveAllowOrigin_emptyAllowlist_rejectsEverything() {
    Assert.assertNull(
        APIDriver.resolveAllowOrigin("https://app.example.com", Set.of(), false));
  }

}
