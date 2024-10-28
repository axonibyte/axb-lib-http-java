/*
 * Copyright (c) 2020-2023 Axonibyte Innovations, LLC. All rights reserved.
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

import java.io.IOException;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Deprecated public class LegacyCAPTCHAValidator {

  /**
   * Expected header to receive user's reCAPTCHA v2 response.
   */
  public static final String CAPTCHA_HEADER = "X-G-reCAPTCHA-Response";
  
  private static final OkHttpClient HTTP_CLIENT = new OkHttpClient();
  private static final String API_ENDPOINT = "https://www.google.com/recaptcha/api/siteverify";
  private static final String SECRET_PARAM = "secret";
  private static final String RESPONSE_PARAM = "response";
  private static final String REMOTE_IP_PARAM = "remoteip";
  private static final String SUCCESS_FIELD = "success";

  private final Logger logger = LoggerFactory.getLogger(LegacyCAPTCHAValidator.class);
  private String secret = null;
  
  /**
   * Overloaded constructor.
   * 
   * @param secret the reCAPTCHA secret key
   */
  public LegacyCAPTCHAValidator(String secret) {
    this.secret = secret;
  }
  
  /**
   * Verifies the user's response to the reCAPTCHA v2 challenge.
   * 
   * @param captchaResponse the user's CAPTCHA response
   * @return {@code true} iff the user passed the challenge
   */
  public boolean verify(String captchaResponse) {
    return verify(captchaResponse, null);
  }
  
  /**
   * Verifies the user's response to the reCAPTCHA v2 challenge. Additionally
   * verifies the user's IP address against malicious activity lists.
   * 
   * @param captchaResponse the user's CAPTCHA response
   * @param remoteIP optionally, the user's IP address
   * @return {@code true} iff the user passed the challenge
   */
  public boolean verify(String captchaResponse, String remoteIP) {
    if(captchaResponse == null) return false;
    
    var formBody = new FormBody.Builder()
        .add(SECRET_PARAM, secret)
        .add(RESPONSE_PARAM, captchaResponse);
    if(remoteIP != null)
      formBody.add(REMOTE_IP_PARAM, remoteIP);
    
    final Request request = new Request.Builder()
        .url(API_ENDPOINT)
        .post(formBody.build())
        .build();
    
    try(Response response = HTTP_CLIENT.newCall(request).execute()) {
      JSONObject body = new JSONObject(response.body().string());
      return body.getBoolean(SUCCESS_FIELD);
    } catch(IOException | JSONException e) {
      logger.error("Ran into issues verifying reCAPTCHA response: {}", e.getMessage());
    }
    
    return false;
  }  

}
