/*
 * Copyright (c) 2024 Axonibyte Innovations, LLC. All rights reserved.
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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.ApiException;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.recaptchaenterprise.v1.RecaptchaEnterpriseServiceClient;
import com.google.cloud.recaptchaenterprise.v1.RecaptchaEnterpriseServiceSettings;
import com.google.recaptchaenterprise.v1.Assessment;
import com.google.recaptchaenterprise.v1.CreateAssessmentRequest;
import com.google.recaptchaenterprise.v1.Event;
import com.google.recaptchaenterprise.v1.ProjectName;
import com.google.recaptchaenterprise.v1.RiskAnalysis;
import com.google.recaptchaenterprise.v1.RiskAnalysis.ClassificationReason;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates a Google RECAPTCHA token.
 *
 * @author Caleb L. Power <cpower@axonibyte.com>
 */
public class CAPTCHAValidator implements AutoCloseable {

  /**
   * Expected header to receive user's CAPTCHA response.
   */
  public static final String CAPTCHA_HEADER = "X-CAPTCHA-TOKEN";

  private final Logger logger = LoggerFactory.getLogger(CAPTCHAValidator.class);
  private final String projectID;
  private final String siteKey;
  private final RecaptchaEnterpriseServiceClient client;

  /**
   * How a validator proves who it is to Google.
   *
   * <p>Stated rather than inferred. It would be possible to guess from the
   * shape of the secret -- a path has slashes, a key does not -- but a guess
   * like that is right until somebody's key contains a slash or somebody's path
   * does not, and then it is wrong somewhere far from where it was made.
   *
   * @author Caleb L. Power &lt;cpower@axonibyte.com&gt;
   */
  public enum Credential {

    /**
     * A service account key, as a path to its JSON file on disk.
     *
     * <p>The historical option, and increasingly the awkward one: Google
     * applies the {@code iam.disableServiceAccountKeyCreation} organization
     * policy by default to newer organizations, which blocks the download this
     * needs. Any credential JSON {@link GoogleCredentials#fromStream} accepts
     * will do, including a workload identity federation configuration.
     */
    SERVICE_ACCOUNT,

    /**
     * An API key, restricted to the reCAPTCHA Enterprise API.
     *
     * <p>No file, no service account, and -- worth knowing -- no application
     * default credentials either: gax resolves an API key before it ever
     * consults a credentials provider, so this works on a host that has no
     * Google credentials of any kind. That is the whole point of it.
     */
    API_KEY
  }

  /**
   * Builds the client settings for a credential.
   *
   * <p>Package-visible so it can be tested without opening a client or a
   * socket, which is the only part of construction worth asserting: that an API
   * key lands where gax looks for one, and that a service account is read from
   * disk.
   *
   * @param credential which kind of secret {@code secret} is
   * @param secret the path to a credentials file, or the API key itself
   * @return the settings builder, ready to build
   * @throws IOException if a credentials file could not be read
   */
  static RecaptchaEnterpriseServiceSettings.Builder settingsFor(
      Credential credential, String secret) throws IOException {
    var settings = RecaptchaEnterpriseServiceSettings.newBuilder();

    switch(credential) {
      case SERVICE_ACCOUNT -> {
        // try-with-resources: the stream used to be handed to fromStream and
        // never closed, so every validator leaked a file descriptor for the
        // life of the process.
        try(InputStream creds = new FileInputStream(secret)) {
          settings.setCredentialsProvider(
              FixedCredentialsProvider.create(
                  GoogleCredentials.fromStream(creds)));
        }
      }

      case API_KEY -> settings.setApiKey(secret);
    }

    return settings;
  }

  /**
   * Instantiates the CAPTCHA validator.
   *
   * @param credential which kind of secret {@code secret} is
   * @param secret the path to a credentials JSON file, or an API key
   * @param projectID the Google Cloud project ID
   * @param siteKey the reCAPTCHA site key
   * @throws IOException if the credentials could not be read
   */
  public CAPTCHAValidator(
      Credential credential, String secret, String projectID, String siteKey) throws IOException {
    this.projectID = projectID;
    this.siteKey = siteKey;
    this.client = RecaptchaEnterpriseServiceClient.create(
        settingsFor(credential, secret).build());
  }

  /**
   * Instantiates the CAPTCHA validator against a service account.
   *
   * @param credsFile the path to the credentials JSON file associated with the
   *        service account
   * @param projectID the Google Cloud project ID associated with the service
   *        account
   * @param siteKey the reCAPTCHA site key
   * @throws IOException if the credentials could not be read
   * @deprecated say which kind of credential it is; use
   *             {@link #CAPTCHAValidator(Credential, String, String, String)}.
   *             <strong>Removed in 0.6.0.</strong>
   */
  @Deprecated public CAPTCHAValidator(
      String credsFile, String projectID, String siteKey) throws IOException {
    this(Credential.SERVICE_ACCOUNT, credsFile, projectID, siteKey);
  }

  /**
   * What the policy engine did about a token.
   *
   * <p>Mirrored rather than re-exported so a consumer never has to import a
   * protobuf type to read a verdict. The names match Google's enum exactly,
   * because a translation layer that renamed them would be one more thing to
   * check when reading a log line against their documentation.
   *
   * @author Caleb L. Power &lt;cpower@axonibyte.com&gt;
   */
  public enum Challenge {

    /**
     * The key does not do policy-based challenges, so nothing decided anything.
     *
     * <p>What a checkbox or score-based key always reports. A caller reading
     * this must fall back to the score.
     */
    UNSPECIFIED,

    /** The policy looked and chose not to challenge. That is a pass. */
    NOCAPTCHA,

    /** A challenge was presented and solved. */
    PASSED,

    /** A challenge was presented and failed, or was answered suspiciously. */
    FAILED
  }

  /**
   * Everything an assessment said, so the caller can decide.
   *
   * <p>Deliberately reports rather than judges. The two kinds of key are read
   * two different ways -- a checkbox or score-based key by thresholding
   * {@link #score()}, a policy-based one by reading {@link #challenge()} -- and
   * which of those is right depends on how the key was created, which is
   * something this library cannot see and the deployment configuring it can.
   *
   * @param tokenValid whether Google accepted the token at all
   * @param invalidReason why it did not, or {@code null} when it did
   * @param score the risk score, {@code 0.0f} when the token was rejected
   * @param challenge the policy verdict, {@link Challenge#UNSPECIFIED} unless
   *        the key does policy-based challenges
   */
  public static record Verdict(
      boolean tokenValid, String invalidReason, float score, Challenge challenge) {

    /** A token Google would not accept. Scores zero and decides nothing. */
    static Verdict invalid(String reason) {
      return new Verdict(false, reason, 0f, Challenge.UNSPECIFIED);
    }
  }

  /**
   * Maps Google's challenge enum onto ours.
   *
   * <p>Anything unrecognised becomes {@link Challenge#UNSPECIFIED}, which sends
   * the caller to the score rather than to a guess. A future value this build
   * has never heard of must not read as a pass.
   *
   * @param challenge the value from the assessment
   * @return the mirrored value
   */
  static Challenge challengeOf(RiskAnalysis.Challenge challenge) {
    if(null == challenge) return Challenge.UNSPECIFIED;
    return switch(challenge) {
      case NOCAPTCHA -> Challenge.NOCAPTCHA;
      case PASSED -> Challenge.PASSED;
      case FAILED -> Challenge.FAILED;
      default -> Challenge.UNSPECIFIED;
    };
  }

  /**
   * Scores the token provided in the reCAPTCHA response.
   *
   * <p>Retained for callers that threshold a score -- which is the right way to
   * read a checkbox or score-based key, and the wrong way to read a
   * policy-based one. Use {@link #assess(String, String, String)} if the key
   * might be policy-based.
   *
   * @param token the token returned by the reCAPTCHA service
   * @param action the specified action passed alongside the challenge (optional)
   * @param ip the IP address of the remote user (optional)
   * @return some number on a range of {@code 0.0f} to {@code 1.0f}, with
   *         {@code 1.0f} indicating with extreme confidence that the actor is
   *         a legitimate human user; {@code 0.0f} if the token was rejected
   */
  public float score(String token, String action, String ip) {
    return assess(token, action, ip).score();
  }

  /**
   * Assesses the token provided in the reCAPTCHA response.
   *
   * <p>Reports everything the assessment said and decides nothing. Which part
   * of the answer matters depends on how the key was created:
   *
   * <ul>
   *   <li>a <strong>checkbox</strong> or <strong>score-based</strong> key
   *       reports {@link Challenge#UNSPECIFIED} and is read by thresholding
   *       {@link Verdict#score()};
   *   <li>a <strong>policy-based challenge</strong> key is read by
   *       {@link Verdict#challenge()}, where both {@link Challenge#PASSED} and
   *       {@link Challenge#NOCAPTCHA} mean the policy engine was satisfied --
   *       the second because it looked and decided not to challenge.
   * </ul>
   *
   * @param token the token returned by the reCAPTCHA service
   * @param action the action expected alongside the challenge, or {@code null}
   *        to accept whichever action the token carries
   * @param ip the IP address of the remote user (optional)
   * @return what the assessment said; never throws, and reports a service that
   *         could not be reached as an invalid verdict carrying its status
   */
  public Verdict assess(String token, String action, String ip) {
    if(null == token || token.isBlank())
      return Verdict.invalid("no token was supplied");

    logger.info(
        "analyzing reCAPTCHA token for user at {}",
        null == ip ? "unspecified IP address" : ip);

    var eventBuilder = Event.newBuilder()
        .setSiteKey(siteKey)
        .setToken(token);
    if(null != ip)
      eventBuilder.setUserIpAddress(ip);

    CreateAssessmentRequest request = CreateAssessmentRequest.newBuilder()
        .setParent(
            ProjectName.of(projectID).toString())
        .setAssessment(
            Assessment.newBuilder()
                .setEvent(eventBuilder.build())
                .build())
        .build();

    Assessment assessment;
    try {
      assessment = client.createAssessment(request);
    } catch(ApiException e) {
      // Fails closed, and does not escape. Every other way this method declines
      // a token is a Verdict, so letting a transport failure leave as an
      // unchecked Google exception makes callers handle two shapes of "no" --
      // and the one they will forget is the one that only happens when the key
      // is wrong, the quota is spent, or Google is unreachable.
      //
      // Those are exactly the circumstances where a clear refusal beats a stack
      // trace: a caller that reads this as "not human" refuses the request,
      // which is the safe answer, and the status is on the verdict for anyone
      // who wants to tell it apart from a bad token.
      String status = e.getStatusCode().getCode().name();
      logger.error(
          "could not assess a reCAPTCHA token ({}): {}",
          status,
          e.getMessage());
      return Verdict.invalid(status);
    }

    if(!assessment.getTokenProperties().getValid()) {
      String reason = assessment.getTokenProperties().getInvalidReason().name();
      logger.error("failed to create assessment: {}", reason);
      return Verdict.invalid(reason);
    }

    if(null != action && !action.equals(assessment.getTokenProperties().getAction())) {
      logger.error(
          "mismatched action: got \"{}\" but needed \"{}\"",
          assessment.getTokenProperties().getAction(),
          action);
      return Verdict.invalid("action mismatch");
    }

    StringBuilder classificationSB = new StringBuilder();
    for(ClassificationReason reason : assessment.getRiskAnalysis().getReasonsList()) {
      if(!classificationSB.isEmpty())
        classificationSB.append("; ");
      classificationSB.append(reason.toString());
    }

    var assessmentName = assessment.getName();
    Challenge challenge = challengeOf(assessment.getRiskAnalysis().getChallenge());

    logger.info(
        "user at {} achieved risk score of {} (challenge {}) on assessment {}{}",
        null == ip ? "unspecified IP address" : ip,
        assessment.getRiskAnalysis().getScore(),
        challenge.name(),
        assessmentName.substring(assessmentName.lastIndexOf("/") + 1),
        classificationSB.isEmpty() ? "" : (": " + classificationSB.toString()));

    return new Verdict(
        true,
        null,
        assessment.getRiskAnalysis().getScore(),
        challenge);
  }

  /**
   * Retrieves the project ID associated with this CAPTCHA validator.
   *
   * @return the project ID
   */
  public String getProjectID() {
    return projectID;
  }

  /**
   * Retrieves the site key associated with this CAPTCHA validator.
   *
   * @return the site key
   */
  public String getSiteKey() {
    return siteKey;
  }

  /**
   * {@inheritDoc}
   */
  @Override public void close() {
    client.close();
    client.shutdown();
  }
  
}
