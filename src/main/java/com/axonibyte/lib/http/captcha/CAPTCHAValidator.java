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
   * Instantiates the CAPTCHA validator.
   *
   * @param credsFile the path to the credentials JSON file associated with the
   *        service account
   * @param projectID the Google Cloud project ID associated with the service
   *        account
   * @param siteKey the reCAPTCHA site key
   * @throws IOException if the credentials could not be read
   */
  public CAPTCHAValidator(String credsFile, String projectID, String siteKey) throws IOException {
    this.projectID = projectID;
    this.siteKey = siteKey;
    this.client = RecaptchaEnterpriseServiceClient.create(
        RecaptchaEnterpriseServiceSettings.newBuilder()
            .setCredentialsProvider(
                FixedCredentialsProvider.create(
                    GoogleCredentials.fromStream(
                        new FileInputStream(credsFile))))
            .build());
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
