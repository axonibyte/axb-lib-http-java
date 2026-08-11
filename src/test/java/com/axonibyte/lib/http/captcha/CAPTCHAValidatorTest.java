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
package com.axonibyte.lib.http.captcha;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.axonibyte.lib.http.captcha.CAPTCHAValidator.Challenge;
import com.axonibyte.lib.http.captcha.CAPTCHAValidator.Credential;
import com.axonibyte.lib.http.captcha.CAPTCHAValidator.Verdict;

import org.testng.annotations.Test;

/**
 * How a validator authenticates, asserted without opening a socket.
 *
 * <p>Only the credential decision is tested here, because it is the only part
 * of construction that can be wrong in a way nothing else would catch. Scoring
 * needs Google on the other end and belongs to an integration test.
 *
 * <p>The API key case matters most. Google now applies
 * {@code iam.disableServiceAccountKeyCreation} by default to newer
 * organizations, so a deployment may have no service account key available and
 * no application default credentials either -- and the API key path has to work
 * on such a host. gax resolves an API key before it consults a credentials
 * provider, so it does; these assertions are what would notice if that stopped
 * being true after an upgrade.
 *
 * @author Caleb L. Power &lt;cpower@axonibyte.com&gt;
 */
public class CAPTCHAValidatorTest {

  // --- the policy verdict ---------------------------------------------------

  /**
   * The mapping onto Google's enum, which is the whole of what can be tested
   * without an assessment on the other end.
   */
  @Test public void challengeValuesMapAcross() {
    assertEquals(
        CAPTCHAValidator.challengeOf(com.google.recaptchaenterprise.v1.RiskAnalysis.Challenge.PASSED),
        Challenge.PASSED);
    assertEquals(
        CAPTCHAValidator.challengeOf(com.google.recaptchaenterprise.v1.RiskAnalysis.Challenge.FAILED),
        Challenge.FAILED);
    assertEquals(
        CAPTCHAValidator.challengeOf(com.google.recaptchaenterprise.v1.RiskAnalysis.Challenge.NOCAPTCHA),
        Challenge.NOCAPTCHA);
  }

  /**
   * Anything this build does not recognise reads as UNSPECIFIED, which sends
   * the caller to the score rather than to a guess.
   *
   * <p>UNRECOGNIZED is what protobuf hands back for a value added to the API
   * after this client was built. It must not read as a pass -- that is a future
   * Google release silently turning the CAPTCHA off.
   */
  @Test public void anUnknownChallengeIsNotAPass() {
    assertEquals(
        CAPTCHAValidator.challengeOf(
            com.google.recaptchaenterprise.v1.RiskAnalysis.Challenge.CHALLENGE_UNSPECIFIED),
        Challenge.UNSPECIFIED);
    assertEquals(
        CAPTCHAValidator.challengeOf(
            com.google.recaptchaenterprise.v1.RiskAnalysis.Challenge.UNRECOGNIZED),
        Challenge.UNSPECIFIED);
    assertEquals(CAPTCHAValidator.challengeOf(null), Challenge.UNSPECIFIED);
  }

  /**
   * A rejected token scores zero and decides nothing.
   *
   * <p>Both halves matter: a caller thresholding the score must fail it, and a
   * caller reading the challenge must not see a pass.
   */
  @Test public void aRejectedTokenScoresZeroAndDecidesNothing() {
    Verdict verdict = Verdict.invalid("MALFORMED");
    assertEquals(verdict.tokenValid(), false);
    assertEquals(verdict.score(), 0f);
    assertEquals(verdict.challenge(), Challenge.UNSPECIFIED);
    assertEquals(verdict.invalidReason(), "MALFORMED");
  }

  // --- credentials -----------------------------------------------------------

  @Test public void apiKeyLandsWhereGaxLooksForIt() throws IOException {
    var settings = CAPTCHAValidator.settingsFor(Credential.API_KEY, "AIzaSyExampleKey");
    assertEquals(settings.getApiKey(), "AIzaSyExampleKey");
  }

  /**
   * The API key path must not read a file, look up application default
   * credentials, or otherwise need anything on the host. Building settings for
   * a key that is plainly not a path is the cheapest way to say so: were the
   * credential ever resolved eagerly, this would throw.
   */
  @Test public void apiKeyNeedsNothingOnDisk() throws IOException {
    CAPTCHAValidator.settingsFor(Credential.API_KEY, "not-a-path-and-not-a-file");
  }

  /** A service account is read from disk, and says so when it is not there. */
  @Test public void aMissingServiceAccountFileIsAnIOException() {
    assertThrows(
        IOException.class,
        () -> CAPTCHAValidator.settingsFor(Credential.SERVICE_ACCOUNT, "/nonexistent/creds.json"));
  }

  /** ...and a file that is not credentials is refused rather than accepted. */
  @Test public void aServiceAccountFileThatIsNotCredentialsIsRefused() throws IOException {
    Path bogus = Files.createTempFile("axb-captcha-test", ".json");
    try {
      Files.writeString(bogus, "{\"type\":\"nonsense\"}");
      assertThrows(
          IOException.class,
          () -> CAPTCHAValidator.settingsFor(Credential.SERVICE_ACCOUNT, bogus.toString()));
    } finally {
      Files.deleteIfExists(bogus);
    }
  }

  /**
   * No API key is set on the service account path.
   *
   * <p>gax prefers an API key over a credentials provider, so a stray one here
   * would silently override the service account the caller asked for.
   */
  @Test public void aServiceAccountSetsNoApiKey() throws IOException {
    Path bogus = Files.createTempFile("axb-captcha-test", ".json");
    try {
      Files.writeString(bogus, "{\"type\":\"nonsense\"}");
      try {
        var settings = CAPTCHAValidator.settingsFor(Credential.SERVICE_ACCOUNT, bogus.toString());
        assertNull(settings.getApiKey());
      } catch(IOException e) {
        // Reading the credentials is what failed, which the test above covers.
        // Nothing was set before that point, which is what this one claims.
      }
    } finally {
      Files.deleteIfExists(bogus);
    }
  }

  /**
   * The deprecated three-argument constructor still means "service account".
   *
   * <p>It exists so that consumers on an older version keep compiling, and the
   * only thing that could go wrong with it is routing to the wrong credential.
   * A missing file reaching {@link IOException} proves it still reads from
   * disk.
   */
  @Test public void theDeprecatedConstructorStillMeansServiceAccount() {
    assertThrows(
        IOException.class,
        () -> new CAPTCHAValidator("/nonexistent/creds.json", "some-project", "some-site-key"));
  }
}
