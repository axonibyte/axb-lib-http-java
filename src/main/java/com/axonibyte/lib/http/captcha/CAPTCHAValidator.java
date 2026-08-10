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
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.recaptchaenterprise.v1.RecaptchaEnterpriseServiceClient;
import com.google.cloud.recaptchaenterprise.v1.RecaptchaEnterpriseServiceSettings;
import com.google.recaptchaenterprise.v1.Assessment;
import com.google.recaptchaenterprise.v1.CreateAssessmentRequest;
import com.google.recaptchaenterprise.v1.Event;
import com.google.recaptchaenterprise.v1.ProjectName;
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
   * Scores the token provided in the reCAPTCHA response after a user submits
   * their CAPTCHA challenge.
   *
   * @param token the token returned by the reCAPTCHA service
   * @param action the specified action passed alongside the challenge (optional)
   * @param ip the IP address of the remote user (optional)
   * @return some number on a range of {@code 0.0f} to {@code 1.0f}, with
   *         {@code 1.0f} indicating with extreme confidence that the actor is
   *         a legitimate human user
   */
  public float score(String token, String action, String ip) {
    if(null == token || token.isBlank()) return 0f;
    
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
    
    Assessment assessment = client.createAssessment(request);
    
    if(!assessment.getTokenProperties().getValid())
      logger.error(
          "failed to create assessment: {}",
          assessment.getTokenProperties()
              .getInvalidReason()
              .name());
    
    else if(null != action
        && !action.equals(assessment.getTokenProperties().getAction()))
      logger.error(
          "mismatched action: got \"{}\" but needed \"{}\"",
          assessment.getTokenProperties().getAction(),
          action);
    
    else {
      
      StringBuilder classificationSB = new StringBuilder();
      for(ClassificationReason reason : assessment.getRiskAnalysis().getReasonsList()) {
        if(!classificationSB.isEmpty())
          classificationSB.append("; ");
        classificationSB.append(reason.toString());
      }
      
      var assessmentName = assessment.getName();
      
      logger.info(
          "user at {} achieved risk score of {} on assessment {}{}",
          null == ip ? "unspecified IP address" : ip,
          assessment.getRiskAnalysis().getScore(),
          assessmentName.substring(assessmentName.lastIndexOf("/") + 1),
          classificationSB.isEmpty() ? "" : (": " + classificationSB.toString()));
      
      return assessment.getRiskAnalysis().getScore();
      
    }
    
    return 0f;
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
